package com.example.dropboxbackupfixer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dropbox.core.util.IOUtil
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.CommitInfo
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.UploadSessionCursor
import com.dropbox.core.v2.files.WriteMode
import com.example.dropboxbackupfixer.MainActivity
import com.example.dropboxbackupfixer.ScanConfig
import com.example.dropboxbackupfixer.data.local.AppDatabase
import com.example.dropboxbackupfixer.data.local.DropboxIndexStore
import com.example.dropboxbackupfixer.data.remote.DropboxAuthManager
import com.example.dropboxbackupfixer.util.formatFileSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext

sealed interface UploadUiState {
    data object Idle : UploadUiState
    data object Preparing : UploadUiState
    data class Uploading(
        val fileIndex: Int,
        val totalFiles: Int,
        val currentFileName: String,
        val bytesUploaded: Long,
        val currentFileSize: Long,
        val overallBytesUploaded: Long,
        val overallTotalBytes: Long
    ) : UploadUiState
    data class Complete(val successCount: Int, val errorCount: Int, val note: String? = null) : UploadUiState
    data class Error(val message: String) : UploadUiState
}

/** App-wide upload state. Lives outside any screen so the UI can reattach after leaving the app. */
object UploadManager {
    private val _state = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val state: StateFlow<UploadUiState> = _state.asStateFlow()

    val isActive: Boolean
        get() = _state.value is UploadUiState.Preparing || _state.value is UploadUiState.Uploading

    internal fun set(newState: UploadUiState) { _state.value = newState }

    /** Clears a finished result so the next visit starts clean. Never interrupts a running upload. */
    fun acknowledgeResult() { if (!isActive) _state.value = UploadUiState.Idle }
}

private const val DROPBOX_ROOT = "/Camera Uploads"

/**
 * Decides where a missing photo goes in Dropbox:
 *  - Default camera roll (DCIM or DCIM/Camera) -> /Camera Uploads/<year>/<file>
 *  - Any other folder (album)                  -> /Camera Uploads/<folder name>/<file>
 * Dropbox auto-creates missing folders and reuses existing ones.
 */
internal fun buildDropboxPath(folderPath: String, year: Int?, fileName: String): String {
    val normalized = folderPath.replace('\\', '/').trimEnd('/')
    val isCameraRoll = normalized.endsWith("/DCIM", ignoreCase = true) ||
        normalized.endsWith("/DCIM/Camera", ignoreCase = true)

    val subfolder = if (isCameraRoll) {
        year?.takeIf { it > 1900 }?.toString() ?: "Unknown Year"
    } else {
        sanitizeFolderName(normalized.substringAfterLast('/'))
    }
    return "$DROPBOX_ROOT/$subfolder/$fileName"
}

private fun sanitizeFolderName(name: String): String {
    // Dropbox rejects names ending in dots/spaces and a few reserved characters
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')
    return cleaned.ifEmpty { "Other" }
}

/**
 * Foreground service that performs the upload so it keeps running when the user switches apps
 * or the screen turns off. Holds a partial wake lock + Wi-Fi lock while active.
 */
class UploadService : Service() {

