package com.example.dropboxbackupfixer.data.local

import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MediaFolder(
    val path: String,
    val name: String,
    val mediaCount: Int,
    val isRecommended: Boolean
)

object MediaScanner {

    private val supportedExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif",
        "mp4", "mov", "avi", "mkv", "3gp"
    )
    
    private val videoExtensions = setOf(
        "mp4", "mov", "avi", "mkv", "3gp"
    )

    private val recommendedPaths = listOf(
        "DCIM/Camera",
        "DCIM/Screenshots",
        "Pictures"
    )

    fun discoverMediaFolders(context: Context): List<MediaFolder> {
        val folders = mutableMapOf<String, Int>()

        val projection = arrayOf(
            MediaStore.MediaColumns.DATA
        )

        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )

        for (uri in uris) {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIndex)
                    if (path != null) {
                        val file = File(path)
                        val parent = file.parent
                        if (parent != null) {
                            folders[parent] = folders.getOrDefault(parent, 0) + 1
                        }
                    }
                }
            }
        }

        return folders.map { (path, count) ->
            val dir = File(path)
            val displayName = dir.name
            val isRecommended = recommendedPaths.any { path.endsWith(it, ignoreCase = true) }
            MediaFolder(path, displayName, count, isRecommended)
        }.sortedByDescending { it.mediaCount }
    }

    fun scanFiles(
        context: Context,
        folderPaths: List<String>,
        onProgress: (count: Int, currentFolder: String) -> Unit
    ): List<MediaFileEntity> {
        val result = mutableListOf<MediaFileEntity>()
        var found = 0

        val dateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (folderPath in folderPaths) {
            val folderFile = File(folderPath)
            if (!folderFile.exists() || !folderFile.isDirectory) continue

            folderFile.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val extension = file.extension.lowercase(Locale.US)
                    if (extension in supportedExtensions) {
                        var dateTaken: Long? = null
                        var width: Int? = null
                        var height: Int? = null

                        if (extension !in videoExtensions) {
                            try {
                                val exif = ExifInterface(file.absolutePath)
                                val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                if (dateTime != null) {
                                    try {
                                        val date = dateFormat.parse(dateTime)
                                        dateTaken = date?.time
                                    } catch (_: Exception) {
                                        // Ignore parse error
                                    }
                                }
                                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                            } catch (_: Exception) {
                                // Ignore exif read error
                            }
                        }

                        val dateModified = file.lastModified()
                        val timeToUseForYear = dateTaken ?: dateModified
                        val year = SimpleDateFormat("yyyy", Locale.US).format(Date(timeToUseForYear)).toIntOrNull()

                        val isVideo = extension in videoExtensions
                        val mimeType = if (isVideo) "video/$extension" else "image/$extension"

                        val entity = MediaFileEntity(
                            filePath = file.absolutePath,
                            fileName = file.name,
                            fileExtension = extension,
                            fileSizeBytes = file.length(),
                            dateTaken = dateTaken,
                            dateModified = dateModified,
                            year = year,
                            folder = file.parentFile?.name ?: "",
                            folderPath = file.parent ?: "",
                            width = width,
                            height = height,
                            contentHash = null,
                            hashComputedAt = null,
                            backupStatus = "UNKNOWN",
                            dropboxPath = null,
                            mimeType = mimeType,
                            isVideo = isVideo
                        )
                        result.add(entity)
                        found++
                        if (found % 50 == 0) {
                            onProgress(found, folderFile.name)
                        }
                    }
                }
            }
        }
        onProgress(found, "Done")
        return result
    }
}
