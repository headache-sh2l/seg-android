package com.example.segandroid.segmenter

import android.graphics.Bitmap

/**
 * A single measured iteration of a benchmark run.
 *
 * Deliberately holds only lightweight metadata: per-iteration output bitmaps are NOT
 * retained, since a single full-resolution result can be tens of megabytes (retaining
 * every iteration would exhaust memory within a few hundred runs).
 *
 * @property runIndex The zero-based index of this iteration.
 * @property runtimeMs Wall-clock runtime for this iteration in milliseconds.
 * @property memoryBytes Additional memory allocated during this iteration (bytes, clamped to >= 0).
 * @property error Failure message if this iteration failed, otherwise null.
 */
data class BenchmarkSample(
    val runIndex: Int,
    val runtimeMs: Long,
    val memoryBytes: Long,
    val error: String? = null
)

/**
 * Live progress emitted while a benchmark session is running.
 *
 * @property engineIndex Zero-based index of the engine currently being measured.
 * @property engineCount Total number of engines in the session.
 * @property engineName Display name of the engine currently being measured.
 * @property completedIterations Iterations completed for the current engine.
 * @property iterationCount Total iterations requested per engine.
 */
data class BenchmarkProgress(
    val engineIndex: Int,
    val engineCount: Int,
    val engineName: String,
    val completedIterations: Int,
    val iterationCount: Int
) {
    /** Overall progress across the whole session, in the range 0f..1f. */
    val fraction: Float
        get() {
            if (engineCount == 0 || iterationCount == 0) return 0f
            val withinEngine = completedIterations.toFloat() / iterationCount
            return ((engineIndex + withinEngine) / engineCount).coerceIn(0f, 1f)
        }
}

/**
 * Aggregated statistics for a single model across all iterations.
 */
data class BenchmarkStats(
    val engineId: String,
    val engineName: String,
    val iterations: Int,
    val successCount: Int,
    val failureCount: Int,

    // Runtime statistics (ms)
    val avgRuntimeMs: Double,
    val minRuntimeMs: Long,
    val maxRuntimeMs: Long,
    val stdDevRuntimeMs: Double,
    val p50RuntimeMs: Double,
    val p95RuntimeMs: Double,
    val p99RuntimeMs: Double,

    // Cost-to-run statistics (bytes)
    val avgMemoryBytes: Double,
    val peakMemoryBytes: Long,

    // The first retained output for manual validation, plus where it was saved on device.
    val sampleResultBitmap: Bitmap? = null,
    val sampleSavedPath: String? = null,

    val samples: List<BenchmarkSample> = emptyList()
)

/**
 * The full report produced by one benchmark session across one or more models.
 *
 * @property stopped True if the user stopped the session before all iterations completed.
 */
data class BenchmarkReport(
    val timestamp: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val totalDurationMs: Long,
    val stats: List<BenchmarkStats>,
    val reportSavedPath: String? = null,
    val stopped: Boolean = false
)
