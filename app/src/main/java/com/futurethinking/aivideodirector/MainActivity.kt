@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.futurethinking.aivideodirector
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity:ComponentActivity(){
 private val vm by viewModels<com.futurethinking.aivideodirector.ui.MainViewModel>()
 private val audio=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::importAudio)}
 private val pdf=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::importPdf)}
 private val ts=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::importTimestampPdf)}
 private val vid=registerForActivityResult(ActivityResultContracts.OpenDocument()){u->u?.let{vm.importMergeVideo(it){p->vm.addMergeItem("video:"+p)}}}
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App()}}
 @Composable fun App(){
  val ps by vm.projects.collectAsStateWithLifecycle();val cur by vm.current.collectAsStateWithLifecycle();var screen by remember{mutableStateOf("projects")}
  MaterialTheme{Scaffold(topBar={TopAppBar(title={Text(if(screen=="projects")"AI Universal Auto Editor Pro" else if(screen=="merge")"Merge Videos" else cur?.title?:"Project")},navigationIcon={if(screen!="projects")IconButton({screen="projects"}){Icon(Icons.Default.ArrowBack,null)}},actions={if(screen=="projects"){Button({vm.createProject()}){Text("New")};Spacer(Modifier.width(6.dp));OutlinedButton({screen="merge"}){Text("Merge")}}})}){pad->Surface(Modifier.fillMaxSize().padding(pad)){when(screen){"projects"->ProjectList(ps.filterNot{it.isMerged&&it.state=="IMPORT"}){p->vm.selectProject(p);screen="editor"};"merge"->MergeScreen(ps.filter{!it.isMerged&&it.state=="READY"},vm){vid.launch(arrayOf("video/*"))};else->Editor(cur,vm,{audio.launch(arrayOf("audio/*"))},{pdf.launch(arrayOf("application/pdf"))},{ts.launch(arrayOf("application/pdf"))})}}}}
 @Composable fun ProjectList(ps:List<Project>,open:(Project)->Unit){LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("Up to 10 projects can render independently in the background.")};items(ps,key={it.id}){p->Card(onClick={open(p)},modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(if(p.isMerged)Icons.Default.MergeType else Icons.Default.Movie,null);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(p.title);Text(if(p.state=="READY")"Ready • 100%" else p.state+" • "+p.progress+"%")};if(p.progress in 1..99)CircularProgressIndicator(progress={p.progress/100f},modifier=Modifier.size(30.dp))}}}}}}
 @Composable fun Editor(p:Project?,vm:com.futurethinking.aivideodirector.ui.MainViewModel,a:()->Unit,pdf:()->Unit,ts:()->Unit){if(p==null)return;LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text(p.title,style=MaterialTheme.typography.headlineSmall)};item{Asset("Timestamp Script PDF",p.timestampPdfPath!=null,ts)};item{Asset("Audio",p.audioPath!=null,a)};item{Asset("Panels PDF",p.pdfPath!=null,pdf)};item{Card(modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Generate video");Text("Timestamp PDF controls timing. Yellow separator lines control panel extraction. Original audio remains the timeline.");Button({vm.generate()},enabled=p.progress !in 1..99,modifier=Modifier.fillMaxWidth()){Text(if(p.progress in 1..99)"Rendering "+p.progress+"%" else "Generate")};if(p.progress>0){LinearProgressIndicator(progress={p.progress/100f},modifier=Modifier.fillMaxWidth());Text(p.progress.toString()+"%")};if(p.outputPath!=null&&p.state=="READY"){Button({vm.shareOutput(p)?.let{startActivity(Intent.createChooser(it,"Export video"))}},modifier=Modifier.fillMaxWidth()){Text("Export video")}}}}}}}}
 @Composable fun Asset(name:String,ready:Boolean,onClick:()->Unit){Card(modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(name);Text(if(ready)"Ready" else "Add file")};OutlinedButton(onClick){Text(if(ready)"Change" else "Add")}}}}
 @Composable fun MergeScreen(ready:List<Project>,vm:com.futurethinking.aivideodirector.ui.MainViewModel,onImport:()->Unit){val sel by vm.mergeSelection.collectAsStateWithLifecycle();LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("Add videos in the exact order you want them merged.")};items(ready,key={it.id}){p->Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){Button({vm.addMergeItem(p.id)},enabled=p.id !in sel,modifier=Modifier.weight(1f)){Text("Add "+p.title)};if(p.id in sel)Text("#"+(sel.indexOf(p.id)+1))}};item{OutlinedButton(onImport,modifier=Modifier.fillMaxWidth()){Text("Import video from phone")}};item{Text("Order: "+sel.mapIndexed{n,id->(n+1).toString()+" "+(ready.firstOrNull{it.id==id}?.title?:"Imported video")}.joinToString(" → "))};item{Button({vm.merge(emptyList())},enabled=sel.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("Merge selected videos")}}}}
}