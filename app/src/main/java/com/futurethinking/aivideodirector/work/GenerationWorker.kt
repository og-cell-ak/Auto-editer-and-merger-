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
  fun stage(n:Int,s:String){if(isStopped)throw CancellationException("cancelled");p.progress=n;p.state=if(n>=100)"READY" else "RENDERING";store.save(p);setProgressAsync(workDataOf(KEY_PROGRESS to n,KEY_STAGE to s));}
  return try{
   require(p.timestampPdfPath?.let{File(it).exists()}==true){"timestamp_script_pdf_missing"};require(p.audioPath?.let{File(it).exists()}==true){"voiceover_missing"};require(p.pdfPath?.let{File(it).exists()}==true){"pdf_missing"}
   p.state="ANALYZING";p.lastError=null;store.save(p);stage(5,"Reading timestamp PDF")
   val script=PdfTimestampScriptReader(applicationContext).read(p.timestampPdfPath!!);p.script=script;val audio=AudioDurationReader.durationMs(p.audioPath!!);require(audio>0){"audio_duration_unavailable"};val markers=TimestampScriptParser().parse(script);stage(10,"Extracting yellow-line panels")
   val dir=File(store.assetDir(p),"pdf-visuals");dir.deleteRecursively();val ex=PdfVisualExtractor(applicationContext).extract(p.pdfPath!!,dir){a,b->stage(a,b)};require(ex.visualPaths.isNotEmpty()){"pdf_extraction_failed"};p.pdfPageCount=ex.pageCount;p.visualPaths.clear();p.visualPaths.addAll(ex.visualPaths);store.save(p)
   stage(58,"Building timestamp timeline");val plan=TimestampScenePlanner().plan(markers,ex.visualPaths,audio,p.preferences);val qc=QualityControl.inspectTimeline(plan.scenes,audio,ex.visualPaths,plan.frameToleranceMs);require(qc.ok){qc.issues.joinToString("|")}
   p.durationMs=audio;p.scenePlanJson=JSONArray(plan.scenes.map{JSONObject().apply{put("id",it.id);put("startMs",it.startMs);put("endMs",it.endMs);put("visualPath",it.visualPath);put("pdfOrdinal",it.pdfOrdinal);put("motionDirection",it.motionDirection?.name)}}).toString()
   stage(72,"Rendering video");val out=File(store.outputDir(p),p.title.replace(Regex("[^A-Za-z0-9._-]+"),"_").take(48).ifBlank{"AI_Video"}+".mp4")
   VideoRenderer(applicationContext).render(plan.scenes,p.audioPath,p.preferences,out){n->stage(n,"Rendering video")};require(QualityControl.inspectRenderedFile(out,audio,true)==null){"output_validation_failed"};p.outputPath=out.absolutePath;p.state="READY";p.progress=100;store.save(p);stage(100,"Video ready");Result.success()
  }catch(t:Throwable){if(t is CancellationException){p.state="CANCELLED";store.save(p);throw t};p.state="ERROR";p.progress=0;p.lastError=t.message?:t.javaClass.simpleName;store.save(p);Result.failure(workDataOf(KEY_ERROR to (p.lastError?:"generation_failed")))}
 }
 private fun foreground(text:String,n:Int):ForegroundInfo{val id="ai_generation";if(Build.VERSION.SDK_INT>=26)applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id,"Video generation",NotificationManager.IMPORTANCE_LOW));val no=NotificationCompat.Builder(applicationContext,id).setContentTitle("AI Universal Auto Editor Pro").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,n.coerceIn(0,100),false).build();return ForegroundInfo(1001,no,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)}
 companion object{const val KEY_PROJECT_ID="project_id";const val KEY_PROGRESS="progress";const val KEY_STAGE="stage";const val KEY_ERROR="error"}
}
