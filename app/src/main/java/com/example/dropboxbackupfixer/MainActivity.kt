package com.example.dropboxbackupfixer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dropboxbackupfixer.theme.DropboxBackupFixerTheme

class MainActivity : ComponentActivity() {
  override fun onResume() {
    super.onResume()
    com.example.dropboxbackupfixer.data.remote.DropboxAuthManager.handleAuthCallback(this)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      DropboxBackupFixerTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          var permissionsGranted by remember { mutableStateOf(false) }
          
          val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
          } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
          }

          val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
          ) { permissions ->
            permissionsGranted = permissions.values.all { it }
          }

          LaunchedEffect(Unit) {
            launcher.launch(permissionsToRequest)
          }

          if (permissionsGranted) {
            MainNavigation()
          } else {
            Column(
              modifier = Modifier.fillMaxSize().padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
            ) {
              Text("Permissions required to scan media.", style = MaterialTheme.typography.bodyLarge)
              Spacer(modifier = Modifier.height(16.dp))
              Button(onClick = { launcher.launch(permissionsToRequest) }) {
                Text("Grant Permissions")
              }
            }
          }
        }
      }
    }
  }
}