    companion object {
        private const val ACTION_START = "com.example.dropboxbackupfixer.action.START_UPLOAD"
        private const val ACTION_CANCEL = "com.example.dropboxbackupfixer.action.CANCEL_UPLOAD"
        private const val CHANNEL_ID = "uploads"
        private const val NOTIFICATION_ID = 1001
        private const val DONE_NOTIFICATION_ID = 1002

        // Dropbox simple upload is capped at 150 MB; larger files use a chunked upload session.
        private const val SIMPLE_UPLOAD_LIMIT = 140L * 1024 * 1024
        private const val CHUNK_SIZE = 8L * 1024 * 1024
        private const val MAX_ATTEMPTS = 3

        fun start(context: Context) {
            UploadManager.set(UploadUiState.Preparing)
            val intent = Intent(context, UploadService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, UploadService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startInForeground(buildProgressNotification("Preparing upload…", 0, indeterminate = true))

        if (job?.isActive == true) return START_NOT_STICKY // already uploading
        timedOut = false
        acquireLocks()

        // Mutex guarantees a previous (cancelled) run has fully stopped before a new one begins.
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            uploadMutex.withLock { runUpload() }
        }
        job = newJob
        newJob.invokeOnCompletion {
            scope.launch(Dispatchers.Main + NonCancellable) {
                if (job === newJob) finishService()
            }
        }
        newJob.start()
        return START_NOT_STICKY
    }

    // Android 15+: dataSync foreground services are limited to ~6 hours per day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private suspend fun runUpload() {
        val ctx = coroutineContext
        val app = applicationContext
        var successCount = 0
        var errorCount = 0

        try {
            UploadManager.set(UploadUiState.Preparing)
            val client = DropboxAuthManager.getClient(app)
            if (client == null) {
                UploadManager.set(UploadUiState.Error("Not connected to Dropbox"))
                return
            }

            val dao = AppDatabase.getInstance(app).mediaFileDao()
            val missingFiles = dao.getFilesByStatus("MISSING")
            if (missingFiles.isEmpty()) {
                UploadManager.set(UploadUiState.Complete(0, 0))
                return
            }

            val totalFiles = missingFiles.size
            val overallTotalBytes = missingFiles.sumOf { it.fileSizeBytes }
            var overallDone = 0L
            var lastNotify = 0L

            for ((index, entity) in missingFiles.withIndex()) {
                ctx.ensureActive()

                val file = File(entity.filePath)
                if (!file.exists() || !file.canRead()) {
                    errorCount++
                    overallDone += entity.fileSizeBytes
                    continue
                }

                val publish: (Long) -> Unit = { fileBytes ->
                    ctx.ensureActive() // aborts an in-flight transfer promptly when cancelled
                    UploadManager.set(
                        UploadUiState.Uploading(
                            fileIndex = index + 1,
                            totalFiles = totalFiles,
                            currentFileName = entity.fileName,
                            bytesUploaded = fileBytes,
                            currentFileSize = entity.fileSizeBytes,
                            overallBytesUploaded = overallDone + fileBytes,
                            overallTotalBytes = overallTotalBytes
                        )
                    )
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify > 1000) { // notifications are rate-limited by the OS
                        lastNotify = now
                        val pct = if (overallTotalBytes > 0) ((overallDone + fileBytes) * 100 / overallTotalBytes).toInt() else 0
                        notifyProgress("File ${index + 1} of $totalFiles • ${formatFileSize(overallDone + fileBytes)} / ${formatFileSize(overallTotalBytes)}", pct)
                    }
                }
                publish(0)

                val dbxPath = buildDropboxPath(entity.folderPath, entity.year, entity.fileName)
                var uploaded: FileMetadata? = null
                for (attempt in 1..MAX_ATTEMPTS) {
                    try {
                        uploaded = uploadFile(client, file, dbxPath, publish)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ctx.ensureActive()
                        e.printStackTrace()
                        if (attempt < MAX_ATTEMPTS) delay(5_000L * attempt) // ride out brief network drops
                    }
                }

                if (uploaded != null) {
                    dao.updateBackupStatus(entity.filePath, "BACKED_UP", uploaded.pathDisplay)
                    uploaded.contentHash?.let { DropboxIndexStore.append(app, it, uploaded.pathDisplay ?: dbxPath) }
                    ScanConfig.uploadPerformedThisSession = true
                    successCount++
                } else {
                    errorCount++
                }
                overallDone += entity.fileSizeBytes
            }

            UploadManager.set(UploadUiState.Complete(successCount, errorCount))
        } catch (e: CancellationException) {
            val note = if (timedOut) {
                "Android paused the background upload (it limits background transfers to about 6 hours a day). " +
                    "Finished files are saved — open the app and tap Upload again to continue."
            } else {
                "Upload cancelled. Files not yet uploaded are still marked as missing."
            }
            UploadManager.set(UploadUiState.Complete(successCount, errorCount, note))
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            UploadManager.set(UploadUiState.Error(e.message ?: "Error during upload"))
        }
    }

    private fun uploadFile(client: DbxClientV2, file: File, path: String, onBytes: (Long) -> Unit): FileMetadata {
        val size = file.length()
        FileInputStream(file).use { input ->
            if (size <= SIMPLE_UPLOAD_LIMIT) {
                return client.files().uploadBuilder(path)
                    .withMode(WriteMode.ADD)
                    .withAutorename(true)
                    .uploadAndFinish(input, IOUtil.ProgressListener { b -> onBytes(b) })
            }

            // Chunked upload session for large files (typically videos)
            var offset = 0L
            val sessionId = client.files().uploadSessionStart()
                .uploadAndFinish(input, CHUNK_SIZE, IOUtil.ProgressListener { b -> onBytes(offset + b) })
                .sessionId
            offset += CHUNK_SIZE
            while (size - offset > CHUNK_SIZE) {
                client.files().uploadSessionAppendV2(UploadSessionCursor(sessionId, offset))
                    .uploadAndFinish(input, CHUNK_SIZE, IOUtil.ProgressListener { b -> onBytes(offset + b) })
                offset += CHUNK_SIZE
            }
            val commit = CommitInfo.newBuilder(path).withMode(WriteMode.ADD).withAutorename(true).build()
            return client.files().uploadSessionFinish(UploadSessionCursor(sessionId, offset), commit)
                .uploadAndFinish(input, size - offset, IOUtil.ProgressListener { b -> onBytes(offset + b) })
        }
    }

    private fun finishService() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postResultNotification()
        stopSelf()
    }

    // ---- Locks ----

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropboxBackupFixer:upload").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DropboxBackupFixer:upload").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    // ---- Notifications ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Photo uploads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildProgressNotification(text: String, percent: Int, indeterminate: Boolean = false): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, UploadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up photos to Dropbox")
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyProgress(text: String, percent: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildProgressNotification(text, percent))
    }

    private fun postResultNotification() {
        val text = when (val s = UploadManager.state.value) {
            is UploadUiState.Complete -> s.note ?: buildString {
                append("${s.successCount} uploaded")
                if (s.errorCount > 0) append(", ${s.errorCount} failed")
            }
            is UploadUiState.Error -> s.message
            else -> return
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Dropbox upload finished")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, n)
    }
}
