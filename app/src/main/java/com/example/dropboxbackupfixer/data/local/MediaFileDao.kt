package com.example.dropboxbackupfixer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class TypeSummary(val extension: String, val count: Int, val totalSizeBytes: Long)
data class YearSummary(val year: Int, val count: Int, val totalSizeBytes: Long)  
data class FolderSummary(val folder: String, val count: Int, val totalSizeBytes: Long)
data class OverallSummary(val totalFiles: Int, val totalSizeBytes: Long, val photoCount: Int, val videoCount: Int)

@Dao
interface MediaFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(files: List<MediaFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<MediaFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(file: MediaFileEntity)

    @Query("SELECT * FROM media_files")
    suspend fun getAllFiles(): List<MediaFileEntity>

    @Query("SELECT * FROM media_files ORDER BY filePath ASC")
    fun getAllFilesFlow(): Flow<List<MediaFileEntity>>

    @Query("SELECT COUNT(*) FROM media_files")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM media_files WHERE backupStatus = :status")
    suspend fun getCountByStatus(status: String): Int

    @Query("SELECT * FROM media_files WHERE backupStatus = :status")
    suspend fun getFilesByStatus(status: String): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE contentHash IS NULL")
    suspend fun getFilesWithoutHash(): List<MediaFileEntity>

    @Query("SELECT fileExtension AS extension, COUNT(*) AS count, SUM(fileSizeBytes) AS totalSizeBytes FROM media_files GROUP BY fileExtension")
    suspend fun getSummaryByExtension(): List<TypeSummary>

    @Query("SELECT IFNULL(year, 0) AS year, COUNT(*) AS count, SUM(fileSizeBytes) AS totalSizeBytes FROM media_files GROUP BY IFNULL(year, 0)")
    suspend fun getSummaryByYear(): List<YearSummary>

    @Query("SELECT folder, COUNT(*) AS count, SUM(fileSizeBytes) AS totalSizeBytes FROM media_files GROUP BY folder")
    suspend fun getSummaryByFolder(): List<FolderSummary>

    @Query("UPDATE media_files SET contentHash = :hash, hashComputedAt = :timestamp WHERE filePath = :path")
    suspend fun updateContentHash(path: String, hash: String, timestamp: Long)

    @Query("UPDATE media_files SET backupStatus = :status, dropboxPath = :dropboxPath WHERE filePath = :path")
    suspend fun updateBackupStatus(path: String, status: String, dropboxPath: String?)

    @Query("DELETE FROM media_files")
    suspend fun deleteAll()

    @Query("SELECT * FROM media_files WHERE filePath = :path LIMIT 1")
    suspend fun getFileByPath(path: String): MediaFileEntity?

    @Query("""
        SELECT 
            COUNT(*) as totalFiles, 
            SUM(fileSizeBytes) as totalSizeBytes,
            SUM(CASE WHEN isVideo = 0 THEN 1 ELSE 0 END) as photoCount,
            SUM(CASE WHEN isVideo = 1 THEN 1 ELSE 0 END) as videoCount
        FROM media_files
    """)
    suspend fun getOverallSummary(): OverallSummary
}
