package com.futurethinking.aivideodirector.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
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
 private val store=ProjectStore(app); private val wm=WorkManager.getInstance(app)
 private val _projects=MutableStateFlow(store.list());val projects=_projects.asStateFlow()
 private val _current=MutableStateFlow<Project?>(null);val current=_current.asStateFlow()
 private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
 private val _merge=MutableStateFlow<List<String>>(emptyList());val mergeSelection=_merge.asStateFlow()
 fun refresh(){viewModelScope.launch(Dispatchers.IO){_projects.value=store.list()}}
 fun createProject(){if(store.list().count{!it.isMerged}<10)_current.value=store.create();else _error.value="Maximum 10 projects reached.";refresh()}
 fun selectProject(p:Project){_current.value=p}
 fun updateProject(f:(Project)->Unit){val p=_current.value?:return;f(p);store.save(p);refresh()}
 fun importAudio(uri:android.net.Uri){val p=_current.value?:return;viewModelScope.launch(Dispatchers.IO){runCatching{store.importUri(p,uri,"voice")}.onSuccess{p.audioPath=it;p.outputPath=null;p.state="DRAFT";p.progress=0;p.analysisReport=null;store.save(p);refresh()}.onFailure{_error.value=it.message}}}
 fun importTimestampPdf(uri:android.net.Uri){val p=_current.value?:return;viewModelScope.launch(Dispatchers.IO){runCatching{val path=store.importUri(p,uri,"timestamp-script");val s=PdfTimestampScriptReader(getApplication()).read(path);TimestampScriptParser().parse(s);path to s}.onSuccess{p.timestampPdfPath=it.first;p.script=it.second;p.outputPath=null;p.state="DRAFT";p.progress=0;p.analysisReport=null;store.save(p);refresh()}.onFailure{_error.value=it.message}}}
 fun importPdf(uri:android.net.Uri){val p=_current.value?:return;viewModelScope.launch(Dispatchers.IO){runCatching{store.importUri(p,uri,"source")}.onSuccess{p.pdfPath=it;p.outputPath=null;p.state="DRAFT";p.progress=0;p.analysisReport=null;store.save(p);refresh()}.onFailure{_error.value=it.message}}}
 fun generate(){val p=_current.value?:return;if(p.timestampPdfPath==null||p.audioPath==null||p.pdfPath==null){_error.value="Add timestamp PDF, audio and panels PDF first.";return};p.state="QUEUED";p.progress=1;p.lastError=null;store.save(p);val req=OneTimeWorkRequestBuilder<GenerationWorker>().setInputData(workDataOf(GenerationWorker.KEY_PROJECT_ID to p.id)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build();wm.enqueueUniqueWork("generate-"+p.id,ExistingWorkPolicy.REPLACE,req);observe(req.id,p.id)}
 private fun observe(workId:java.util.UUID,projectId:String){viewModelScope.launch{wm.getWorkInfoByIdFlow(workId).collectLatest{info->if(info!=null){val target=store.list().firstOrNull{it.id==projectId}?:return@collectLatest;target.progress=info.progress.getInt(GenerationWorker.KEY_PROGRESS,target.progress);if(info.state==WorkInfo.State.SUCCEEDED){target.progress=100;target.state="READY"};if(info.state==WorkInfo.State.FAILED){target.state="ERROR";target.progress=0;_error.value=info.outputData.getString(GenerationWorker.KEY_ERROR)};store.save(target);if(_current.value?.id==projectId)_current.value=target;refresh()}}}}
 fun setMergeSelection(ids:List<String>){_merge.value=ids}
 fun addMergeItem(id:String){if(id !in _merge.value)_merge.value=_merge.value+id}
 fun removeMergeItem(id:String){_merge.value=_merge.value.filterNot{it==id}}
 fun importMergeVideo(uri:android.net.Uri,onDone:(String)->Unit){val temp=store.create("Merge Import"); temp.isMerged=true; temp.state="IMPORT"; store.save(temp);viewModelScope.launch(Dispatchers.IO){runCatching{store.importUri(temp,uri,"merge-video")}.onSuccess{onDone(it)}.onFailure{_error.value=it.message}}}
 fun merge(imported:List<String>){val items=_merge.value;if(items.isEmpty()&&imported.isEmpty()){_error.value="Select at least one rendered project or imported video.";return};val p=store.create("Merged Video");p.isMerged=true;p.state="QUEUED";p.progress=1;p.mergeItemsJson=org.json.JSONArray((items+imported).map{if(it.startsWith("video:"))it else "project:"+it}).toString();store.save(p);val req=OneTimeWorkRequestBuilder<MergeWorker>().setInputData(workDataOf(MergeWorker.KEY_PROJECT_ID to p.id)).build();wm.enqueueUniqueWork("merge-"+p.id,ExistingWorkPolicy.REPLACE,req);refresh()}
 fun shareOutput(p:Project):Intent?{val f=p.outputPath?.let(::File)?.takeIf{it.exists()}?:return null;return Intent(Intent.ACTION_SEND).apply{type="video/mp4";putExtra(Intent.EXTRA_STREAM,FileProvider.getUriForFile(getApplication(),getApplication<Application>().packageName+".fileprovider",f));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
}
