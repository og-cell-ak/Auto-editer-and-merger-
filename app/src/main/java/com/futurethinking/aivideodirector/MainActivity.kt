@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.futurethinking.aivideodirector

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
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

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            EditorApp(
                vm = vm,
                audio = audio,
                pdf = pdf,
                ts = ts,
                onExport = { project ->
                    vm.shareOutput(project)?.let {
                        startActivity(Intent.createChooser(it, "Export video"))
                    }
                },
                onBackgroundProtection = ::openBackgroundProtection
            )
        }
    }

    private fun openBackgroundProtection() {
        val packageUri = Uri.parse("package:$packageName")
        val batteryRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = packageUri
            }
        } else null

        runCatching {
            if (batteryRequest != null && packageManager.resolveActivity(batteryRequest, 0) != null) {
                startActivity(batteryRequest)
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
    onExport: (Project) -> Unit,
    onBackgroundProtection: () -> Unit
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf("projects") }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (screen == "projects") "Video Renderer" else current?.title ?: "Project")
                    },
                    navigationIcon = {
                        if (screen != "projects") {
                            IconButton(onClick = { screen = "projects" }) {
                                Icon(Icons.Default.ArrowBack, null)
                            }
                        }
                    },
                    actions = {
                        if (screen == "projects") {
                            Button(onClick = { vm.createProject() }) { Text("New") }
                        }
                    }
                )
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    if (!error.isNullOrBlank()) {
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Problem", style = MaterialTheme.typography.titleMedium)
                                Text(error!!)
                            }
                        }
                    }

                    Box(Modifier.weight(1f)) {
                        if (screen == "projects") {
                            ProjectList(
                                projects = projects.filterNot { it.isMerged },
                                vm = vm,
                                onOpen = {
                                    vm.selectProject(it)
                                    screen = "editor"
                                },
                                onBackgroundProtection = onBackgroundProtection
                            )
                        } else {
                            EditorScreen(
                                project = current,
                                vm = vm,
                                onAudio = { audio.launch(arrayOf("audio/*")) },
                                onPdf = { pdf.launch(arrayOf("application/pdf")) },
                                onTimestamp = { ts.launch(arrayOf("application/pdf")) },
                                onExport = onExport
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectList(
    projects: List<Project>,
    vm: com.futurethinking.aivideodirector.ui.MainViewModel,
    onOpen: (Project) -> Unit,
    onBackgroundProtection: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Rendering queue", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Up to 10 projects can be queued. Rendering uses Android background work so the queue can continue after this screen is closed."
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBackgroundProtection,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Protect background rendering")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "For Xiaomi, set this app to unrestricted battery use and do not force-stop it while queued renders are running.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(projects, key = { it.id }) { project ->
            Card(
                onClick = { onOpen(project) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Movie, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(project.title)
                            Text(
                                if (project.state == "READY") "Ready • 100%"
                                else project.state + " • " + project.progress + "%"
                            )
                            if (project.progressStage.isNotBlank()) {
                                Text(
                                    project.progressStage,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (project.progress in 1..99) {
                            CircularProgressIndicator(
                                progress = { project.progress / 100f },
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    if (project.state == "QUEUED" || project.state == "PAUSED") {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { vm.renderNow(project) }) { Text("Render now") }
                            TextButton(onClick = { vm.moveUp(project) }) { Text("↑") }
                            TextButton(onClick = { vm.moveDown(project) }) { Text("↓") }
                            TextButton(onClick = { vm.togglePause(project) }) {
                                Text(if (project.state == "PAUSED") "Resume" else "Pause")
                            }
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

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(project.title, style = MaterialTheme.typography.headlineSmall) }
        item { Asset("Timestamp Script PDF", project.timestampPdfPath != null, onTimestamp) }
        item { Asset("Audio", project.audioPath != null, onAudio) }
        item { Asset("Panels PDF", project.pdfPath != null, onPdf) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Generate video", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Timestamp PDF controls timing. Yellow separator lines control panel extraction. Original audio remains the timeline."
                    )
                    Button(
                        onClick = { vm.generate() },
                        enabled = project.progress !in 1..99,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (project.progress in 1..99)
                                "Rendering " + project.progress + "%"
                            else
                                "Generate"
                        )
                    }
                    if (project.progress > 0) {
                        LinearProgressIndicator(
                            progress = { project.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(project.progress.toString() + "%")
                    }
                    if (project.analysisReport != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    "File analysis",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(project.analysisReport!!)
                            }
                        }
                    }
                    if (project.outputPath != null && project.state == "READY") {
                        Button(
                            onClick = { onExport(project) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Export video")
                        }
                    }
                    if (project.state == "ERROR") {
                        OutlinedButton(
                            onClick = { vm.retryProject(project) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Retry project")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Asset(name: String, ready: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name)
                Text(if (ready) "Ready" else "Add file")
            }
            OutlinedButton(onClick = onClick) {
                Text(if (ready) "Change" else "Add")
            }
        }
    }
}
