package com.example.dropboxbackupfixer.ui.upload

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.dropboxbackupfixer.service.UploadManager
import com.example.dropboxbackupfixer.service.UploadService
import com.example.dropboxbackupfixer.service.UploadUiState
import com.example.dropboxbackupfixer.util.KeepScreenOn
import com.example.dropboxbackupfixer.util.formatFileSize

@Composable
fun UploadProgressScreen(onNavigate: (NavKey) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val uiState by UploadManager.state.collectAsState()

    // Keep the display on while watching the upload (it also continues in the background)
    KeepScreenOn()

    val returnToSummary = {
        UploadManager.acknowledgeResult()
        onBack()
    }

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
                Text("No upload is currently running.", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = returnToSummary) { Text("Return to Summary") }
            }
            is UploadUiState.Preparing -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Preparing upload…")
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
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "You can switch apps or turn off the screen — the upload keeps going in the background. " +
                        "Progress is shown in your notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is UploadUiState.Complete -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (state.note == null) "Upload Complete!" else "Upload Stopped",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text("Successfully uploaded ${state.successCount} files.")
                if (state.errorCount > 0) {
                    Text("Failed to upload ${state.errorCount} files.", color = MaterialTheme.colorScheme.error)
                }
                state.note?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = returnToSummary, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Return to Summary", fontSize = 16.sp)
                }
            }
            is UploadUiState.Error -> {
                Text("Error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(state.message)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = returnToSummary) { Text("Go Back") }
            }
        }

        if (UploadManager.isActive) {
            Spacer(modifier = Modifier.height(48.dp))
            OutlinedButton(onClick = {
                UploadService.cancel(context)
                onBack()
            }) {
                Text("Cancel Upload")
            }
        }
    }
}
