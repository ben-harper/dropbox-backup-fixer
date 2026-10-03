package com.example.dropboxbackupfixer

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey
@Serializable data object FolderSelection : NavKey  
@Serializable data object ScanProgress : NavKey
@Serializable data object CatalogueSummary : NavKey
