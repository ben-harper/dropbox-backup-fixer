package com.example.dropboxbackupfixer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_files")
data class MediaFileEntity(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val fileExtension: String,  // lowercase, e.g. "jpg", "png", "mp4"
    val fileSizeBytes: Long,
    val dateTaken: Long?,       // epoch millis from EXIF DateTimeOriginal
    val dateModified: Long,     // file system last modified
    val year: Int?,             // extracted from dateTaken or dateModified
    val folder: String,         // parent folder name, e.g. "Camera", "Screenshots"
    val folderPath: String,     // full parent folder path
    val width: Int?,            // from EXIF
    val height: Int?,           // from EXIF
    val contentHash: String?,   // Dropbox content_hash, null if not yet computed
    val hashComputedAt: Long?,  // when hash was last computed
    val backupStatus: String = "UNKNOWN",  // UNKNOWN, BACKED_UP, MISSING, PROBABLE_MATCH
    val dropboxPath: String? = null,
    val mimeType: String?,      // e.g. "image/jpeg"
    val isVideo: Boolean = false
)
