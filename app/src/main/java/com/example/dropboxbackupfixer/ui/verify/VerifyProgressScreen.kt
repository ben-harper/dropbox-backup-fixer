package com.example.dropboxbackupfixer.ui.verify

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.dropboxbackupfixer.CatalogueSummary
import com.example.dropboxbackupfixer.data.local.AppDatabase
import com.example.dropboxbackupfixer.data.remote.DropboxAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface VerifyUiState {
    data object Idle : VerifyUiState
    data class Fetching(
        val filesFound: Int,
        val message: String
    ) : VerifyUiState
    data class CrossReferencing(
        val filesProcessed: Int,
        val totalFiles: Int,
        val missingCount: Int,
        val matchedCount: Int
    ) : VerifyUiState
    data class Complete(
        val missingCount: Int,
        val matchedCount: Int
    ) : VerifyUiState
    data class Error(val message: String) : VerifyUiState
}

class VerifyViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<VerifyUiState>(VerifyUiState.Idle)
    val uiState: StateFlow<VerifyUiState> = _uiState.asStateFlow()
    
    private var verifyJob: Job? = null
    private val db = AppDatabase.getInstance(application)
    private val mediaFileDao = db.mediaFileDao()

    init {
        startVerification()
    }

    private fun startVerification() {
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = DropboxAuthManager.getClient(getApplication())
                if (client == null) {
                    _uiState.update { VerifyUiState.Error("Not connected to Dropbox") }
                    return@launch
                }

                _uiState.update { VerifyUiState.Fetching(0, "Connecting to Dropbox...") }

                val dropboxHashes = mutableMapOf<String, String>() // hash -> path

                // 1. Fetch metadata from Dropbox (let's scan /Camera Uploads first)
                // If it fails or is empty, we could fallback, but let's just try /Camera Uploads
                // Wait, it's safer to just scan the entire Dropbox? "path must match pattern" for root is ""
                var result = try {
                    client.files().listFolderBuilder("").withRecursive(true).start()
                } catch (e: Exception) {
                    null
                }

                if (result != null) {
                    var found = 0
                    while (true) {
                        for (metadata in result!!.entries) {
                            if (metadata is com.dropbox.core.v2.files.FileMetadata) {
                                val hash = metadata.contentHash
                                if (hash != null) {
                                    dropboxHashes[hash] = metadata.pathDisplay ?: metadata.name
                                    found++
                                    if (found % 100 == 0) {
                                        _uiState.update { VerifyUiState.Fetching(found, "Fetching file list...") }
                                    }
                                }
                            }
                        }
                        if (!result!!.hasMore) break
                        result = client.files().listFolderContinue(result!!.cursor)
                    }
                }

                // 2. Cross reference
                val localFiles = mediaFileDao.getAllFiles()
                val total = localFiles.size
                var processed = 0
                var missing = 0
                var matched = 0

                _uiState.update { VerifyUiState.CrossReferencing(0, total, 0, 0) }

                for (file in localFiles) {
                    val hash = file.contentHash
                    if (hash != null && dropboxHashes.containsKey(hash)) {
                        val dbxPath = dropboxHashes[hash]
                        mediaFileDao.updateBackupStatus(file.filePath, "BACKED_UP", dbxPath)
                        matched++
                    } else {
                        mediaFileDao.updateBackupStatus(file.filePath, "MISSING", null)
                        missing++
                    }
                    processed++
                    
                    if (processed % 50 == 0) {
                        _uiState.update { VerifyUiState.CrossReferencing(processed, total, missing, matched) }
                    }
                }
                
                // Final update
                _uiState.update { VerifyUiState.Complete(missing, matched) }

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { VerifyUiState.Error(e.message ?: "Error verifying with Dropbox") }
            }
        }
    }

    fun cancel() {
        verifyJob?.cancel()
    }
}

class VerifyViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VerifyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VerifyViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun VerifyProgressScreen(onNavigate: (NavKey) -> Unit, onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    // Scope it using the scanId so it's fresh for each new scan
    val viewModel: VerifyViewModel = viewModel(
        key = com.example.dropboxbackupfixer.ScanConfig.verifyRunId,
        factory = VerifyViewModelFactory(application)
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is VerifyUiState.Complete) {
            // We can navigate back to CatalogueSummary, which will now show the new status!
            // Or a new screen entirely. For now, go back to summary.
            onBack()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Verifying with Dropbox",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        when (val state = uiState) {
            is VerifyUiState.Idle -> {
                CircularProgressIndicator()
            }
            is VerifyUiState.Fetching -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(24.dp))
                Text(state.message, style = MaterialTheme.typography.titleMedium)
                Text("${state.filesFound} files indexed from cloud")
            }
            is VerifyUiState.CrossReferencing -> {
                val progress = if (state.totalFiles > 0) state.filesProcessed.toFloat() / state.totalFiles else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("Comparing local files...", style = MaterialTheme.typography.titleMedium)
                Text("${state.filesProcessed} / ${state.totalFiles} processed")
                Spacer(modifier = Modifier.height(16.dp))
                Text("Matched: ${state.matchedCount}", color = MaterialTheme.colorScheme.primary)
                Text("Missing: ${state.missingCount}", color = MaterialTheme.colorScheme.error)
            }
            is VerifyUiState.Complete -> {
                CircularProgressIndicator()
                Text("Finalizing...", modifier = Modifier.padding(top = 16.dp))
            }
            is VerifyUiState.Error -> {
                Text("Error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(state.message)
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        if (uiState !is VerifyUiState.Complete) {
            OutlinedButton(onClick = { viewModel.cancel(); onBack() }) {
                Text("Cancel")
            }
        }
    }
}
