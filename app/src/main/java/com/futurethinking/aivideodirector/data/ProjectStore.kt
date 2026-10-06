package com.futurethinking.aivideodirector.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProjectStore(private val context: Context) {
    private val rootDir = File(context.filesDir, "projects").apply { mkdirs() }
    private val indexFile = File(rootDir, "index.json")

    @Synchronized fun list(): List<Project> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until arr.length()) add(fromJson(arr.getJSONObject(i)))
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun create(title: String = "Untitled Project"): Project {
        val id = UUID.randomUUID().toString()
        File(rootDir, id + "/assets").mkdirs()
        return Project(
            id = id,
            title = title.ifBlank { "Untitled Project" }
        ).also(::save)
    }

    @Synchronized fun save(project: Project) {
        project.updatedAt = System.currentTimeMillis()
        val current = list().filterNot { it.id == project.id }.toMutableList()
        current.add(project)
        val arr = JSONArray()
        current.forEach { arr.put(toJson(it)) }
        indexFile.writeText(arr.toString())
    }

    fun projectDir(project: Project): File =
        File(rootDir, project.id).apply { mkdirs() }

    fun assetDir(project: Project): File =
        File(projectDir(project), "assets").apply { mkdirs() }

    fun outputDir(project: Project): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "ai-video-exports/" + project.id).apply { mkdirs() }
    }

    fun importUri(project: Project, uri: Uri, prefix: String): String {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val ext = extensionFor(mime, uri.toString())
        require(ext != "bin") { "Unsupported file type" }

        val safeName = prefix + "-" + System.currentTimeMillis() + "." + ext
        val out = File(assetDir(project), safeName)

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read selected file" }
            out.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }

        require(out.length() > 0L) { "Selected file is empty" }
        return out.absolutePath
    }

    private fun extensionFor(mime: String, raw: String): String {
        val lower = raw.substringBefore('?').lowercase()
        return when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> "pdf"
            mime.contains("wav") || lower.endsWith(".wav") -> "wav"
            mime.contains("m4a") || lower.endsWith(".m4a") -> "m4a"
            mime.contains("aac") || lower.endsWith(".aac") -> "aac"
            mime.contains("ogg") || lower.endsWith(".ogg") -> "ogg"
            mime.contains("mpeg") || lower.endsWith(".mp3") -> "mp3"
            mime.contains("flac") || lower.endsWith(".flac") -> "flac"
            mime.contains("opus") || lower.endsWith(".opus") -> "opus"
            else -> "bin"
        }
    }

    private fun toJson(project: Project): JSONObject = JSONObject().apply {
        put("id", project.id)
        put("title", project.title)
        put("script", project.script)
        putOpt("timestampPdfPath", project.timestampPdfPath)
        putOpt("timestampPdfName", project.timestampPdfName)
        putOpt("pdfPath", project.pdfPath)
        putOpt("pdfName", project.pdfName)
        put("pdfPageCount", project.pdfPageCount)
        putOpt("audioPath", project.audioPath)
        put("visualPaths", JSONArray(project.visualPaths))
        put("createdAt", project.createdAt)
        put("updatedAt", project.updatedAt)
        put("durationMs", project.durationMs)
        putOpt("outputPath", project.outputPath)
        put("state", project.state)
        putOpt("lastError", project.lastError)
        putOpt("scenePlanJson", project.scenePlanJson)
        put("prefs", JSONObject().apply {
            put("aspectRatio", project.preferences.aspectRatio.name)
            put("fps", project.preferences.fps)
            put("exportFormat", project.preferences.exportFormat)
        })
    }

    private fun fromJson(json: JSONObject): Project {
        val prefs = json.optJSONObject("prefs") ?: JSONObject()
        val visuals = mutableListOf<String>()
        val visualArray = json.optJSONArray("visualPaths") ?: JSONArray()
        for (i in 0 until visualArray.length()) {
            visualArray.optString(i).takeIf { it.isNotBlank() }?.let(visuals::add)
        }

        val aspect = runCatching {
            enumValueOf<Enums.AspectRatio>(prefs.optString("aspectRatio"))
        }.getOrDefault(Enums.AspectRatio.WIDE_16_9)

        return Project(
            id = json.optString("id", UUID.randomUUID().toString()),
            title = json.optString("title", "Untitled Project"),
            script = json.optString("script"),
            timestampPdfPath = json.optString("timestampPdfPath").ifBlank { null },
            timestampPdfName = json.optString("timestampPdfName").ifBlank { null },
            pdfPath = json.optString("pdfPath").ifBlank { null },
            pdfName = json.optString("pdfName").ifBlank { null },
            pdfPageCount = json.optInt("pdfPageCount", 0),
            audioPath = json.optString("audioPath").ifBlank { null },
            visualPaths = visuals,
            preferences = AppPreferences(
                aspectRatio = aspect,
                fps = prefs.optInt("fps", 30).coerceIn(24, 60),
                exportFormat = prefs.optString("exportFormat", "mp4")
            ),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            durationMs = json.optLong("durationMs", 0L),
            outputPath = json.optString("outputPath").ifBlank { null },
            state = json.optString("state", "DRAFT"),
            lastError = json.optString("lastError").ifBlank { null },
            scenePlanJson = json.optString("scenePlanJson").ifBlank { null }
        )
    }
}
