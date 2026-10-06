@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.futurethinking.aivideodirector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futurethinking.aivideodirector.data.Project

class MainActivity : ComponentActivity() {
    private val vm by viewModels<com.futurethinking.aivideodirector.ui.MainViewModel>()
    private val audio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importAudio) }
    private val pdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importPdf) }
    private val ts = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importTimestampPdf) }
    private val vid = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importMergeVideo(it) { path -> vm.addMergeItem("video:" + path) } }
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) vm.showBackgroundPermissionNotice()
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            EditorApp(vm, audio, pdf, ts, vid) { project ->
                vm.shareOutput(project)?.let { startActivity(Intent.createChooser(it, "Export video")) }
            }
        }
    }
}

@Composable
fun EditorApp(
    vm: com.futurethinking.aivideodirector.ui.MainViewModel,
    audio: ActivityResultLauncher<Array<String>>,
    pdf: ActivityResultLauncher<Array<String>>,
    ts: ActivityResultLauncher<Array<String>>,
    vid: ActivityResultLauncher<Array<String>>,
    onExport: (Project) -> Unit
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf("projects") }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (screen == "projects") "Editor and Merger" else if (screen == "merge") "Merge Videos" else current?.title ?: "Project") },
                    navigationIcon = {
                        if (screen != "projects") {
                            IconButton(onClick = { screen = "projects" }) { Icon(Icons.Default.ArrowBack, null) }
                        }
                    },
                    actions = {
                        if (screen == "projects") {
                            Button(onClick = { vm.createProject() }) { Text("New") }
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { screen = "merge" }) { Text("Merge") }
                        }
                    }
                )
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    if (!error.isNullOrBlank()) {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Problem", style = MaterialTheme.typography.titleMedium)
                                Text(error!!)
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                when (screen) {
                    "projects" -> ProjectList(projects.filterNot { it.isMerged && it.state == "IMPORT" }, vm) {
                        vm.selectProject(it)
                        screen = "editor"
                    }
                    "merge" -> MergeScreen(projects.filter { !it.isMerged && it.state == "READY" }, vm) {
                        vid.launch(arrayOf("video/*"))
                    }
                    else -> EditorScreen(
                        current, vm,
                        { audio.launch(arrayOf("audio/*")) },
                        { pdf.launch(arrayOf("application/pdf")) },
                        { ts.launch(arrayOf("application/pdf")) },
                        onExport
                    )
                }
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectList(projects: List<Project>, vm: com.futurethinking.aivideodirector.ui.MainViewModel, onOpen: (Project) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Up to 10 projects can be queued. Rendering runs one heavy media job at a time for stability.") }
        items(projects, key = { it.id }) { project ->
            Card(onClick = { onOpen(project) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (project.isMerged) Icons.Default.MergeType else Icons.Default.Movie, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(project.title)
                            Text(if (project.state == "READY") "Ready • 100%" else project.state + " • " + project.progress + "%")
                            if (project.progressStage.isNotBlank()) Text(project.progressStage, style = MaterialTheme.typography.bodySmall)
                        }
                        if (project.progress in 1..99) CircularProgressIndicator(progress = { project.progress / 100f }, modifier = Modifier.size(30.dp))
                    }
                    if (project.state == "QUEUED" || project.state == "PAUSED") {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { vm.renderNow(project) }) { Text("Render now") }
                            TextButton(onClick = { vm.moveUp(project) }) { Text("↑") }
                            TextButton(onClick = { vm.moveDown(project) }) { Text("↓") }
                            TextButton(onClick = { vm.togglePause(project) }) { Text(if (project.state == "PAUSED") "Resume" else "Pause") }
                            TextButton(onClick = { vm.cancelProject(project) }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditorScreen(
    project: Project?,
    vm: com.futurethinking.aivideodirector.ui.MainViewModel,
    onAudio: () -> Unit,
    onPdf: () -> Unit,
    onTimestamp: () -> Unit,
    onExport: (Project) -> Unit
) {
    if (project == null) return
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(project.title, style = MaterialTheme.typography.headlineSmall) }
        item { Asset("Timestamp Script PDF", project.timestampPdfPath != null, onTimestamp) }
        item { Asset("Audio", project.audioPath != null, onAudio) }
        item { Asset("Panels PDF", project.pdfPath != null, onPdf) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Generate video", style = MaterialTheme.typography.titleLarge)
                    Text("Timestamp PDF controls timing. Yellow separator lines control panel extraction. Original audio remains the timeline.")
                    Button(
                        onClick = { vm.generate() },
                        enabled = project.progress !in 1..99,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (project.progress in 1..99) "Rendering " + project.progress + "%" else "Generate") }
                    if (project.progress > 0) {
                        LinearProgressIndicator(progress = { project.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(project.progress.toString() + "%")
                    }
                    if (project.analysisReport != null) {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("File analysis", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp)); Text(project.analysisReport!!) } }
                    }
                    if (project.outputPath != null && project.state == "READY") {
                        Button(onClick = { onExport(project) }, modifier = Modifier.fillMaxWidth()) { Text("Export video") }
                    }
                    if (project.state == "ERROR") {
                        OutlinedButton(onClick = { vm.retryProject(project) }, modifier = Modifier.fillMaxWidth()) { Text("Retry project") }
                    }
                }
            }
        }
    }
}

@Composable
fun Asset(name: String, ready: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name)
                Text(if (ready) "Ready" else "Add file")
            }
            OutlinedButton(onClick = onClick) { Text(if (ready) "Change" else "Add") }
        }
    }
}

@Composable
fun MergeScreen(
    ready: List<Project>,
    vm: com.futurethinking.aivideodirector.ui.MainViewModel,
    onImport: () -> Unit
) {
    val selected by vm.mergeSelection.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Add videos in exact merge order.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        if (selected.isNotEmpty()) {
            Text("Selected order", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(selected, key = { index, token -> index.toString() + ":" + token }) { index, token ->
                    val label = if (token.startsWith("video:")) "Imported: " + java.io.File(token.removePrefix("video:")).name else ready.firstOrNull { it.id == token }?.title ?: "Project"
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#" + (index + 1), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(10.dp))
                            Text(label, Modifier.weight(1f))
                            TextButton(onClick = { vm.removeMergeItem(token) }) { Text("Remove") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ready, key = { it.id }) { project ->
                val position = selected.indexOf(project.id)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.addMergeItem(project.id) }, enabled = position < 0, modifier = Modifier.weight(1f)) { Text(if (position >= 0) "Selected" else "Add " + project.title) }
                    if (position >= 0) Text(" #" + (position + 1))
                }
            }
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import video from phone") }
        Text("Selected videos: " + selected.size)
        Button(onClick = { vm.merge(emptyList()) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Merge selected videos") }
    }
}
