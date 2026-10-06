package com.futurethinking.aivideodirector.work
import android.app.*;import android.os.*;import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.futurethinking.aivideodirector.data.ProjectStore
import com.futurethinking.aivideodirector.media.*;import com.futurethinking.aivideodirector.pipeline.*
import kotlinx.coroutines.CancellationException
import java.io.File
import org.json.JSONArray;import org.json.JSONObject

class GenerationWorker(appContext:android.content.Context,params:WorkerParameters):CoroutineWorker(appContext,params){
 override suspend fun doWork():Result{
  val id=inputData.getString(KEY_PROJECT_ID)?:return Result.failure(workDataOf(KEY_ERROR to "missing_project_id"));val store=ProjectStore(applicationContext);val p=store.list().firstOrNull{it.id==id}?:return Result.failure(workDataOf(KEY_ERROR to "project_not_found"))
   suspend fun stage(n:Int,s:String){if(isStopped)throw CancellationException("cancelled");p.progress=n;p.state=if(n>=100)"READY" else "RENDERING";store.save(p);setProgressAsync(workDataOf(KEY_PROGRESS to n,KEY_STAGE to s));setForeground(foreground(s,n))}
  return try{
   setForeground(foreground("Starting analysis",1))
   require(p.timestampPdfPath?.let{File(it).exists()}==true){"timestamp_script_pdf_missing"};require(p.audioPath?.let{File(it).exists()}==true){"voiceover_missing"};require(p.pdfPath?.let{File(it).exists()}==true){"pdf_missing"}
   p.state="ANALYZING";p.lastError=null;p.analysisReport=null;store.save(p);stage(5,"Reading timestamp PDF")
   val script=PdfTimestampScriptReader(applicationContext).read(p.timestampPdfPath!!);p.script=script;val audio=AudioDurationReader.durationMs(p.audioPath!!);require(audio>0){"audio_duration_unavailable"};val markers=TimestampScriptParser().parse(script);stage(10,"Extracting yellow-line panels")
   val dir=File(store.assetDir(p),"pdf-visuals");dir.deleteRecursively();val ex=PdfVisualExtractor(applicationContext).extract(p.pdfPath!!,dir){a,b->if(!isStopped){p.progress=a;p.state="RENDERING";store.save(p);setProgressAsync(workDataOf(KEY_PROGRESS to a,KEY_STAGE to b))}};require(ex.visualPaths.isNotEmpty()){"pdf_extraction_failed"};p.pdfPageCount=ex.pageCount;p.visualPaths.clear();p.visualPaths.addAll(ex.visualPaths);store.save(p)
   stage(58,"Building timestamp timeline");val plan=TimestampScenePlanner().plan(markers,ex.visualPaths,audio,p.preferences)
   val rawTimelineEnd=plan.scenes.maxOfOrNull{it.endMs}?:0L
   val durationDelta=rawTimelineEnd-audio
   val panelDelta=ex.visualPaths.size-markers.size
   val report=buildAnalysisReport(audio,markers.size,ex.visualPaths.size,rawTimelineEnd,durationDelta,plan.diagnostics)
   p.analysisReport=report
   store.save(p)
   require(durationDelta <= 5000L && durationDelta >= -5000L){"Timeline/audio mismatch exceeds the allowed 5 second correction window.\n"+report}
   val qc=QualityControl.inspectTimeline(plan.scenes,audio,ex.visualPaths,plan.frameToleranceMs);require(qc.ok){qc.issues.joinToString("|")+"\n"+report}
   p.durationMs=audio;p.scenePlanJson=JSONArray(plan.scenes.map{JSONObject().apply{put("id",it.id);put("startMs",it.startMs);put("endMs",it.endMs);put("visualPath",it.visualPath);put("pdfOrdinal",it.pdfOrdinal);put("motionDirection",it.motionDirection?.name)}}).toString()
   stage(72,"Rendering video");val out=File(store.outputDir(p),p.title.replace(Regex("[^A-Za-z0-9._-]+"),"_").take(48).ifBlank{"AI_Video"}+".mp4")
   VideoRenderer(applicationContext).render(plan.scenes,p.audioPath,p.preferences,out){n->if(!isStopped){p.progress=n;p.state="RENDERING";store.save(p);setProgressAsync(workDataOf(KEY_PROGRESS to n,KEY_STAGE to "Rendering video"))}};require(QualityControl.inspectRenderedFile(out,audio,true)==null){"output_validation_failed. See analysis report for timeline/audio details."};p.outputPath=out.absolutePath;p.state="READY";p.progress=100;store.save(p);stage(100,"Video ready");Result.success()
  }catch(t:Throwable){
   if(t is CancellationException){throw t}
   val transientFailure=t is java.io.IOException || t is androidx.media3.transformer.ExportException || t is IllegalStateException
   if(transientFailure && runAttemptCount < 2){
      p.state="QUEUED";p.progress=1;p.lastError="Temporary renderer/codec issue. WorkManager will retry automatically.";store.save(p)
      return Result.retry()
   }
   p.state="ERROR";p.progress=0;p.lastError=t.message?:t.javaClass.simpleName;store.save(p);Result.success(workDataOf(KEY_RESULT_STATE to "ERROR",KEY_ERROR to (p.lastError?:"generation_failed")))
 }
 }
 private fun buildAnalysisReport(audio:Long,timestamps:Int,panels:Int,timelineEnd:Long,delta:Long,corrections:List<String>):String{
  val absDelta=kotlin.math.abs(delta)
  val status=when{absDelta==0L->"MATCH: timestamp timeline and audio duration match.";absDelta<=5000L->"CORRECTABLE: mismatch is "+formatMs(absDelta)+" and will be corrected automatically.";else->"BLOCKED: mismatch is "+formatMs(absDelta)+" and exceeds the 5 second limit."}
  return buildString{
   appendLine("PRE-GENERATION ANALYSIS")
   appendLine("Audio duration: "+formatMs(audio))
   appendLine("Timestamp entries: "+timestamps)
   appendLine("Panel visuals found: "+panels)
   appendLine("Timestamp timeline end: "+formatMs(timelineEnd))
   appendLine("Audio/timeline difference: "+formatSigned(delta))
   appendLine(status)
   if(panels!=timestamps) appendLine("Panel/timestamp count difference: "+(panels-timestamps)+" panel(s).")
   if(corrections.isNotEmpty()){appendLine("Corrections:");corrections.forEach{appendLine("• "+it)}}
   else appendLine("Corrections: none required.")
  }
 }
 private fun formatMs(ms:Long):String{val a=kotlin.math.abs(ms);return "%02d:%02d.%03d".format(a/60000,(a/1000)%60,a%1000)}
 private fun formatSigned(ms:Long):String{val sign=if(ms>0)"+" else if(ms<0)"-" else "";return sign+formatMs(ms)}
 private fun foreground(text:String,n:Int):ForegroundInfo{val id="ai_generation";if(Build.VERSION.SDK_INT>=26)applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id,"Video generation",NotificationManager.IMPORTANCE_LOW));val no=NotificationCompat.Builder(applicationContext,id).setContentTitle("AI Universal Auto Editor Pro").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,n.coerceIn(0,100),false).build();return ForegroundInfo(1001,no,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)}
 companion object{const val KEY_PROJECT_ID="project_id";const val KEY_PROGRESS="progress";const val KEY_STAGE="stage";const val KEY_ERROR="error";const val KEY_RESULT_STATE="result_state"}
}
