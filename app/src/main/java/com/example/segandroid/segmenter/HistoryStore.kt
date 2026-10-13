package com.example.segandroid.segmenter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream

/**
 * The kind of run that produced a retained history entry.
 */
enum class RunType { BENCHMARK, SEGMENT }

/** Compact per-model summary shown directly on a benchmark history card. */
data class ModelSummary(
    val engineName: String,
    val avgRuntimeMs: Double,
    val iterations: Int
)

/**
 * One retained run shown in the in-app history.
 *
 * @property runId Epoch-millis identifier, unique per run, used for ordering/pruning.
 * @property thumbnailPath Path to the representative (first model / the single) thumbnail.
 * @property csvPath Path to the CSV report — benchmark runs only.
 * @property segmentTimeMs Time taken, for a single segmentation run.
 * @property segmentEngineName Engine display name, for a single segmentation run.
 * @property models Per-model summaries, for a benchmark run.
 */
data class RunHistoryEntry(
    val type: RunType,
    val runId: Long,
    val thumbnailPath: String?,
    val csvPath: String?,
    val segmentTimeMs: Long? = null,
    val segmentEngineName: String? = null,
    val models: List<ModelSummary> = emptyList()
) {
    val engineNames: List<String>
        get() = when (type) {
            RunType.SEGMENT -> listOfNotNull(segmentEngineName)
            RunType.BENCHMARK -> models.map { it.engineName }
        }
}

/** One model's retained result inside a benchmark run (used by the detail screen). */
data class BenchmarkModelResult(
    val stats: BenchmarkStats,
    val thumbnail: Bitmap?
)

/** A benchmark run loaded on demand with its per-model statistics and thumbnails. */
data class BenchmarkRunDetail(
    val runId: Long,
    val csvPath: String,
    val models: List<BenchmarkModelResult>
)

/** A segment run loaded on demand. */
data class SegmentRunDetail(
    val runId: Long,
    val engineName: String,
    val timeTakenMs: Long,
    val thumbnail: Bitmap?
)

/** The downscaled bitmap plus the path it was written to (either may be absent on failure). */
data class SavedThumbnail(val bitmap: Bitmap, val path: String?)

/**
 * Owns all on-device persistence for the segmenter feature.
 *
 * Layout (app-specific external storage, `<externalFilesDir>/Pictures/segmenter_runs/`):
 *  - `bench_<runId>.csv`                     — a benchmark session report (the run marker)
 *  - `bench_<runId>_<index>_<engine>.png`    — retained sample per benchmarked model
 *  - `seg_<runId>_<durationMs>_<engine>.png` — thumbnail + duration per single segmentation
 *
 * Retention: the last [MAX_BENCHMARK_RUNS] benchmark sessions and the last
 * [MAX_SEGMENT_RUNS] single segmentations are kept; older runs are deleted by [prune].
 * Thumbnails are downscaled to at most [THUMBNAIL_MAX_DIM] px so a full history stays small.
 */
class HistoryStore(private val context: Context) {

    companion object {
        private const val TAG = "HistoryStore"
        private const val DIR_NAME = "segmenter_runs"
        private const val LEGACY_DIR_NAME = "segmenter_benchmarks"
        private const val MAX_BENCHMARK_RUNS = 10
        private const val MAX_SEGMENT_RUNS = 20
        private const val THUMBNAIL_MAX_DIM = 512
        private const val THUMBNAIL_QUALITY = 85

        private const val BENCH_PREFIX = "bench_"
        private const val SEG_PREFIX = "seg_"

        private val BENCH_CSV = Regex("^bench_\\d+\\.csv$")
        private val BENCH_SAMPLE_PNG = Regex("^bench_\\d+_\\d+_.+\\.png$")
        private val SEG_RUN_PNG = Regex("^seg_\\d+_\\d+_.+\\.png$")
    }

    init {
        removeLegacyDir()
        removeUnrecognizedFiles()
    }

