package com.example.dropboxbackupfixer.ui.scan

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.navigation3.runtime.NavKey
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
import com.example.dropboxbackupfixer.CatalogueSummary
import com.example.dropboxbackupfixer.ScanConfig
import com.example.dropboxbackupfixer.data.local.AppDatabase
import com.example.dropboxbackupfixer.data.local.ContentHasher
import com.example.dropboxbackupfixer.data.local.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Discovering(
        val filesFound: Int,
        val currentFolder: String
    ) : ScanUiState
    data class Hashing(
        val filesProcessed: Int,
        val totalFiles: Int,
        val bytesProcessed: Long,
        val totalBytes: Long,
        val currentFileName: String,
        val estimatedSecondsRemaining: Long?
    ) : ScanUiState
    data class Complete(
        val totalFiles: Int,
        val totalSizeBytes: Long,
        val photoCount: Int,
        val videoCount: Int
    ) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()
    
    private var scanJob: Job? = null
    private val db = AppDatabase.getInstance(application)
    private val mediaFileDao = db.mediaFileDao()

    init {
        startScan(ScanConfig.selectedFolderPaths)
    }

    private fun startScan(folderPaths: List<String>) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Phase A: Discovery
                _uiState.update { ScanUiState.Discovering(0, "Starting...") }
                
                val discoveredFiles = MediaScanner.scanFiles(
                    context = getApplication(),
                    folderPaths = folderPaths,
                    onProgress = { count, folder ->
                        _uiState.update { ScanUiState.Discovering(count, folder) }
                    }
                )
                
                // Save to DB
                mediaFileDao.insertAll(discoveredFiles)
                
                // Get files needing hashes
                val allFiles = mediaFileDao.getAllFiles()
                val filesToHash = allFiles.filter { it.contentHash == null }
                
                // Phase B: Hashing
                val totalFiles = allFiles.size
                var processedFiles = totalFiles - filesToHash.size
                val totalBytes = filesToHash.sumOf { it.fileSizeBytes }
                var bytesProcessedSoFar = 0L
                val startTime = System.currentTimeMillis()
                
                _uiState.update { 
                    ScanUiState.Hashing(
                        filesProcessed = processedFiles,
                        totalFiles = totalFiles,
                        bytesProcessed = 0L,
                        totalBytes = totalBytes,
                        currentFileName = "Preparing...",
                        estimatedSecondsRemaining = null
                    ) 
                }

                for (mediaFile in filesToHash) {
                    val file = File(mediaFile.filePath)
                    if (!file.exists()) {
                        processedFiles++
                        continue
                    }

                    var lastFileBytesProcessed = 0L
                    
                    val hash = ContentHasher.computeHash(file) { fileBytesProcessed, _ ->
                        val diff = fileBytesProcessed - lastFileBytesProcessed
                        lastFileBytesProcessed = fileBytesProcessed
                        bytesProcessedSoFar += diff
                        
                        val elapsedSecs = (System.currentTimeMillis() - startTime) / 1000.0
                        val bytesPerSec = if (elapsedSecs > 0) bytesProcessedSoFar / elapsedSecs else 0.0
                        val remainingBytes = totalBytes - bytesProcessedSoFar
                        val estRemaining = if (bytesPerSec > 0) (remainingBytes / bytesPerSec).toLong() else null
                        
                        _uiState.update {
                            ScanUiState.Hashing(
                                filesProcessed = processedFiles,
                                totalFiles = totalFiles,
                                bytesProcessed = bytesProcessedSoFar,
                                totalBytes = totalBytes,
                                currentFileName = file.name,
                                estimatedSecondsRemaining = estRemaining
                            )
                        }
                    }
                    
                    mediaFileDao.updateContentHash(mediaFile.filePath, hash, System.currentTimeMillis())
                    processedFiles++
                }
                
                // Complete
                val finalFiles = mediaFileDao.getAllFiles()
                _uiState.update {
                    ScanUiState.Complete(
                        totalFiles = finalFiles.size,
                        totalSizeBytes = finalFiles.sumOf { it.fileSizeBytes },
                        photoCount = finalFiles.count { !it.isVideo },
                        videoCount = finalFiles.count { it.isVideo }
                    )
                }

            } catch (e: Exception) {
                _uiState.update { ScanUiState.Error(e.message ?: "Error during scan") }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }
}

class ScanViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScanViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ScanViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun ScanProgressScreen(onNavigate: (NavKey) -> Unit, onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: ScanViewModel = viewModel(
        factory = ScanViewModelFactory(application)
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is ScanUiState.Complete) {
            onNavigate(CatalogueSummary)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Cataloguing your photos",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        when (val state = uiState) {
            is ScanUiState.Idle -> {
                CircularProgressIndicator()
                Text("Initializing scan...", modifier = Modifier.padding(top = 16.dp))
            }
            is ScanUiState.Discovering -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Discovering files...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.filesFound} files found",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    text = "Scanning: ${state.currentFolder}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is ScanUiState.Hashing -> {
                val progress = if (state.totalBytes > 0) {
                    (state.bytesProcessed.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    state.filesProcessed.toFloat() / state.totalFiles.coerceAtLeast(1).toFloat()
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Computing file signatures...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.filesProcessed} of ${state.totalFiles} files processed",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    text = "Current file: ${state.currentFileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                
                if (state.estimatedSecondsRemaining != null) {
                    val mins = state.estimatedSecondsRemaining / 60
                    val secs = state.estimatedSecondsRemaining % 60
                    Text(
                        text = "Estimated time remaining: ${mins}m ${secs}s",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                
                Text(
                    text = "This may take a few minutes depending on your library size.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            is ScanUiState.Complete -> {
                CircularProgressIndicator()
                Text("Finalizing...", modifier = Modifier.padding(top = 16.dp))
            }
            is ScanUiState.Error -> {
                Text(
                    text = "Error occurred",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        if (uiState !is ScanUiState.Complete) {
            OutlinedButton(
                onClick = { 
                    viewModel.cancelScan()
                    onBack()
                }
            ) {
                Text("Cancel Scan")
            }
        }
    }
}
