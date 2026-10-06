package com.futurethinking.aivideodirector.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProjectStore(private val context: Context) {
    private val rootDir=File(context.filesDir,"projects").apply{mkdirs()}
    private val indexFile=File(rootDir,"index.json")
    @Synchronized fun list():List<Project>{
        if(!indexFile.exists()) return emptyList()
        return runCatching{val a=JSONArray(indexFile.readText());buildList{for(i in 0 until a.length())add(fromJson(a.getJSONObject(i)))}.sortedByDescending{it.updatedAt}}.getOrDefault(emptyList())
    }
    @Synchronized fun create(title:String="Untitled Project"):Project=Project(UUID.randomUUID().toString(),title.ifBlank{"Untitled Project"}).also{File(rootDir,it.id+"/assets").mkdirs();save(it)}
    @Synchronized fun save(p:Project){p.updatedAt=System.currentTimeMillis();val cur=list().filterNot{it.id==p.id}.toMutableList();cur.add(p);val a=JSONArray();cur.forEach{a.put(toJson(it))};indexFile.writeText(a.toString())}
    fun projectDir(p:Project)=File(rootDir,p.id).apply{mkdirs()}
    fun assetDir(p:Project)=File(projectDir(p),"assets").apply{mkdirs()}
    fun outputDir(p:Project):File{val b=context.getExternalFilesDir(null)?:context.filesDir;return File(b,"ai-video-exports/"+p.id).apply{mkdirs()}}
    fun importUri(p:Project,uri:Uri,prefix:String):String{
        val r=context.contentResolver;val mime=r.getType(uri).orEmpty();val ext=extensionFor(mime,uri.toString());require(ext!="bin"){"Unsupported file type"}
        val out=File(assetDir(p),prefix+"-"+System.currentTimeMillis()+"."+ext);r.openInputStream(uri).use{input->requireNotNull(input);out.outputStream().use{output->input.copyTo(output,64*1024)}};require(out.length()>0){"Selected file is empty"};return out.absolutePath
    }
    private fun extensionFor(mime:String,raw:String)=when{mime=="application/pdf"||raw.substringBefore('?').lowercase().endsWith(".pdf")->"pdf";mime.contains("wav")||raw.endsWith(".wav")->"wav";mime.contains("m4a")||raw.endsWith(".m4a")->"m4a";mime.contains("aac")||raw.endsWith(".aac")->"aac";mime.contains("ogg")||raw.endsWith(".ogg")->"ogg";mime.contains("mpeg")||raw.endsWith(".mp3")->"mp3";mime.contains("flac")||raw.endsWith(".flac")->"flac";mime.contains("opus")||raw.endsWith(".opus")->"opus";mime.startsWith("video/")||raw.matches(Regex(".*\\.(mp4|mkv|webm|mov)$"))->"mp4";else->"bin"}
    private fun toJson(p:Project)=JSONObject().apply{
        put("id",p.id);put("title",p.title);put("script",p.script);putOpt("timestampPdfPath",p.timestampPdfPath);putOpt("timestampPdfName",p.timestampPdfName)
        putOpt("pdfPath",p.pdfPath);putOpt("pdfName",p.pdfName);put("pdfPageCount",p.pdfPageCount);putOpt("audioPath",p.audioPath);put("visualPaths",JSONArray(p.visualPaths))
        put("createdAt",p.createdAt);put("updatedAt",p.updatedAt);put("durationMs",p.durationMs);putOpt("outputPath",p.outputPath);put("state",p.state);putOpt("lastError",p.lastError);putOpt("scenePlanJson",p.scenePlanJson);put("progress",p.progress);put("isMerged",p.isMerged);putOpt("mergeItemsJson",p.mergeItemsJson)
        put("prefs",JSONObject().apply{put("aspectRatio",p.preferences.aspectRatio.name);put("fps",p.preferences.fps);put("exportFormat",p.preferences.exportFormat)})
    }
    private fun fromJson(j:JSONObject):Project{
        val q=j.optJSONObject("prefs")?:JSONObject();val visuals=mutableListOf<String>();val va=j.optJSONArray("visualPaths")?:JSONArray();for(i in 0 until va.length())va.optString(i).takeIf{it.isNotBlank()}?.let(visuals::add)
        val aspect=runCatching{enumValueOf<Enums.AspectRatio>(q.optString("aspectRatio"))}.getOrDefault(Enums.AspectRatio.WIDE_16_9)
        return Project(j.optString("id",UUID.randomUUID().toString()),j.optString("title","Untitled Project"),j.optString("script"),
            j.optString("timestampPdfPath").ifBlank{null},j.optString("timestampPdfName").ifBlank{null},j.optString("pdfPath").ifBlank{null},j.optString("pdfName").ifBlank{null},j.optInt("pdfPageCount"),
            j.optString("audioPath").ifBlank{null},visuals,AppPreferences(aspect,Enums.Resolution.FHD_1080,q.optInt("fps",30).coerceIn(24,60),q.optString("exportFormat","mp4")),
            j.optLong("createdAt",System.currentTimeMillis()),j.optLong("updatedAt",System.currentTimeMillis()),j.optLong("durationMs"),j.optString("outputPath").ifBlank{null},
            j.optString("state","DRAFT"),j.optString("lastError").ifBlank{null},j.optString("scenePlanJson").ifBlank{null},j.optInt("progress"),j.optBoolean("isMerged"),j.optString("mergeItemsJson").ifBlank{null})
    }
}
