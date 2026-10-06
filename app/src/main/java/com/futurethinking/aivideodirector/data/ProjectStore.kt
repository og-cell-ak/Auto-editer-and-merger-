package com.futurethinking.aivideodirector.data

import android.content.Context
import android.net.Uri
import androidx.core.util.AtomicFile
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProjectStore(private val context: Context) {
    private val rootDir=File(context.filesDir,"projects").apply{mkdirs()}
    private val indexFile=File(rootDir,"index.json")

    fun list():List<Project> = synchronized(ProjectStore::class.java) {
        if(!indexFile.exists()) return@synchronized emptyList()
        return@synchronized runCatching {
            val text = AtomicFile(indexFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val a=JSONArray(text)
            buildList{for(i in 0 until a.length())add(fromJson(a.getJSONObject(i)))}
                .also { migrateGenericProjectNames(it) }
                .sortedByDescending{it.updatedAt}
        }.getOrDefault(emptyList())
    }

    @Synchronized fun create(title:String?=null):Project {
        val existing=readIndex()
        val normal=existing.filterNot{it.isMerged}
        val ordinal=(normal.size+1).coerceAtMost(10)
        val finalTitle=title?.takeIf{it.isNotBlank()} ?: projectOrdinalName(ordinal)
        return Project(UUID.randomUUID().toString(),finalTitle).also{
            it.queueRank=(existing.maxOfOrNull{p->p.queueRank}?:System.currentTimeMillis())+1L
            File(rootDir,it.id+"/assets").mkdirs()
            save(it)
        }
    }

    private fun projectOrdinalName(n:Int)=when(n){
        1->"First Project";2->"Second Project";3->"Third Project";4->"Fourth Project";5->"Fifth Project"
        6->"Sixth Project";7->"Seventh Project";8->"Eighth Project";9->"Ninth Project";else->"Tenth Project"
    }

    private fun migrateGenericProjectNames(projects:List<Project>){
        val normal=projects.filterNot{it.isMerged}.sortedWith(compareBy<Project>{it.createdAt}.thenBy{it.id})
        var changed=false
        normal.forEachIndexed{index,p->
            if(p.title.isBlank() || p.title.equals("Untitled Project",true) ||
                p.title.equals("Unknown Project",true) || p.title.equals("Unknown object",true)){
                p.title=projectOrdinalName(index+1)
                p.updatedAt=System.currentTimeMillis()
                changed=true
            }
        }
        if(changed){
            val out=JSONArray()
            projects.forEach{out.put(toJson(it))}
            writeIndexAtomically(out.toString())
        }
    }

    fun save(p:Project){
        synchronized(ProjectStore::class.java){
            p.updatedAt=System.currentTimeMillis()
            val cur=readIndex().filterNot{it.id==p.id}.toMutableList()
            cur.add(p)
            val a=JSONArray()
            cur.forEach{a.put(toJson(it))}
            writeIndexAtomically(a.toString())
        }
    }

    private fun readIndex():List<Project>{
        if(!indexFile.exists()) return emptyList()
        return runCatching{
            if(!indexFile.exists()) return@runCatching emptyList()
            val text = AtomicFile(indexFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val a=JSONArray(text)
            buildList{for(i in 0 until a.length())add(fromJson(a.getJSONObject(i)))}
        }.getOrDefault(emptyList())
    }

    private fun writeIndexAtomically(text:String){
        val atomic=AtomicFile(indexFile)
        var stream:FileOutputStream?=null
        try{
            stream=atomic.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
            atomic.finishWrite(stream)
        }catch(t:Throwable){
            if(stream != null) atomic.failWrite(stream)
            throw t
        }
    }

    fun projectDir(p:Project)=File(rootDir,p.id).apply{mkdirs()}
    fun assetDir(p:Project)=File(projectDir(p),"assets").apply{mkdirs()}
    fun outputDir(p:Project):File{
        val b=context.getExternalFilesDir(null)?:context.filesDir
        return File(b,"ai-video-exports/"+p.id).apply{mkdirs()}
    }

    fun availableStorageBytes():Long{
        val stat=android.os.StatFs((context.getExternalFilesDir(null)?:context.filesDir).absolutePath)
        return stat.availableBytes
    }

    fun importUri(p:Project,uri:Uri,prefix:String):String{
        val r=context.contentResolver
        val mime=r.getType(uri).orEmpty()
        val ext=extensionFor(mime,uri.toString())
        require(ext!="bin"){"Unsupported file type"}
        val out=File(assetDir(p),prefix+"-"+System.currentTimeMillis()+"."+ext)
        r.openInputStream(uri).use{input->
            requireNotNull(input)
            out.outputStream().use{output->input.copyTo(output,64*1024)}
        }
        require(out.length()>0){"Selected file is empty"}
        return out.absolutePath
    }

    private fun extensionFor(mime:String,raw:String)=when{
        mime=="application/pdf"||raw.substringBefore('?').lowercase().endsWith(".pdf")->"pdf"
        mime.contains("wav")||raw.endsWith(".wav")->"wav"
        mime.contains("m4a")||raw.endsWith(".m4a")->"m4a"
        mime.contains("aac")||raw.endsWith(".aac")->"aac"
        mime.contains("ogg")||raw.endsWith(".ogg")->"ogg"
        mime.contains("mpeg")||raw.endsWith(".mp3")->"mp3"
        mime.contains("flac")||raw.endsWith(".flac")->"flac"
        mime.contains("opus")||raw.endsWith(".opus")->"opus"
        mime.startsWith("video/")||raw.matches(Regex(".*\\.(mp4|mkv|webm|mov)$"))->"mp4"
        else->"bin"
    }

    private fun toJson(p:Project)=JSONObject().apply{
        put("id",p.id);put("title",p.title);put("script",p.script)
        putOpt("timestampPdfPath",p.timestampPdfPath);putOpt("timestampPdfName",p.timestampPdfName)
        putOpt("pdfPath",p.pdfPath);putOpt("pdfName",p.pdfName);put("pdfPageCount",p.pdfPageCount)
        putOpt("audioPath",p.audioPath);put("visualPaths",JSONArray(p.visualPaths))
        put("createdAt",p.createdAt);put("updatedAt",p.updatedAt);put("durationMs",p.durationMs)
        putOpt("outputPath",p.outputPath);put("state",p.state);putOpt("lastError",p.lastError)
        putOpt("scenePlanJson",p.scenePlanJson);put("progress",p.progress);put("isMerged",p.isMerged)
        putOpt("mergeItemsJson",p.mergeItemsJson);putOpt("analysisReport",p.analysisReport)
        put("progressStage",p.progressStage);put("queueRank",p.queueRank)
        put("prefs",JSONObject().apply{
            put("aspectRatio",p.preferences.aspectRatio.name);put("fps",p.preferences.fps);put("exportFormat",p.preferences.exportFormat)
        })
    }

    private fun fromJson(j:JSONObject):Project{
        val q=j.optJSONObject("prefs")?:JSONObject()
        val visuals=mutableListOf<String>()
        val va=j.optJSONArray("visualPaths")?:JSONArray()
        for(i in 0 until va.length())va.optString(i).takeIf{it.isNotBlank()}?.let(visuals::add)
        val aspect=runCatching{enumValueOf<Enums.AspectRatio>(q.optString("aspectRatio"))}.getOrDefault(Enums.AspectRatio.WIDE_16_9)
        return Project(
            j.optString("id",UUID.randomUUID().toString()),j.optString("title","Untitled Project"),j.optString("script"),
            j.optString("timestampPdfPath").ifBlank{null},j.optString("timestampPdfName").ifBlank{null},
            j.optString("pdfPath").ifBlank{null},j.optString("pdfName").ifBlank{null},j.optInt("pdfPageCount"),
            j.optString("audioPath").ifBlank{null},visuals,
            AppPreferences(aspect,Enums.Resolution.FHD_1080,q.optInt("fps",30).coerceIn(24,60),q.optString("exportFormat","mp4")),
            j.optLong("createdAt",System.currentTimeMillis()),j.optLong("updatedAt",System.currentTimeMillis()),
            j.optLong("durationMs"),j.optString("outputPath").ifBlank{null},j.optString("state","DRAFT"),
            j.optString("lastError").ifBlank{null},j.optString("scenePlanJson").ifBlank{null},j.optInt("progress"),
            j.optBoolean("isMerged"),j.optString("mergeItemsJson").ifBlank{null},
            j.optString("analysisReport").ifBlank{null},j.optString("progressStage",""),j.optLong("queueRank",j.optLong("createdAt",System.currentTimeMillis()))
        )
    }
}
