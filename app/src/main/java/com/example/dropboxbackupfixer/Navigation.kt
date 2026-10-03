package com.example.dropboxbackupfixer

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.dropboxbackupfixer.ui.folders.FolderSelectionScreen
import com.example.dropboxbackupfixer.ui.home.HomeScreen
import com.example.dropboxbackupfixer.ui.scan.ScanProgressScreen
import com.example.dropboxbackupfixer.ui.summary.CatalogueSummaryScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Home)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Home> {
          HomeScreen(onNavigate = { navKey -> backStack.add(navKey) })
        }
        entry<FolderSelection> {
          FolderSelectionScreen(onNavigate = { navKey -> backStack.add(navKey) })
        }
        entry<ScanProgress> {
          ScanProgressScreen(
            onNavigate = { navKey -> backStack.add(navKey) },
            onBack = { backStack.removeLastOrNull() }
          )
        }
        entry<CatalogueSummary> {
          CatalogueSummaryScreen(
            onRestart = {
                // Return to FolderSelection by removing CatalogueSummary and ScanProgress
                backStack.removeLastOrNull()
                backStack.removeLastOrNull()
            }
          )
        }
      },
  )
}
