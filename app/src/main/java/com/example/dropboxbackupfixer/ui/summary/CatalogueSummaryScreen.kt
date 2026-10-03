package com.example.dropboxbackupfixer.ui.summary

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dropboxbackupfixer.data.export.ExcelReportGenerator
import com.example.dropboxbackupfixer.data.local.AppDatabase
import com.example.dropboxbackupfixer.data.local.FolderSummary
import com.example.dropboxbackupfixer.data.local.TypeSummary
import com.example.dropboxbackupfixer.data.local.YearSummary
import com.example.dropboxbackupfixer.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CatalogueSummaryUiState(
    val isLoading: Boolean = true,
    val totalFiles: Int = 0,
    val totalSizeBytes: Long = 0,
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val oldestDate: String? = null,
    val newestDate: String? = null,
    val byType: List<TypeSummary> = emptyList(),
    val byYear: List<YearSummary> = emptyList(),
    val byFolder: List<FolderSummary> = emptyList(),
    val reportGenerating: Boolean = false,
    val reportFile: File? = null,
    val error: String? = null
)

class CatalogueSummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(CatalogueSummaryUiState())
    val uiState: StateFlow<CatalogueSummaryUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getInstance(application)
    private val mediaFileDao = db.mediaFileDao()

    init {
        loadSummary()
    }

    private fun loadSummary() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = mediaFileDao.getAllFiles()
                
                if (files.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, error = "No files found") }
                    return@launch
                }
                
                val photoCount = files.count { !it.isVideo }
                val videoCount = files.count { it.isVideo }
                val totalSize = files.sumOf { it.fileSizeBytes }
                
                val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                val dates = files.map { it.dateModified }.filter { it > 0 }
                val oldestDate = if (dates.isNotEmpty()) dateFormat.format(Date(dates.min())) else null
                val newestDate = if (dates.isNotEmpty()) dateFormat.format(Date(dates.max())) else null
                
                // Use DAO aggregate queries
                val byType = mediaFileDao.getSummaryByExtension()
                val byYear = mediaFileDao.getSummaryByYear()
                val byFolder = mediaFileDao.getSummaryByFolder()

                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        totalFiles = files.size,
                        totalSizeBytes = totalSize,
                        photoCount = photoCount,
                        videoCount = videoCount,
                        oldestDate = oldestDate,
                        newestDate = newestDate,
                        byType = byType,
                        byYear = byYear,
                        byFolder = byFolder
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Error loading summary") }
            }
        }
    }

    fun generateReport() {
        val state = _uiState.value
        if (state.isLoading || state.reportGenerating) return
        
        _uiState.update { it.copy(reportGenerating = true, error = null) }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = mediaFileDao.getAllFiles()
                val generator = ExcelReportGenerator()
                val reportFile = generator.generateReport(
                    context = getApplication(),
                    files = files,
                    summaryByType = state.byType,
                    summaryByYear = state.byYear,
                    summaryByFolder = state.byFolder
                )
                _uiState.update { it.copy(reportGenerating = false, reportFile = reportFile) }
            } catch (e: Exception) {
                _uiState.update { it.copy(reportGenerating = false, error = e.message ?: "Failed to generate report") }
            }
        }
    }
}

class CatalogueSummaryViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CatalogueSummaryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CatalogueSummaryViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueSummaryScreen(onRestart: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: CatalogueSummaryViewModel = viewModel(
        key = com.example.dropboxbackupfixer.ScanConfig.scanId,
        factory = CatalogueSummaryViewModelFactory(application)
    )
    val uiState by viewModel.uiState.collectAsState()

    // Handle report file sharing
    LaunchedEffect(uiState.reportFile) {
        uiState.reportFile?.let { file ->
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(Intent.createChooser(intent, "Open Report"))
            } catch (_: Exception) {
                // No app to handle xlsx — just show success
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your Photo Library", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onRestart) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back to start")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.error != null && uiState.totalFiles == 0) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(uiState.error ?: "Unknown error", color = MaterialTheme.colorScheme.error)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Catalogue complete!",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = "${uiState.totalFiles} Files",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = formatFileSize(uiState.totalSizeBytes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Image, contentDescription = "Photos", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${uiState.photoCount} Photos", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VideoFile, contentDescription = "Videos", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${uiState.videoCount} Videos", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }

                            if (uiState.oldestDate != null && uiState.newestDate != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "From ${uiState.oldestDate} to ${uiState.newestDate}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                // By File Type
                item {
                    Text("By File Type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                items(uiState.byType) { item ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(".${item.extension}")
                        Text("${item.count} (${formatFileSize(item.totalSizeBytes)})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // By Year
                item {
                    Text("By Year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                items(uiState.byYear) { item ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (item.year == 0) "Unknown" else item.year.toString())
                        Text("${item.count} (${formatFileSize(item.totalSizeBytes)})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // By Folder
                item {
                    Text("By Folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                items(uiState.byFolder) { item ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.folder)
                        Text("${item.count} (${formatFileSize(item.totalSizeBytes)})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Action buttons
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { viewModel.generateReport() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = !uiState.reportGenerating
                    ) {
                        if (uiState.reportGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generating...")
                        } else {
                            Text("Generate Excel Report", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    var hasToken by remember { mutableStateOf(com.example.dropboxbackupfixer.data.remote.DropboxAuthManager.hasToken(context)) }
                    
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                hasToken = com.example.dropboxbackupfixer.data.remote.DropboxAuthManager.hasToken(context)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    OutlinedButton(
                        onClick = { 
                            if (!hasToken) {
                                com.example.dropboxbackupfixer.data.remote.DropboxAuthManager.startAuth(context)
                            } else {
                                // TODO: Navigate to VerifyScreen
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(if (hasToken) "Verify Dropbox Backup" else "Connect to Dropbox", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
