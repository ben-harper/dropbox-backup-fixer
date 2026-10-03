package com.example.dropboxbackupfixer.ui.upload

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.dropbox.core.v2.files.WriteMode
import com.example.dropboxbackupfixer.data.local.AppDatabase
import com.example.dropboxbackupfixer.data.remote.DropboxAuthManager
import com.example.dropboxbackupfixer.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

sealed interface UploadUiState {
    data object Idle : UploadUiState
    data class Uploading(
        val fileIndex: Int,
        val totalFiles: Int,
        val currentFileName: String,
        val bytesUploaded: Long,
        val currentFileSize: Long,
        val overallBytesUploaded: Long,
        val overallTotalBytes: Long
    ) : UploadUiState
    data class Complete(val successCount: Int, val errorCount: Int) : UploadUiState
    data class Error(val message: String) : UploadUiState
}

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()
    
    private var uploadJob: Job? = null
    private val db = AppDatabase.getInstance(application)
    private val mediaFileDao = db.mediaFileDao()

    init {
        startUpload()
    }

    private fun startUpload() {
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = DropboxAuthManager.getClient(getApplication())
                if (client == null) {
                    _uiState.update { UploadUiState.Error("Not connected to Dropbox") }
                    return@launch
                }

                val missingFiles = mediaFileDao.getFilesByStatus("MISSING")
                if (missingFiles.isEmpty()) {
                    _uiState.update { UploadUiState.Complete(0, 0) }
                    return@launch
                }

                val totalFiles = missingFiles.size
                val overallTotalBytes = missingFiles.sumOf { it.fileSizeBytes }
                var overallBytesUploaded = 0L
                var successCount = 0
                var errorCount = 0

                for ((index, fileEntity) in missingFiles.withIndex()) {
                    val file = File(fileEntity.filePath)
                    if (!file.exists() || !file.canRead()) {
                        errorCount++
                        continue
                    }
                    
                    val fileSize = fileEntity.fileSizeBytes
                    
                    _uiState.update { 
                        UploadUiState.Uploading(
                            fileIndex = index + 1,
                            totalFiles = totalFiles,
                            currentFileName = fileEntity.fileName,
                            bytesUploaded = 0,
                            currentFileSize = fileSize,
                            overallBytesUploaded = overallBytesUploaded,
                            overallTotalBytes = overallTotalBytes
                        )
                    }

                    try {
                        FileInputStream(file).use { input ->
                            // For simplicity, we use simple upload.
                            // The Dropox v2 API has uploadBuilder which supports uploading large files via stream.
                            // If it fails on large files, we could upgrade to upload_session, but this is fine for MVP.
                            val dbxPath = "/Camera Uploads/${fileEntity.fileName}"
                            val metadata = client.files().uploadBuilder(dbxPath)
                                .withMode(WriteMode.ADD)
                                .uploadAndFinish(input)
                            
                            mediaFileDao.updateBackupStatus(fileEntity.filePath, "BACKED_UP", metadata.pathDisplay)
                            successCount++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        errorCount++
                    }
                    
                    overallBytesUploaded += fileSize
                }
                
                _uiState.update { UploadUiState.Complete(successCount, errorCount) }

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { UploadUiState.Error(e.message ?: "Error during upload") }
            }
        }
    }

    fun cancel() {
        uploadJob?.cancel()
    }
}

class UploadViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UploadViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UploadViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun UploadProgressScreen(onNavigate: (NavKey) -> Unit, onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: UploadViewModel = viewModel(
        key = com.example.dropboxbackupfixer.ScanConfig.scanId + "_upload",
        factory = UploadViewModelFactory(application)
    )
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Uploading to Dropbox",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        when (val state = uiState) {
            is UploadUiState.Idle -> {
                CircularProgressIndicator()
            }
            is UploadUiState.Uploading -> {
                val overallProgress = if (state.overallTotalBytes > 0) {
                    state.overallBytesUploaded.toFloat() / state.overallTotalBytes
                } else 0f
                
                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier.fillMaxWidth().height(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    "Uploading file ${state.fileIndex} of ${state.totalFiles}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    state.currentFileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${formatFileSize(state.overallBytesUploaded)} / ${formatFileSize(state.overallTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is UploadUiState.Complete -> {
                Icon(
                    androidx.compose.material.icons.Icons.Default.CheckCircle,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Upload Complete!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Successfully uploaded ${state.successCount} files.")
                if (state.errorCount > 0) {
                    Text("Failed to upload ${state.errorCount} files.", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { onBack() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Return to Summary", fontSize = 16.sp)
                }
            }
            is UploadUiState.Error -> {
                Text("Error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(state.message)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { onBack() }) {
                    Text("Go Back")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        if (uiState !is UploadUiState.Complete) {
            OutlinedButton(onClick = { viewModel.cancel(); onBack() }) {
                Text("Cancel Upload")
            }
        }
    }
}
