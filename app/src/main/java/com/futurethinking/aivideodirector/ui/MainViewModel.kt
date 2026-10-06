package com.futurethinking.aivideodirector.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.futurethinking.aivideodirector.data.Project
import com.futurethinking.aivideodirector.data.ProjectStore
import com.futurethinking.aivideodirector.pipeline.PdfTimestampScriptReader
import com.futurethinking.aivideodirector.pipeline.TimestampScriptParser
import com.futurethinking.aivideodirector.work.GenerationWorker
import com.futurethinking.aivideodirector.work.MergeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainViewModel(app:Application):AndroidViewModel(app){
 private val store=ProjectStore(app)
 private val wm=WorkManager.getInstance(app)
 private val _projects=MutableStateFlow(store.list())
 val projects=_projects.asStateFlow()
 private val _current=MutableStateFlow<Project?>(null)
 val current=_current.asStateFlow()
 private val _error=MutableStateFlow<String?>(null)
 val error=_error.asStateFlow()
 private val _merge=MutableStateFlow<List<String>>(emptyList())
 val mergeSelection=_merge.asStateFlow()

 init{
  viewModelScope.launch{
   wm.getWorkInfosForUniqueWorkFlow(MEDIA_QUEUE_NAME).collectLatest{refresh()}
  }
  reconcileQueue()
 }

 fun refresh(){viewModelScope.launch(Dispatchers.IO){_projects.value=store.list()}}
 fun createProject(){
  if(store.list().count{!it.isMerged}<10)_current.value=store.create()
  else _error.value="Maximum 10 projects reached."
  refresh()
 }
 fun selectProject(p:Project){_current.value=p}
 fun updateProject(f:(Project)->Unit){
  val p=_current.value?:return
  f(p);store.save(p);refresh()
 }

 fun importAudio(uri:android.net.Uri){
  val p=_current.value?:return
  viewModelScope.launch(Dispatchers.IO){
   runCatching{store.importUri(p,uri,"voice")}
    .onSuccess{p.audioPath=it;p.outputPath=null;p.state="DRAFT";p.progress=0;p.progressStage="";p.analysisReport=null;store.save(p);refresh()}
    .onFailure{_error.value=it.message}
  }
 }

 fun importTimestampPdf(uri:android.net.Uri){
  val p=_current.value?:return
  viewModelScope.launch(Dispatchers.IO){
   runCatching{
    val path=store.importUri(p,uri,"timestamp-script")
    val s=PdfTimestampScriptReader(getApplication()).read(path)
    TimestampScriptParser().parse(s)
    path to s
   }.onSuccess{
    p.timestampPdfPath=it.first;p.script=it.second;p.outputPath=null;p.state="DRAFT";p.progress=0;p.progressStage="";p.analysisReport=null;store.save(p);refresh()
   }.onFailure{_error.value=it.message}
  }
 }

 fun importPdf(uri:android.net.Uri){
  val p=_current.value?:return
  viewModelScope.launch(Dispatchers.IO){
   runCatching{store.importUri(p,uri,"source")}
    .onSuccess{p.pdfPath=it;p.outputPath=null;p.state="DRAFT";p.progress=0;p.progressStage="";p.analysisReport=null;store.save(p);refresh()}
    .onFailure{_error.value=it.message}
  }
 }

 fun generate(){
  val p=_current.value?:return
  if(p.timestampPdfPath==null||p.audioPath==null||p.pdfPath==null){
   _error.value="Add timestamp PDF, audio and panels PDF first.";return
  }
  if(store.availableStorageBytes()<MIN_FREE_STORAGE_BYTES){
   _error.value="Storage is too low for a safe render. Free at least 500 MB and try again.";return
  }
  p.state="QUEUED";p.progress=1;p.progressStage="Queued";p.lastError=null;store.save(p)
  val req=generationRequest(p)
  enqueueMediaWork(req);observe(req.id,p.id);refresh()
 }

 private fun generationRequest(p:Project):OneTimeWorkRequest=
  OneTimeWorkRequestBuilder<GenerationWorker>()
   .setInputData(workDataOf(GenerationWorker.KEY_PROJECT_ID to p.id))
   .addTag(GENERATION_TAG)
   .setConstraints(storageConstraints())
   .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()

 private fun mergeRequest(p:Project):OneTimeWorkRequest=
  OneTimeWorkRequestBuilder<MergeWorker>()
   .setInputData(workDataOf(MergeWorker.KEY_PROJECT_ID to p.id))
   .addTag(MERGE_TAG)
   .setConstraints(storageConstraints())
   .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()

 private fun storageConstraints()=Constraints.Builder().setRequiresStorageNotLow(true).build()

 private fun enqueueMediaWork(req:OneTimeWorkRequest){
  wm.beginUniqueWork(MEDIA_QUEUE_NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,req).enqueue()
 }

 private fun isPendingState(s:String)=s=="QUEUED"||s=="ANALYZING"||s=="RENDERING"||s=="MERGING"

 private fun reconcileQueue(){
  viewModelScope.launch(Dispatchers.IO){
   val prefs=getApplication<Application>().getSharedPreferences(PREFS_NAME,0)
   var ps=store.list()
   val migration=prefs.getInt(QUEUE_VERSION_KEY,0)<QUEUE_VERSION
   if(migration){
    ps.forEach{wm.cancelUniqueWork("generate-"+it.id);wm.cancelUniqueWork("merge-"+it.id)}
    ps.filter{isPendingState(it.state)}.forEach{it.state="QUEUED";it.progress=1;it.progressStage="Queued after recovery";store.save(it)}
    prefs.edit().putInt(QUEUE_VERSION_KEY,QUEUE_VERSION).commit()
    ps=store.list()
   }
   val info=runCatching{wm.getWorkInfosForUniqueWork(MEDIA_QUEUE_NAME).get()}.getOrDefault(emptyList())
   if(info.none{!it.state.isFinished}){
    ps.filter{isPendingState(it.state)}
     .sortedWith(compareBy<Project>{it.createdAt}.thenBy{it.updatedAt})
     .forEach{enqueueMediaWork(if(it.isMerged)mergeRequest(it)else generationRequest(it))}
   }
   _projects.value=store.list()
  }
 }

 private fun observe(workId:java.util.UUID,projectId:String){
  viewModelScope.launch{
   wm.getWorkInfoByIdFlow(workId).collectLatest{info->
    if(info==null)return@collectLatest
    val target=store.list().firstOrNull{it.id==projectId}?:return@collectLatest
    target.progress=info.progress.getInt(GenerationWorker.KEY_PROGRESS,target.progress)
    target.progressStage=info.progress.getString(GenerationWorker.KEY_STAGE)?:target.progressStage
    if(info.state==WorkInfo.State.SUCCEEDED){
     val resultState=info.outputData.getString(GenerationWorker.KEY_RESULT_STATE)
     if(resultState=="ERROR"){
      target.state="ERROR";target.progress=0
      target.progressStage="Failed"
      target.lastError=info.outputData.getString(GenerationWorker.KEY_ERROR)
      _error.value=target.lastError
     }else{
      target.progress=100;target.state="READY";target.progressStage=if(target.isMerged)"Merge ready" else "Video ready"
     }
    }
    if(info.state==WorkInfo.State.FAILED){
     target.state="ERROR";target.progress=0;target.progressStage="Failed"
     target.lastError=info.outputData.getString(GenerationWorker.KEY_ERROR)
     _error.value=target.lastError
    }
    if(info.state==WorkInfo.State.CANCELLED&&target.state!="READY"&&target.state!="ERROR"){
     target.state="QUEUED";target.progress=1;target.progressStage="Queued for recovery"
    }
    store.save(target)
    if(_current.value?.id==projectId)_current.value=target
    refresh()
   }
  }
 }

 fun retryProject(p:Project){
  if(p.state!="ERROR")return
  p.state="QUEUED";p.progress=1;p.progressStage="Retry queued";p.lastError=null;store.save(p)
  enqueueMediaWork(if(p.isMerged)mergeRequest(p)else generationRequest(p))
  refresh()
 }

 fun setMergeSelection(ids:List<String>){_merge.value=ids}
 fun addMergeItem(id:String){if(id !in _merge.value)_merge.value=_merge.value+id}
 fun removeMergeItem(id:String){_merge.value=_merge.value.filterNot{it==id}}

 fun importMergeVideo(uri:android.net.Uri,onDone:(String)->Unit){
  val temp=store.create("Merge Import");temp.isMerged=true;temp.state="IMPORT";store.save(temp)
  viewModelScope.launch(Dispatchers.IO){
   runCatching{store.importUri(temp,uri,"merge-video")}
    .onSuccess{onDone(it)}
    .onFailure{temp.state="ERROR";temp.lastError=it.message;store.save(temp);_error.value=it.message}
  }
 }

 fun merge(imported:List<String>){
  val items=_merge.value
  if(items.isEmpty()&&imported.isEmpty()){_error.value="Select at least one rendered project or imported video.";return}
  if(store.availableStorageBytes()<MIN_FREE_STORAGE_BYTES){_error.value="Storage is too low for a safe merge. Free at least 500 MB and try again.";return}
  val p=store.create("Merged Video");p.isMerged=true;p.state="QUEUED";p.progress=1;p.progressStage="Merge queued"
  p.mergeItemsJson=org.json.JSONArray((items+imported).map{if(it.startsWith("video:"))it else "project:"+it}).toString()
  store.save(p)
  val req=mergeRequest(p);enqueueMediaWork(req);observe(req.id,p.id);refresh()
 }

 fun shareOutput(p:Project):Intent?{
  val f=p.outputPath?.let(::File)?.takeIf{it.exists()&&it.length()>8192L}?:return null
  return Intent(Intent.ACTION_SEND).apply{
   type="video/mp4"
   putExtra(Intent.EXTRA_STREAM,FileProvider.getUriForFile(getApplication(),getApplication<Application>().packageName+".fileprovider",f))
   addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
 }

 companion object{
  const val MEDIA_QUEUE_NAME="editor-and-merger-media-queue"
  private const val GENERATION_TAG="editor-generation"
  private const val MERGE_TAG="editor-merge"
  private const val PREFS_NAME="editor-and-merger-settings"
  private const val QUEUE_VERSION_KEY="media_queue_version"
  private const val QUEUE_VERSION=3
  private const val MIN_FREE_STORAGE_BYTES=500L*1024L*1024L
 }
}
