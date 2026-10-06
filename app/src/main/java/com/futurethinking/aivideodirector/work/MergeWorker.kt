package com.futurethinking.aivideodirector.work

import android.app.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.media3.common.*
import androidx.media3.transformer.*
import androidx.work.*
import com.futurethinking.aivideodirector.data.ProjectStore
import com.futurethinking.aivideodirector.media.MediaExportGate
import com.futurethinking.aivideodirector.pipeline.QualityControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray

class MergeWorker(appContext:android.content.Context,params:WorkerParameters):CoroutineWorker(appContext,params){
 override suspend fun doWork():Result{
  val id=inputData.getString(KEY_PROJECT_ID)?:return Result.failure(workDataOf(KEY_ERROR to "missing_project_id"))
  val store=ProjectStore(applicationContext)
  val p=store.list().firstOrNull{it.id==id}?:return Result.failure(workDataOf(KEY_ERROR to "merge_project_not_found"))
  if(p.state=="PAUSED"||p.state=="CANCELLED") return Result.success(workDataOf(KEY_SKIPPED to true))
  val next=store.list().filter{it.state=="QUEUED"}.minByOrNull{it.queueRank}
  if(next!=null&&next.id!=p.id) return Result.success(workDataOf(KEY_SKIPPED to true))

  fun stage(n:Int,s:String){
   p.progress=n;p.progressStage=s;p.state=if(n>=100)"READY" else "MERGING"
   store.save(p);setProgressAsync(workDataOf(KEY_PROGRESS to n,KEY_STAGE to s))
  }

  return try{
   stage(5,"Preparing merge")
   val raw=JSONArray(p.mergeItemsJson?:"[]")
   val paths=mutableListOf<String>()
   for(i in 0 until raw.length()){
    val token=raw.getString(i)
    if(token.startsWith("project:")){
     val id2=token.removePrefix("project:")
     val q=store.list().firstOrNull{it.id==id2}
     require(q?.outputPath?.let{File(it).exists()}==true){"project_output_missing"}
     paths+=q!!.outputPath!!
    }else if(token.startsWith("video:")){
     val f=File(token.removePrefix("video:"))
     require(f.exists()&&f.length()>8192L){"imported_video_missing"}
     paths+=f.absolutePath
    }
   }
   require(paths.isNotEmpty()){"nothing_to_merge"}
   val expectedDurationMs=paths.sumOf{durationMs(it)}
   require(expectedDurationMs>0L){"merge_input_duration_unavailable"}

   val out=File(store.outputDir(p),"Merged_"+System.currentTimeMillis()+".mp4")
   val temp=File(out.parentFile,"."+out.name+".rendering")
   if(temp.exists())temp.delete()
   val items=paths.map{EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(File(it)))).build()}

   stage(12,"Merging videos")
   MediaExportGate.withLock{
    awaitExport(items,temp){n->stage(12+(n*86/100),"Merging videos")}
   }

   require(temp.exists()&&temp.length()>8192L){"merge_output_invalid"}
   require(QualityControl.inspectRenderedFile(temp,expectedDurationMs,true)==null){"merge_output_validation_failed"}
   if(out.exists())out.delete()
   require(temp.renameTo(out)){"merge_finalize_failed"}
   require(QualityControl.inspectRenderedFile(out,expectedDurationMs,true)==null){"merge_final_validation_failed"}

   p.outputPath=out.absolutePath;p.progress=100;p.progressStage="Merge ready";p.state="READY";store.save(p)
   stage(100,"Merge ready")
   Result.success()
  }catch(t:Throwable){
   if(t is CancellationException)throw t
   p.state="ERROR";p.progress=0;p.progressStage="Failed";p.lastError=t.message?:t.javaClass.simpleName;store.save(p)
   Result.success(workDataOf(KEY_RESULT_STATE to "ERROR",KEY_ERROR to (p.lastError?:"merge_failed")))
  }
 }

 private fun durationMs(path:String):Long{
  val r=android.media.MediaMetadataRetriever()
  return try{
   r.setDataSource(path)
   r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
  }finally{r.release()}
 }

 private suspend fun awaitExport(items:List<EditedMediaItem>,file:File,onProgress:(Int)->Unit)=
  suspendCancellableCoroutine<Unit>{c->
   val handler=Handler(Looper.getMainLooper())
   handler.post{
    if(!c.isActive)return@post
    val holder=ProgressHolder();var tr:Transformer?=null;var done=false
    val poll=object:Runnable{
     override fun run(){
      if(done)return
      tr?.let{if(it.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE)onProgress(holder.progress)}
      if(!done)handler.postDelayed(this,400)
     }
    }
    val listener=object:Transformer.Listener{
     override fun onCompleted(cmp:Composition,r:ExportResult){
      if(done)return;done=true;handler.removeCallbacks(poll);c.resume(Unit)
     }
     override fun onError(cmp:Composition,r:ExportResult,e:ExportException){
      if(done)return;done=true;handler.removeCallbacks(poll);c.resumeWithException(e)
     }
    }
    tr=Transformer.Builder(applicationContext).setVideoMimeType(MimeTypes.VIDEO_H264)
     .setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(listener).build()
    c.invokeOnCancellation{handler.removeCallbacks(poll);runCatching{tr?.cancel()}}
    val video=EditedMediaItemSequence.withVideoFrom(items)
    val audio=EditedMediaItemSequence.withAudioFrom(items)
    tr!!.start(Composition.Builder(video,audio).build(),file.absolutePath)
    handler.post(poll)
   }
  }

 private fun foreground(text:String,n:Int):ForegroundInfo{
  val id="editor_merge"
  if(Build.VERSION.SDK_INT>=26)applicationContext.getSystemService(NotificationManager::class.java)
   .createNotificationChannel(NotificationChannel(id,"Video merge",NotificationManager.IMPORTANCE_LOW))
  val no=NotificationCompat.Builder(applicationContext,id).setContentTitle("Editor and Merger")
   .setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).setOnlyAlertOnce(true)
   .setOngoing(true).setProgress(100,n.coerceIn(0,100),false).build()
  return ForegroundInfo(1002,no,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
 }

 companion object{
  const val KEY_PROJECT_ID="project_id";const val KEY_PROGRESS="progress";const val KEY_STAGE="stage"
  const val KEY_ERROR="error";const val KEY_RESULT_STATE="result_state";const val KEY_SKIPPED="skipped"
 }
}
