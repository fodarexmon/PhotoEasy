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
    private var pendingLegacyExport: Pair<Boolean, Int>? = null
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingLegacyExport
        pendingLegacyExport = null
        if (granted && pending != null) {
            viewModel.exportImage(pending.first, pending.second)
        } else if (!granted && pending != null) {
            viewModel.notifyStoragePermissionDenied()
        }
    }
    private fun exportImageSafely(isPng: Boolean, quality: Int) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingLegacyExport = isPng to quality; storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else viewModel.exportImage(isPng, quality)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            val layoutDirection = when (uiState.currentLanguage) {
                "ar" -> LayoutDirection.Rtl
                "en" -> LayoutDirection.Ltr
                else -> if (Locale.getDefault().language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                PhotoEasyTheme {
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
                                onExportConfirm = { isPng, quality -> exportImageSafely(isPng, quality) }
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
