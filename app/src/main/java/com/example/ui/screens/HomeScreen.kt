package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.rememberAsyncImagePainter
import com.example.R
import com.example.data.model.ProjectSummary
import com.example.data.model.StandardCanvasPresets
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.PinkAccent
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant

import androidx.activity.result.PickVisualMediaRequest

@Composable
fun HomeScreen(
    projects: List<ProjectSummary>,
    isProcessing: Boolean = false,
    statusMessage: String? = null,
    onClearStatusMessage: () -> Unit = {},
    recoveryProjectId: String? = null,
    onOpenProject: (ProjectSummary) -> Unit,
    onRestoreSession: (String) -> Unit = {},
    onDismissRecoverySession: () -> Unit = {},
    onCreateNewCanvas: (String, Int, Int, Int) -> Unit,
    onImportImageUri: (Uri) -> Unit,
    onOpenSampleProject: () -> Unit = {},
    onDeleteProject: (String) -> Unit,
    /** Deletes several projects at once (multi-selection). */
    onDeleteProjects: (Set<String>) -> Unit = { ids -> ids.forEach(onDeleteProject) },
    onRenameProject: (id: String, newName: String) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit
) {
    var pendingRenameProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }
    var pendingDeleteProject by remember { mutableStateOf<ProjectSummary?>(null) }
    // Multi-selection: a long press on a project starts it; then taps select / deselect.
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectionMode by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    // Projects deleted elsewhere drop out of the selection.
    LaunchedEffect(projects) {
        val ids = projects.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(ids)
        if (projects.isEmpty()) selectionMode = false
    }
    androidx.activity.compose.BackHandler(enabled = selectionMode) { selectionMode = false; selectedIds = emptySet() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Results (e.g. "import failed") are shown here; progress text is shown by the overlay below.
    LaunchedEffect(statusMessage, isProcessing) {
        if (statusMessage != null && !isProcessing) {
            snackbarHostState.showSnackbar(statusMessage)
            onClearStatusMessage()
        }
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportImageUri(uri)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportImageUri(uri)
        }
    }

    fun launchPhotoPicker() {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            try {
                getContentLauncher.launch("image/*")
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(listOf(IndigoPrimary, CyanAccent))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("btn_open_settings")) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings), tint = Color.White)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = StudioBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 1000.dp)
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Unsaved Session Recovery Banner
                if (recoveryProjectId != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = StudioSurface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.restore_session_title),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.restore_session_desc),
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { onRestoreSession(recoveryProjectId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(stringResource(R.string.restore_session_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = onDismissRecoverySession) {
                                    // Close (not a trash can): dismissing the banner does not delete the project.
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.Gray)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Quick Actions Header Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = StudioSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { showNewCanvasDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_blank_canvas"),
                                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_blank_canvas), fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { launchPhotoPicker() },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_open_photo"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_open_photo))
                            }
                        }

                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (selectionMode) {
                    // Selection bar: count, select all, delete, cancel.
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("project_selection_bar"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectionMode = false; selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
                        }
                        Text(
                            text = stringResource(R.string.projects_selected, selectedIds.size),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        val all = selectedIds.size == projects.size && projects.isNotEmpty()
                        TextButton(onClick = { selectedIds = if (all) emptySet() else projects.map { it.id }.toSet() }) {
                            Text(stringResource(if (all) R.string.projects_select_none else R.string.projects_select_all), color = IndigoPrimary)
                        }
                        IconButton(
                            onClick = { if (selectedIds.isNotEmpty()) confirmDeleteSelected = true },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.testTag("btn_delete_selected")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = if (selectedIds.isNotEmpty()) Color(0xFFF87171) else Color.Gray)
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.recent_projects),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (projects.isNotEmpty()) {
                            TextButton(onClick = { selectionMode = true }, modifier = Modifier.testTag("btn_select_projects")) {
                                Text(stringResource(R.string.projects_select), color = IndigoPrimary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (projects.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.no_projects_yet),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.no_projects_desc),
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.sample_project_hint),
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onOpenSampleProject,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = IndigoPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.btn_sample_photo), color = IndigoPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 180.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(projects, key = { it.id }) { proj ->
                            val selected = proj.id in selectedIds
                            ProjectCard(
                                project = proj,
                                onClick = {
                                    if (selectionMode) selectedIds = if (selected) selectedIds - proj.id else selectedIds + proj.id
                                    else onOpenProject(proj)
                                },
                                onLongClick = {
                                    if (!selectionMode) { selectionMode = true; selectedIds = setOf(proj.id) }
                                    else selectedIds = if (selected) selectedIds - proj.id else selectedIds + proj.id
                                },
                                selectionMode = selectionMode,
                                selected = selected,
                                onDeleteClick = { pendingDeleteProject = proj },
                                onRenameClick = { pendingRenameProject = proj }
                            )
                        }
                    }
                }
            }

            // Import / open / sample creation run in the background: show progress and block
            // repeated taps that would start the same operation twice.
            if (isProcessing && statusMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = IndigoPrimary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = statusMessage, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    pendingRenameProject?.let { project ->
        RenameProjectDialog(
            initialName = project.name,
            onDismiss = { pendingRenameProject = null },
            onConfirm = { newName ->
                pendingRenameProject = null
                onRenameProject(project.id, newName)
            }
        )
    }

    pendingDeleteProject?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProject = null },
            title = { Text(stringResource(R.string.delete_project_title)) },
            text = { Text(stringResource(R.string.delete_project_message)) },
            confirmButton = {
                Button(
                    onClick = { pendingDeleteProject = null; onDeleteProject(project.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete_project_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProject = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text(stringResource(R.string.delete_projects_title, selectedIds.size)) },
            text = { Text(stringResource(R.string.delete_projects_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedIds
                        confirmDeleteSelected = false
                        selectionMode = false
                        selectedIds = emptySet()
                        onDeleteProjects(ids)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("btn_confirm_delete_selected")
                ) { Text(stringResource(R.string.delete_project_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showNewCanvasDialog) {
        NewCanvasDialog(
            onDismiss = { showNewCanvasDialog = false },
            onCreate = { name, w, h, bg ->
                showNewCanvasDialog = false
                onCreateNewCanvas(name, w, h, bg)
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, IndigoPrimary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("project_card_${project.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .background(StudioSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (project.thumbnailPath != null) {
                    val context = LocalContext.current
                    // The thumbnail file keeps the same path after every save; keying the cache on the
                    // update time makes Coil load the new image instead of a stale cached one.
                    val thumbRequest = remember(project.thumbnailPath, project.updatedAt) {
                        ImageRequest.Builder(context)
                            .data(File(project.thumbnailPath))
                            .memoryCacheKey("${project.thumbnailPath}#${project.updatedAt}")
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build()
                    }
                    Image(
                        painter = rememberAsyncImagePainter(thumbRequest),
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(36.dp)
                    )
                }
                if (selectionMode) {
                    // Check mark in the corner: filled when selected.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .background(if (selected) IndigoPrimary else Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape)
                            .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.cd_selected), tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "${project.width} x ${project.height} px",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }

                if (!selectionMode) Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more), tint = Color.Gray)
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRenameClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = Color.Red) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Shared by the project list and the editor's overflow menu. */
@Composable
fun RenameProjectDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_project_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 80) name = it },
                label = { Text(stringResource(R.string.canvas_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("field_rename_project")
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
            ) { Text(stringResource(R.string.action_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun NewCanvasDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, width: Int, height: Int, bgColorHex: Int) -> Unit
) {
    val defaultProjectName = stringResource(R.string.new_canvas_name_default)
    var name by remember { mutableStateOf("") }
    var widthStr by remember { mutableStateOf("1080") }
    var heightStr by remember { mutableStateOf("1080") }
    var lockAspect by remember { mutableStateOf(true) }
    var aspectRatio by remember { mutableStateOf(1f) }
    var selectedBgHex by remember { mutableIntStateOf(0xFF12131C.toInt()) }

    val wParsed = widthStr.toIntOrNull()
    val hParsed = heightStr.toIntOrNull()
    val validation = com.example.util.CanvasValidation.validate(wParsed, hParsed)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("dialog_new_canvas")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.create_canvas_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.canvas_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Presets Quick Selector
                Text(text = stringResource(R.string.preset_quick_presets), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StandardCanvasPresets.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(StudioSurfaceVariant)
                                .clickable {
                                    widthStr = preset.width.toString()
                                    heightStr = preset.height.toString()
                                    aspectRatio = preset.width.toFloat() / preset.height.toFloat()
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(preset.titleResId),
                                fontSize = 11.sp,
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = widthStr,
                        onValueChange = { newW ->
                            widthStr = newW
                            if (lockAspect) {
                                val wVal = newW.toIntOrNull()
                                if (wVal != null && aspectRatio > 0f) {
                                    heightStr = (wVal / aspectRatio).toInt().toString()
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.width_px)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = {
                        lockAspect = !lockAspect
                        val w = widthStr.toIntOrNull()
                        val h = heightStr.toIntOrNull()
                        if (w != null && h != null && h > 0) {
                            aspectRatio = w.toFloat() / h.toFloat()
                        }
                    }) {
                        Icon(
                            if (lockAspect) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.lock_aspect_ratio),
                            tint = if (lockAspect) IndigoPrimary else Color.Gray
                        )
                    }

                    OutlinedTextField(
                        value = heightStr,
                        onValueChange = { newH ->
                            heightStr = newH
                            if (lockAspect) {
                                val hVal = newH.toIntOrNull()
                                if (hVal != null) {
                                    widthStr = (hVal * aspectRatio).toInt().toString()
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.height_px)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!validation.isValid) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = validation.errorResId?.let { stringResource(it, *validation.errorArgs.toTypedArray()) } ?: stringResource(R.string.canvas_invalid_dimensions),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Background Color Swatches
                Text(text = stringResource(R.string.bg_color), color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val colors = listOf(
                        0xFF12131C.toInt() to stringResource(R.string.bg_black),
                        0xFFFFFFFF.toInt() to stringResource(R.string.bg_white),
                        0x00000000 to stringResource(R.string.bg_transparent)
                    )
                    colors.forEach { (hex, label) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedBgHex == hex) IndigoPrimary else StudioSurfaceVariant)
                                .clickable { selectedBgHex = hex }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = label, fontSize = 12.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (validation.isValid) {
                                onCreate(name.ifBlank { defaultProjectName }, validation.width, validation.height, selectedBgHex)
                            }
                        },
                        enabled = validation.isValid,
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("btn_create_project")
                    ) {
                        Text(stringResource(R.string.action_apply), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
