package com.example.dropboxbackupfixer

import java.util.UUID

object ScanConfig {
    var selectedFolderPaths: List<String> = emptyList()
    var scanId: String = UUID.randomUUID().toString()
}
