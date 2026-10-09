from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "README.md",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/futurethinking/aivideodirector/MainActivity.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/data/Models.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/data/ProjectStore.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/media/PdfVisualExtractor.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/media/SceneFrameFactory.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/media/VideoRenderer.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/media/SpecialPanelScrollDetector.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/media/MediaExportGate.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/work/QueueRecoveryReceiver.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/pipeline/AudioDurationReader.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/pipeline/TimestampScriptParser.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/pipeline/PdfTimestampScriptReader.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/pipeline/TimestampScenePlanner.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/ui/MainViewModel.kt",
    "app/src/main/java/com/futurethinking/aivideodirector/work/GenerationWorker.kt",
    "app/src/test/java/com/futurethinking/aivideodirector/TimestampPipelineTest.kt",
]
missing = [p for p in required if not (root / p).exists()]
if missing:
    print("MISSING", *missing, sep="\n")
    sys.exit(1)

java_root = root / "app/src/main/java"
all_source = ""
for p in java_root.rglob("*.kt"):
    all_source += "\n" + p.read_text()

main = (java_root / "com/futurethinking/aivideodirector/MainActivity.kt").read_text().lower()
vm = (java_root / "com/futurethinking/aivideodirector/ui/MainViewModel.kt").read_text().lower()
worker = (java_root / "com/futurethinking/aivideodirector/work/GenerationWorker.kt").read_text().lower()
recovery = (java_root / "com/futurethinking/aivideodirector/work/QueueRecoveryReceiver.kt").read_text().lower()
planner = (java_root / "com/futurethinking/aivideodirector/pipeline/TimestampScenePlanner.kt").read_text()
extractor = (java_root / "com/futurethinking/aivideodirector/media/PdfVisualExtractor.kt").read_text()
renderer = (java_root / "com/futurethinking/aivideodirector/media/VideoRenderer.kt").read_text()
planner_lower = planner.lower()
extractor_lower = extractor.lower()
renderer_lower = renderer.lower()
store = (java_root / "com/futurethinking/aivideodirector/data/ProjectStore.kt").read_text().lower()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text().lower()
gradle = (root / "app/build.gradle.kts").read_text().lower()

assert "opendocument" in main and "importpdf" in vm and "importtimestamppdf" in vm and "importaudio" in vm
assert "paste your timestamped script" not in main
assert "pdftimestampscriptreader" in worker
for forbidden in [
    "imagepicker","image picker","gallery import","importimages",
    "importtranscript","transcriptpicker","transcript panel","transcript screen",
]:
    assert forbidden not in all_source.lower()

for forbidden in [
    "aivideodirector().plan","imageanalyzer","audioanalyzer",
    "audioalignmentengine","visual-analysis-cache",
]:
    assert forbidden not in worker

assert "timestampscriptparser" in worker
assert "timestampsceneplanner" in worker
assert "orderedpdfvisuals" in planner_lower
assert "pageindex in 0 until renderer.pagecount" in extractor_lower
assert "setimagedurationms" in renderer_lower
assert "setdurationus" in renderer_lower
assert "createblack" in renderer_lower
assert "atomicfile" in store
assert "synchronized(projectstore::class.java)" in store
assert "editor-and-merger-media-queue" in vm
assert "append_or_replace" in vm
assert "queue_version" in vm
assert "key_result_state" in worker
assert 'key_result_state to "error"' in worker
assert 'android:label="editor"' in manifest

# Merge stability: use Media3 Transformer only. No raw MediaMuxer shortcut.
# The rendering app must not contain an active merger UI or merger worker.
assert "mergeworker" not in all_source.lower()
assert "mergeworker" not in main
assert "mergevideos" not in main
assert "importmergevideo" not in vm
assert "mergeselection" not in vm
assert "mergeitemsjson" in store  # legacy data remains parseable, but is not executable behavior.
assert "mediaexportgate.withlock" in renderer.lower()
assert "setforeground(foreground" in worker
assert "savetophonemovies" in worker
assert "mediastore.video.media.external_content_uri" in worker
assert "movies/editor" in worker
assert "result.retry()" in worker

print("VALIDATION_OK")

# Core visual and stability invariants: do not change without an intentional product decision.
assert "availablestoragebytes" in store
assert "progressstage" in store
assert "setrequiresstoragenotlow" in vm
assert "mediaexportgate" in (java_root / "com/futurethinking/aivideodirector/media/MediaExportGate.kt").read_text().lower()
assert "mediaexportgate.withlock" in renderer.lower()
assert "inspectrenderedfile" in worker
assert "queuerecoveryreceiver" in manifest
assert "receive_boot_completed" in manifest
assert '<string name="app_name">EDITOR</string>' in (root / "app/src/main/res/values/strings.xml").read_text()
assert "mincoverage = 0.55f" in extractor.lower()
assert "r >= 170" in extractor.lower() and "g >= 125" in extractor.lower() and "b <= 155" in extractor.lower()
assert "scaledsize(page.width, page.height, 1920)" in extractor.lower()
assert "buildsubtlepan" not in renderer_lower
assert "buildfixedzoomscroll" in renderer_lower
assert "if (hastext || hasmarker) 1.5f else 1.2f" in renderer_lower
assert "setscale(scale, scale)" in renderer_lower
assert "if (hastext || hasmarker) 1.5f else 1.2f" in renderer_lower
assert "containsanytext" in renderer_lower
assert "specialpanelscrolldetector" in renderer_lower
assert "containstargettext" in renderer_lower
assert "manhwa talks 007" in all_source.lower()
assert "setscale(scale, scale)" in renderer_lower
assert "verticaltranslation" in renderer_lower
assert "max_mismatch_ms = 5000l" in planner.lower()
assert "timestamps must be strictly increasing" in (java_root / "com/futurethinking/aivideodirector/pipeline/TimestampScriptParser.kt").read_text().lower()

assert "queuerank" in store
assert "rendernow" in vm and "moveup" in vm and "movedown" in vm
assert "togglepause" in vm and "cancelproject" in vm
assert "post_notifications" in manifest
assert "request_ignore_battery_optimizations" in manifest
assert 'android:stopwithtask="false"' in manifest
assert "requestpermission" not in main
assert "showbackgroundpermissionnotice" not in vm
assert "first project" in store and "tenth project" in store
assert "migrategenericprojectnames" in store
assert "work-multiprocess" not in gradle
assert "remoteworkerservice" not in manifest
assert 'android:process=":media"' not in manifest
assert "remotelistenabledelegatingworker" not in vm
assert "workmanager.getinstance" in recovery
assert "runcatching" in recovery
assert "merging" not in recovery
assert 'queue_version=6' in vm
