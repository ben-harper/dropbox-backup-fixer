package com.example.dropboxbackupfixer

import java.util.UUID

object ScanConfig {
    var selectedFolderPaths: List<String> = emptyList()
    var scanId: String = UUID.randomUUID().toString()

    // Regenerated each time the Verify / Upload screens are opened so they always get a fresh ViewModel
    var verifyRunId: String = UUID.randomUUID().toString()
    var uploadRunId: String = UUID.randomUUID().toString()

    // In-memory only: resets when the app process restarts
    var uploadPerformedThisSession: Boolean = false

    // Set from the Verify dialog: true = reuse the saved Dropbox index instead of re-listing Dropbox
    var reuseDropboxIndex: Boolean = false

    fun newVerifyRun() { verifyRunId = UUID.randomUUID().toString() }
    fun newUploadRun() { uploadRunId = UUID.randomUUID().toString() }
}
