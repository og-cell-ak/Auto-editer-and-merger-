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
planner = (java_root / "com/futurethinking/aivideodirector/pipeline/TimestampScenePlanner.kt").read_text()
extractor = (java_root / "com/futurethinking/aivideodirector/media/PdfVisualExtractor.kt").read_text()
renderer = (java_root / "com/futurethinking/aivideodirector/media/VideoRenderer.kt").read_text()

assert "pdfpicker" in main and "timestamppdfpicker" in main and "audiopicker" in main
assert "paste your timestamped script" not in main
assert "pdftimestampscriptreader" in worker
for forbidden in [
    "imagepicker",
    "image picker",
    "gallery import",
    "importimages",
    "importtranscript",
    "transcriptpicker",
    "transcript panel",
    "transcript screen",
]:
    assert forbidden not in all_source.lower()

for forbidden in [
    "aivideodirector().plan",
    "imageanalyzer",
    "audioanalyzer",
    "audioalignmentengine",
    "visual-analysis-cache",
]:
    assert forbidden not in worker

assert "timestampscriptparser" in worker
assert "timestampsceneplanner" in worker
assert "orderedPdfVisuals" in planner
assert "pageIndex in 0 until renderer.pageCount" in extractor
assert "setImageDurationMs" in renderer
assert "setDurationUs" in renderer
assert "createBlack" in renderer

print("VALIDATION_OK")
