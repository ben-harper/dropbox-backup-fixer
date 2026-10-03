package com.example.dropboxbackupfixer.ui.folders

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.navigation3.runtime.NavKey
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dropboxbackupfixer.ScanConfig
import com.example.dropboxbackupfixer.ScanProgress
import com.example.dropboxbackupfixer.data.local.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderItem(
    val path: String,
    val displayName: String,
    val fileCount: Int,
    val isRecommended: Boolean,
    val isSelected: Boolean
)

sealed interface FolderSelectionUiState {
    data object Loading : FolderSelectionUiState
    data class Ready(val folders: List<FolderItem>, val selectedCount: Int) : FolderSelectionUiState
    data class Error(val message: String) : FolderSelectionUiState
}

class FolderSelectionViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<FolderSelectionUiState>(FolderSelectionUiState.Loading)
    val uiState: StateFlow<FolderSelectionUiState> = _uiState.asStateFlow()

    init {
        loadFolders()
    }

    private fun loadFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { FolderSelectionUiState.Loading }
                val folders = MediaScanner.discoverMediaFolders(getApplication())
                val items = folders.map { folder ->
                    FolderItem(
                        path = folder.path,
                        displayName = folder.name,
                        fileCount = folder.mediaCount,
                        isRecommended = folder.isRecommended,
                        isSelected = folder.isRecommended
                    )
                }
                _uiState.update { 
                    FolderSelectionUiState.Ready(items, items.count { it.isSelected })
                }
            } catch (e: Exception) {
                _uiState.update { FolderSelectionUiState.Error(e.message ?: "Unknown error occurred") }
            }
        }
    }

    fun toggleFolder(path: String) {
        _uiState.update { state ->
            if (state is FolderSelectionUiState.Ready) {
                val updatedFolders = state.folders.map { 
                    if (it.path == path) it.copy(isSelected = !it.isSelected) else it 
                }
                FolderSelectionUiState.Ready(updatedFolders, updatedFolders.count { it.isSelected })
            } else state
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            if (state is FolderSelectionUiState.Ready) {
                val updatedFolders = state.folders.map { it.copy(isSelected = true) }
                FolderSelectionUiState.Ready(updatedFolders, updatedFolders.size)
            } else state
        }
    }

    fun deselectAll() {
        _uiState.update { state ->
            if (state is FolderSelectionUiState.Ready) {
                val updatedFolders = state.folders.map { it.copy(isSelected = false) }
                FolderSelectionUiState.Ready(updatedFolders, 0)
            } else state
        }
    }
    
    fun getSelectedPaths(): List<String> {
        val state = _uiState.value
        return if (state is FolderSelectionUiState.Ready) {
            state.folders.filter { it.isSelected }.map { it.path }
        } else emptyList()
    }
}

class FolderSelectionViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FolderSelectionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FolderSelectionViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun FolderSelectionScreen(onNavigate: (NavKey) -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: FolderSelectionViewModel = viewModel(
        factory = FolderSelectionViewModelFactory(application)
    )
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Select folders to scan",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp, top = 24.dp)
        )
        Text(
            text = "We found these folders containing photos and videos on your device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        when (val state = uiState) {
            is FolderSelectionUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is FolderSelectionUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                }
            }
            is FolderSelectionUiState.Ready -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.selectedCount} selected",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text("Select All")
                        }
                        TextButton(onClick = { viewModel.deselectAll() }) {
                            Text("Deselect All")
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.folders) { folder ->
                        FolderListItem(
                            folder = folder,
                            onToggle = { viewModel.toggleFolder(folder.path) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        ScanConfig.selectedFolderPaths = viewModel.getSelectedPaths()
                        onNavigate(ScanProgress)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = state.selectedCount > 0
                ) {
                    Text("Start Scan", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FolderListItem(folder: FolderItem, onToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (folder.isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = folder.isSelected,
                onCheckedChange = { onToggle() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Folder Icon",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = folder.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = folder.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                Text(text = folder.fileCount.toString(), color = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.padding(4.dp))
            }
        }
    }
}
