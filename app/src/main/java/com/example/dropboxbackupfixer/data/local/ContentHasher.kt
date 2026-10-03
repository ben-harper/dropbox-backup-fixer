package com.example.dropboxbackupfixer.data.local

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object ContentHasher {
    private const val BLOCK_SIZE = 4 * 1024 * 1024 // 4MB

    fun computeHash(
        file: File,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        return FileInputStream(file).use {
            computeHash(it, file.length(), onProgress)
        }
    }

    fun computeHash(
        inputStream: InputStream,
        totalBytes: Long = -1L,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        val blockDigests = mutableListOf<ByteArray>()
        var currentBlockDigest = MessageDigest.getInstance("SHA-256")
        
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var bytesInCurrentBlock = 0
        var totalBytesRead = 0L

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            var offset = 0
            while (offset < bytesRead) {
                val bytesToHash = Math.min(bytesRead - offset, BLOCK_SIZE - bytesInCurrentBlock)
                currentBlockDigest.update(buffer, offset, bytesToHash)
                
                bytesInCurrentBlock += bytesToHash
                offset += bytesToHash
                
                if (bytesInCurrentBlock == BLOCK_SIZE) {
                    blockDigests.add(currentBlockDigest.digest())
                    currentBlockDigest = MessageDigest.getInstance("SHA-256")
                    bytesInCurrentBlock = 0
                }
            }
            
            totalBytesRead += bytesRead
            onProgress?.invoke(totalBytesRead, totalBytes)
        }
        
        if (bytesInCurrentBlock > 0) {
            blockDigests.add(currentBlockDigest.digest())
        }

        val finalDigest = MessageDigest.getInstance("SHA-256")
        if (blockDigests.isEmpty()) {
            // For empty files, just hash empty bytes
        } else {
            for (digest in blockDigests) {
                finalDigest.update(digest)
            }
        }

        val result = finalDigest.digest()
        return result.joinToString("") { "%02x".format(it) }
    }
}
