package com.futurethinking.aivideodirector.work

import android.app.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.futurethinking.aivideodirector.data.ProjectStore
import com.futurethinking.aivideodirector.media.*
import com.futurethinking.aivideodirector.pipeline.*
import kotlinx.coroutines.CancellationException
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class GenerationWorker(appContext:android.content.Context,params:WorkerParameters):CoroutineWorker(appContext,params){
 override suspend fun doWork():Result{
  val id=inputData.getString(KEY_PROJECT_ID)
   ?:return Result.failure(workDataOf(KEY_ERROR to "missing_project_id"))
  val store=ProjectStore(applicationContext)
  val p=store.list().firstOrNull{it.id==id}
   ?:return Result.failure(workDataOf(KEY_ERROR to "project_not_found"))
  // A queue reconciliation or a fast repeated tap can leave a duplicate
  // WorkRequest in the unique chain. Never re-render a project that has
  // already completed, and never start work for a non-pending project.
  if(p.state=="READY" && p.outputPath?.let{File(it).exists() && File(it).length()>8192L}==true)
   return Result.success(workDataOf(KEY_SKIPPED to true))
  if(p.state=="PAUSED"||p.state=="CANCELLED"||p.state=="DRAFT"||p.state=="ERROR")
   return Result.success(workDataOf(KEY_SKIPPED to true))
  if(p.state!="QUEUED"&&p.state!="ANALYZING"&&p.state!="RENDERING")
   return Result.success(workDataOf(KEY_SKIPPED to true))
  val next=store.list().filter{it.state=="QUEUED"}.minByOrNull{it.queueRank}
  if(next!=null&&next.id!=p.id) return Result.success(workDataOf(KEY_SKIPPED to true))

  suspend fun stage(n:Int,s:String){
   if(isStopped)throw CancellationException("cancelled")
   p.progress=n
   p.progressStage=s
   if(n>=100)p.state="READY"
   else if(s.contains("analysis",true)||s.contains("timestamp",true)||s.contains("extracting",true)||s.contains("timeline",true))p.state="ANALYZING"
   else p.state="RENDERING"
   store.save(p)
   setProgressAsync(workDataOf(KEY_PROGRESS to n,KEY_STAGE to s))
   setForeground(foreground(s,n))
  }

  return try{
   setForeground(foreground("Starting analysis",1))
   require(store.availableStorageBytes()>=MIN_FREE_STORAGE_BYTES){
    "Storage is too low for safe rendering. Free at least 500 MB and try again."
   }
   require(p.timestampPdfPath?.let{File(it).exists()}==true){"timestamp_script_pdf_missing"}
   require(p.audioPath?.let{File(it).exists()}==true){"voiceover_missing"}
   require(p.pdfPath?.let{File(it).exists()}==true){"pdf_missing"}

   p.state="ANALYZING";p.lastError=null;p.analysisReport=null;p.progressStage="Reading timestamp PDF";store.save(p)
   stage(5,"Reading timestamp PDF")

   val script=PdfTimestampScriptReader(applicationContext).read(p.timestampPdfPath!!)
   p.script=script
   val audio=AudioDurationReader.durationMs(p.audioPath!!)
   require(audio>0){"audio_duration_unavailable"}
   val markers=TimestampScriptParser().parse(script)

   stage(10,"Extracting yellow-line panels")
   val dir=File(store.assetDir(p),"pdf-visuals")
   val cachedVisuals=p.visualPaths.filter{java.io.File(it).exists()&&java.io.File(it).length()>2048L}
   val canReuseCache=p.pdfPageCount>0 && cachedVisuals.isNotEmpty() && cachedVisuals.size==p.visualPaths.size
   val ex=if(canReuseCache){
    p.progress=55
    p.progressStage="Reusing extracted yellow-line panels"
    p.state="ANALYZING"
    setProgressAsync(workDataOf(KEY_PROGRESS to 55,KEY_STAGE to p.progressStage))
    PdfVisualExtractor.Result(p.pdfPageCount,emptyList(),cachedVisuals)
   }else{
    dir.deleteRecursively()
    var lastExtractionPersistAt=0L
    var lastExtractionPersistProgress=-1
    val result=PdfVisualExtractor(applicationContext).extract(p.pdfPath!!,dir){a,b->
     if(!isStopped){
      val now=System.currentTimeMillis()
      p.progress=a;p.progressStage=b;p.state="ANALYZING"
      setProgressAsync(workDataOf(KEY_PROGRESS to a,KEY_STAGE to b))
      if(a>=55 || a-lastExtractionPersistProgress>=2 || now-lastExtractionPersistAt>=1000L){
       lastExtractionPersistAt=now
       lastExtractionPersistProgress=a
       store.save(p)
      }
     }
    }
    result
   }
   require(ex.visualPaths.isNotEmpty()){"pdf_extraction_failed"}
   p.pdfPageCount=ex.pageCount
   p.visualPaths.clear()
   p.visualPaths.addAll(ex.visualPaths)
   store.save(p)

   stage(58,"Building timestamp timeline")
   val plan=TimestampScenePlanner().plan(markers,ex.visualPaths,audio,p.preferences)
   val rawTimelineEnd=plan.scenes.maxOfOrNull{it.endMs}?:0L
   val durationDelta=rawTimelineEnd-audio
   val report=buildAnalysisReport(audio,markers.size,ex.visualPaths.size,rawTimelineEnd,durationDelta,plan.diagnostics,store.availableStorageBytes())
   p.analysisReport=report
   store.save(p)

   require(durationDelta<=5000L&&durationDelta>=-5000L){
    "Timeline/audio mismatch exceeds the allowed 5 second correction window.\n"+report
   }
   val qc=QualityControl.inspectTimeline(plan.scenes,audio,ex.visualPaths,plan.frameToleranceMs)
   require(qc.ok){qc.issues.joinToString("|")+"\n"+report}

   p.durationMs=audio
   p.scenePlanJson=JSONArray(plan.scenes.map{
    JSONObject().apply{
     put("id",it.id);put("startMs",it.startMs);put("endMs",it.endMs)
     put("visualPath",it.visualPath);put("pdfOrdinal",it.pdfOrdinal)
     put("motionDirection",it.motionDirection?.name)
    }
   }).toString()
   store.save(p)

   stage(72,"Rendering video")
   require(store.availableStorageBytes()>=MIN_FREE_STORAGE_BYTES){
    "Storage became too low before rendering. Free at least 500 MB and retry."
   }
   val out=File(
    store.outputDir(p),
    p.title.replace(Regex("[^A-Za-z0-9._-]+"),"_").take(48).ifBlank{"AI_Video"}+".mp4"
   )
   var lastPersistAt = 0L
   var lastPersistProgress = -1
   VideoRenderer(applicationContext).render(plan.scenes,p.audioPath,p.preferences,out){n->
    if(!isStopped){
     val now = System.currentTimeMillis()
     p.progress=n
     p.progressStage="Rendering video"
     p.state="RENDERING"
     if(n != lastPersistProgress && (now-lastPersistAt >= 1000L || n >= 98)){
      lastPersistAt = now
      lastPersistProgress = n
      store.save(p)
      setProgressAsync(workDataOf(KEY_PROGRESS to n,KEY_STAGE to "Rendering video"))
     }
    }
   }
   require(QualityControl.inspectRenderedFile(out,audio,true)==null){
    "output_validation_failed. See analysis report for timeline/audio details."
   }
   p.outputPath=out.absolutePath;p.state="READY";p.progress=100;p.progressStage="Video ready";store.save(p)
   stage(100,"Video ready")
   Result.success()
  }catch(t:Throwable){
   if(t is CancellationException)throw t
   val transientFailure=t is java.io.IOException ||
     t is androidx.media3.transformer.ExportException ||
     t is IllegalStateException
   if(transientFailure&&runAttemptCount<3){
    p.state="QUEUED";p.progress=1;p.progressStage="Retrying after temporary renderer issue"
    p.lastError="Temporary renderer/codec issue. WorkManager will retry automatically."
    store.save(p)
    return Result.retry()
   }
   p.state="ERROR";p.progress=0;p.progressStage="Failed"
   p.lastError=t.message?:t.javaClass.simpleName
   store.save(p)
   Result.success(workDataOf(KEY_RESULT_STATE to "ERROR",KEY_ERROR to (p.lastError?:"generation_failed")))
  }
 }

 private fun buildAnalysisReport(
  audio:Long,timestamps:Int,panels:Int,timelineEnd:Long,delta:Long,
  corrections:List<String>,freeStorage:Long
 ):String{
  val absDelta=kotlin.math.abs(delta)
  val status=when{
   absDelta==0L->"MATCH: timestamp timeline and audio duration match."
   absDelta<=5000L->"CORRECTABLE: mismatch is "+formatMs(absDelta)+" and will be corrected automatically."
   else->"BLOCKED: mismatch is "+formatMs(absDelta)+" and exceeds the 5 second limit."
  }
  return buildString{
   appendLine("PRE-GENERATION ANALYSIS")
   appendLine("Audio duration: "+formatMs(audio))
   appendLine("Timestamp entries: "+timestamps)
   appendLine("Panel visuals found: "+panels)
   appendLine("Timestamp timeline end: "+formatMs(timelineEnd))
   appendLine("Audio/timeline difference: "+formatSigned(delta))
   appendLine("Free storage: "+formatBytes(freeStorage))
   appendLine(status)
   if(panels!=timestamps)appendLine("Panel/timestamp count difference: "+(panels-timestamps)+" panel(s).")
   if(corrections.isNotEmpty()){
    appendLine("Corrections:")
    corrections.forEach{appendLine("• "+it)}
   }else appendLine("Corrections: none required.")
  }
 }

 private fun formatMs(ms:Long):String{
  val a=kotlin.math.abs(ms)
  return "%02d:%02d.%03d".format(a/60000,(a/1000)%60,a%1000)
 }
 private fun formatSigned(ms:Long):String{
  val sign=if(ms>0)"+" else if(ms<0)"-" else ""
  return sign+formatMs(ms)
 }
 private fun formatBytes(bytes:Long):String{
  return when{
   bytes>=1024L*1024L*1024L->"%.2f GB".format(bytes.toDouble()/(1024.0*1024.0*1024.0))
   else->"%.0f MB".format(bytes.toDouble()/(1024.0*1024.0))
  }
 }
 private fun foreground(text:String,n:Int):ForegroundInfo{
  val id="editor_generation"
  if(Build.VERSION.SDK_INT>=26){
   applicationContext.getSystemService(NotificationManager::class.java)
    .createNotificationChannel(NotificationChannel(id,"Video generation",NotificationManager.IMPORTANCE_LOW))
  }
  val no=NotificationCompat.Builder(applicationContext,id)
   .setContentTitle("Editor and Merger")
   .setContentText(text)
   .setSmallIcon(android.R.drawable.ic_media_play)
   .setOnlyAlertOnce(true).setOngoing(true)
   .setProgress(100,n.coerceIn(0,100),false).build()
  return ForegroundInfo(1001,no,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
 }

 companion object{
  const val KEY_PROJECT_ID="project_id"
  const val KEY_PROGRESS="progress"
  const val KEY_STAGE="stage"
  const val KEY_ERROR="error"
  const val KEY_RESULT_STATE="result_state"
  const val KEY_SKIPPED="skipped"
  private const val MIN_FREE_STORAGE_BYTES=500L*1024L*1024L
 }
}
