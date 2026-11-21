package com.example

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.example.data.model.SelectionToolType
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.PhotoEasyTheme
import com.example.ui.viewmodel.ActiveTool
import com.example.ui.viewmodel.EditorUiState
import com.example.ui.viewmodel.EditorViewModel
import com.example.ui.viewmodel.Screen
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: EditorViewModel by viewModels()
    private var pendingLegacyExport: com.example.engine.ExportSettings? = null
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingLegacyExport
        pendingLegacyExport = null
        if (granted && pending != null) {
            viewModel.exportImage(pending)
        } else if (!granted && pending != null) {
            viewModel.notifyStoragePermissionDenied()
        }
    }
    /** Gallery export; Android 9 and older first need the storage permission. */
    private fun exportImageSafely(settings: com.example.engine.ExportSettings) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingLegacyExport = settings; storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else viewModel.exportImage(settings)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (home, app switch, screen off): write the open project right away.
        viewModel.saveForBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.util.CrashReporter.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crashReport = androidx.compose.runtime.mutableStateOf(com.example.util.CrashReporter.lastReport(this))

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            val layoutDirection = when (uiState.currentLanguage) {
                "ar" -> LayoutDirection.Rtl
                "en" -> LayoutDirection.Ltr
                else -> if (Locale.getDefault().language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                PhotoEasyTheme {
                    crashReport.value?.let { report ->
                        CrashReportDialog(report) {
                            com.example.util.CrashReporter.clear(this)
                            crashReport.value = null
                        }
                    }
                    when (uiState.currentScreen) {
                        Screen.HOME -> {
                            HomeScreen(
                                projects = uiState.projects,
                                isProcessing = uiState.isProcessing,
                                statusMessage = uiState.statusMessage,
                                onClearStatusMessage = { viewModel.clearStatusMessage() },
                                recoveryProjectId = uiState.recoveryProjectId,
                                onOpenProject = { viewModel.openProject(it) },
                                onRestoreSession = { viewModel.restoreRecoverySession() },
                                onDismissRecoverySession = { viewModel.dismissRecoverySession() },
                                onCreateNewCanvas = { name, w, h, bg ->
                                    viewModel.createNewProject(name, w, h, bg)
                                },
                                onImportImageUri = { uri ->
                                    viewModel.importImageAsNewProject(uri)
                                },
                                onOpenSampleProject = { viewModel.openSampleProject() },
                                onDeleteProject = { viewModel.deleteProject(it) },
                                onRenameProject = { id, name -> viewModel.renameProject(id, name) },
                                onOpenSettings = { viewModel.navigateTo(Screen.SETTINGS) }
                            )
                        }

                        Screen.EDITOR -> {
                            EditorScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                onExportConfirm = { settings -> exportImageSafely(settings) }
                            )
                        }

                        Screen.SETTINGS -> {
                            // System back returns to the project list instead of closing the app.
                            BackHandler { viewModel.navigateTo(Screen.HOME) }
                            SettingsScreen(
                                currentLanguage = uiState.currentLanguage,
                                onLanguageChange = { viewModel.setLanguage(it) },
                                onBackClick = { viewModel.navigateTo(Screen.HOME) }
                            )
                        }
                    }
                }
            }
        }
    }
}


/** Shown once after a crash: the report can be shared (to send to the developer) or dismissed. */
@Composable
private fun CrashReportDialog(report: String, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.crash_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.crash_message))
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                androidx.compose.material3.Text(
                    report.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") } ?: "",
                    fontSize = 11.sp, maxLines = 3
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "PhotoEasy crash report")
                    putExtra(android.content.Intent.EXTRA_TEXT, report)
                }
                runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
                onDone()
            }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.crash_share)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDone) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.crash_dismiss))
            }
        }
    )
}