    private fun rootDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    /** Deletes the pre-retention folder layout used by earlier builds, if present. */
    private fun removeLegacyDir() {
        try {
            File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), LEGACY_DIR_NAME)
                .takeIf { it.exists() }
                ?.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove legacy dir", e)
        }
    }

    /**
     * Drops files left behind by earlier naming schemes so history never shows entries it
     * cannot parse.
     */
    private fun removeUnrecognizedFiles() {
        try {
            rootDir().listFiles()?.forEach { file ->
                val name = file.name
                val recognized = BENCH_CSV.matches(name) ||
                    BENCH_SAMPLE_PNG.matches(name) ||
                    SEG_RUN_PNG.matches(name)
                if (!recognized) file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up unrecognized files", e)
        }
    }

    /** Allocates a run id that does not collide with anything already on disk. */
    fun newRunId(): Long {
        val existing = rootDir().listFiles()?.map { it.name } ?: emptyList()
        var id = System.currentTimeMillis()
        while (existing.any { it.startsWith("$BENCH_PREFIX$id") || it.startsWith("${SEG_PREFIX}${id}_") }) {
            id++
        }
        return id
    }

    // ---------------------------------------------------------------- writes

    /**
     * Writes one benchmarked model's sample. [bitmap] is downscaled; the returned
     * [SavedThumbnail.bitmap] is the small version the caller should keep for display.
     */
    fun saveBenchmarkSample(
        runId: Long,
        modelIndex: Int,
        engineName: String,
        bitmap: Bitmap
    ): SavedThumbnail {
        val thumbnail = downscale(bitmap)
        val file = File(rootDir(), "$BENCH_PREFIX${runId}_${modelIndex}_${sanitize(engineName)}.png")
        return SavedThumbnail(thumbnail, writeThumbnail(file, thumbnail))
    }

    /** Writes the CSV report for a benchmark session and prunes old runs. */
    fun saveBenchmarkReport(runId: Long, csv: String): String? {
        return try {
            val file = File(rootDir(), "$BENCH_PREFIX$runId.csv")
            file.writeText(csv)
            prune()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save benchmark report", e)
            null
        }
    }

    /**
     * Writes a single segmentation run's thumbnail (downscaled, duration encoded in the name)
     * and prunes old runs. Returns the thumbnail path.
     */
    fun saveSegmentRun(engineName: String, timeTakenMs: Long, bitmap: Bitmap): String? {
        val runId = newRunId()
        val thumbnail = downscale(bitmap)
        val file = File(rootDir(), "${SEG_PREFIX}${runId}_${timeTakenMs}_${sanitize(engineName)}.png")
        val path = writeThumbnail(file, thumbnail)
        if (thumbnail !== bitmap && !thumbnail.isRecycled) thumbnail.recycle()
        prune()
        return path
    }

    // ---------------------------------------------------------------- reads

    /** All retained runs, newest first, with just enough data to render a card. */
    fun listHistory(): List<RunHistoryEntry> {
        val files = rootDir().listFiles() ?: return emptyList()
        val entries = mutableListOf<RunHistoryEntry>()

        // A benchmark run is marked by its CSV; samples hang off the same run id.
        files.asSequence()
            .filter { it.name.startsWith(BENCH_PREFIX) && it.name.endsWith(".csv") }
            .forEach { csv ->
                val runId = csv.name.removePrefix(BENCH_PREFIX).removeSuffix(".csv").toLongOrNull()
                    ?: return@forEach
                val summaries = runCatching { parseCsv(csv.readText()) }
                    .getOrDefault(emptyList())
                    .map { ModelSummary(it.engineName, it.avgRuntimeMs, it.iterations) }
                entries += RunHistoryEntry(
                    type = RunType.BENCHMARK,
                    runId = runId,
                    thumbnailPath = samplesFor(files, runId).firstOrNull()?.absolutePath,
                    csvPath = csv.absolutePath,
                    models = summaries
                )
            }

        // Each single-segmentation thumbnail is its own run.
        files.asSequence()
            .filter { it.name.startsWith(SEG_PREFIX) && it.name.endsWith(".png") }
            .forEach { png ->
                val parts = png.name.removePrefix(SEG_PREFIX).removeSuffix(".png").split("_", limit = 3)
                if (parts.size < 2) return@forEach
                val runId = parts[0].toLongOrNull() ?: return@forEach
                val duration = parts[1].toLongOrNull() ?: 0L
                val engineName = parts.getOrNull(2).orEmpty().ifEmpty { "unknown" }
                entries += RunHistoryEntry(
                    type = RunType.SEGMENT,
                    runId = runId,
                    thumbnailPath = png.absolutePath,
                    csvPath = null,
                    segmentTimeMs = duration,
                    segmentEngineName = engineName
                )
            }

        return entries.sortedByDescending { it.runId }
    }

    /** Loads a benchmark run's per-model statistics plus each model's thumbnail. */
    fun loadBenchmarkRun(runId: Long): BenchmarkRunDetail? {
        val csv = File(rootDir(), "$BENCH_PREFIX$runId.csv").takeIf { it.exists() } ?: return null
        val stats = runCatching { parseCsv(csv.readText()) }.getOrDefault(emptyList())
        val samples = samplesFor(rootDir().listFiles() ?: emptyArray(), runId)

        val models = stats.mapIndexed { index, modelStats ->
            val thumbnailFile = samples.firstOrNull { file ->
                // Name shape: bench_<runId>_<index>_<engine>.png
                file.name.removePrefix("$BENCH_PREFIX${runId}_").substringBefore("_").toIntOrNull() == index
            }
            val bitmap = thumbnailFile?.absolutePath?.let(::decodeThumbnail)
            BenchmarkModelResult(
                stats = modelStats.copy(
                    sampleResultBitmap = bitmap,
                    sampleSavedPath = thumbnailFile?.absolutePath
                ),
                thumbnail = bitmap
            )
        }
        return BenchmarkRunDetail(runId = runId, csvPath = csv.absolutePath, models = models)
    }

    /** Loads a single segmentation run. */
    fun loadSegmentRun(entry: RunHistoryEntry): SegmentRunDetail? {
        val path = entry.thumbnailPath ?: return null
        return SegmentRunDetail(
            runId = entry.runId,
            engineName = entry.segmentEngineName ?: entry.engineNames.firstOrNull() ?: "unknown",
            timeTakenMs = entry.segmentTimeMs ?: 0L,
            thumbnail = decodeThumbnail(path)
        )
    }

    fun clearAll() {
        rootDir().listFiles()?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- pruning

    /** Enforces the retention policy: last [MAX_BENCHMARK_RUNS] and [MAX_SEGMENT_RUNS] runs. */
    fun prune() {
        pruneBenchmarkRuns()
        pruneSegmentRuns()
    }

    private fun pruneBenchmarkRuns() {
        val runIds = rootDir().listFiles()
            ?.filter { it.name.startsWith(BENCH_PREFIX) && it.name.endsWith(".csv") }
            ?.mapNotNull { it.name.removePrefix(BENCH_PREFIX).removeSuffix(".csv").toLongOrNull() }
            ?: return
        runIds.sortedDescending().drop(MAX_BENCHMARK_RUNS).forEach { deleteRun(BENCH_PREFIX, it) }
    }

    private fun pruneSegmentRuns() {
        val runIds = rootDir().listFiles()
            ?.filter { it.name.startsWith(SEG_PREFIX) && it.name.endsWith(".png") }
            ?.mapNotNull { it.name.removePrefix(SEG_PREFIX).substringBefore('_').toLongOrNull() }
            ?.distinct()
            ?: return
        runIds.sortedDescending().drop(MAX_SEGMENT_RUNS).forEach { deleteRun(SEG_PREFIX, it) }
    }

    /**
     * Deletes every file belonging to a run. Matching is anchored on the separator so that
     * run 1000 is never confused with run 100 (which is a prefix of it).
     */
    private fun deleteRun(prefix: String, runId: Long) {
        rootDir().listFiles()?.forEach { file ->
            val name = file.name
            val belongsToRun = name.startsWith("$prefix$runId.") || name.startsWith("$prefix${runId}_")
            if (belongsToRun) file.delete()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun samplesFor(files: Array<File>, runId: Long): List<File> =
        files.filter { it.name.startsWith("$BENCH_PREFIX${runId}_") && it.name.endsWith(".png") }
            .sortedBy { it.name.substringBeforeLast('_').substringAfterLast('_').toIntOrNull() ?: 0 }

    private fun decodeThumbnail(path: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    private fun writeThumbnail(file: File, bitmap: Bitmap): String? {
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, THUMBNAIL_QUALITY, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write thumbnail ${file.name}", e)
            null
        }
    }

    /** Returns [src] itself when it already fits, otherwise a new smaller bitmap. */
    private fun downscale(src: Bitmap, maxDim: Int = THUMBNAIL_MAX_DIM): Bitmap {
        val longestSide = maxOf(src.width, src.height)
        if (longestSide <= maxDim || longestSide == 0) return src
        val scale = maxDim.toFloat() / longestSide
        val width = (src.width * scale).toInt().coerceAtLeast(1)
        val height = (src.height * scale).toInt().coerceAtLeast(1)
        return src.scale(width, height)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /**
     * Rebuilds per-model statistics from a stored CSV report, so historical runs can be
     * rendered with exactly the same cards as a live run.
     */
    private fun parseCsv(text: String): List<BenchmarkStats> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 13) return@mapNotNull null
            val avgMemoryKb = c[8].toDoubleOrNull() ?: 0.0
            val peakMemoryKb = c[9].toLongOrNull() ?: 0L
            BenchmarkStats(
                engineId = c[0],
                engineName = c[0],
                iterations = c[12].toIntOrNull() ?: 0,
                successCount = c[10].toIntOrNull() ?: 0,
                failureCount = c[11].toIntOrNull() ?: 0,
                avgRuntimeMs = c[1].toDoubleOrNull() ?: 0.0,
                minRuntimeMs = c[2].toLongOrNull() ?: 0L,
                maxRuntimeMs = c[3].toLongOrNull() ?: 0L,
                stdDevRuntimeMs = c[4].toDoubleOrNull() ?: 0.0,
                p50RuntimeMs = c[5].toDoubleOrNull() ?: 0.0,
                p95RuntimeMs = c[6].toDoubleOrNull() ?: 0.0,
                p99RuntimeMs = c[7].toDoubleOrNull() ?: 0.0,
                avgMemoryBytes = avgMemoryKb * 1024.0,
                peakMemoryBytes = peakMemoryKb * 1024L
            )
        }
    }
}
