package com.example.segandroid.segmenter

import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Runs benchmarking sessions across one or more [Segmenter] engines.
 *
 * For each engine it performs a warm-up pass, then [generateReport] measured passes,
 * collecting runtime and memory statistics. The first successful output is downscaled to
 * a thumbnail and retained for manual validation, and a full report is persisted as CSV.
 * Persistence and retention are delegated to [HistoryStore].
 *
 * Memory note: each segmentation produces a fresh full-resolution bitmap (tens of MB).
 * The engine therefore never retains per-iteration bitmaps — it keeps only the first
 * successful output (downscaled immediately) and recycles every other output.
 */
class BenchmarkEngine(context: Context) {

    companion object {
        private const val TAG = "BenchmarkEngine"
    }

    private val store = HistoryStore(context)

    /**
     * Runs a full benchmark session for [engines] on [inputBitmap].
     *
     * @param shouldStop Polled between iterations; when it returns true the session stops
     *   *after* the in-flight iteration finishes, and remaining engines are skipped.
     * @param onProgress Invoked as iterations complete so the UI can show live progress.
     */
    suspend fun generateReport(
        engines: List<Segmenter>,
        inputBitmap: Bitmap,
        iterations: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (BenchmarkProgress) -> Unit = {}
    ): BenchmarkReport = withContext(Dispatchers.Default) {
        val runId = store.newRunId()
        val reportStart = SystemClock.elapsedRealtime()
        val statsList = mutableListOf<BenchmarkStats>()
        var stopped = false

        // Engines run sequentially so they don't compete for CPU and skew runtimes.
        for ((engineIndex, engine) in engines.withIndex()) {
            if (stopped) break

            val (stats, stoppedEarly) = runSingleEngine(
                engine = engine,
                inputBitmap = inputBitmap,
                iterations = iterations,
                engineIndex = engineIndex,
                engineCount = engines.size,
                runId = runId,
                shouldStop = shouldStop,
                onProgress = onProgress
            )
            statsList.add(stats)
            stopped = stoppedEarly
        }

        val report = BenchmarkReport(
            timestamp = runId,
            imageWidth = inputBitmap.width,
            imageHeight = inputBitmap.height,
            totalDurationMs = SystemClock.elapsedRealtime() - reportStart,
            stats = statsList,
            stopped = stopped
        )

        report.copy(reportSavedPath = store.saveBenchmarkReport(runId, report.toCsv()))
    }

    /** Returns the engine's stats plus whether the run was cut short by [shouldStop]. */
    private suspend fun runSingleEngine(
        engine: Segmenter,
        inputBitmap: Bitmap,
        iterations: Int,
        engineIndex: Int,
        engineCount: Int,
        runId: Long,
        shouldStop: () -> Boolean,
        onProgress: (BenchmarkProgress) -> Unit
    ): Pair<BenchmarkStats, Boolean> {
        fun emitProgress(completed: Int) {
            onProgress(
                BenchmarkProgress(
                    engineIndex = engineIndex,
                    engineCount = engineCount,
                    engineName = engine.name,
                    completedIterations = completed,
                    iterationCount = iterations
                )
            )
        }

        // Warm-up pass: lets the interpreter/JIT and any lazy model loading settle.
        try {
            engine.segment(inputBitmap).getOrNull()?.foregroundBitmap?.recycleSafely(inputBitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up failed for ${engine.name}", e)
        }
        emitProgress(0)

        val samples = ArrayList<BenchmarkSample>(iterations)
        var stoppedEarly = false

        // Retains only the first successful output; every other output is recycled.
        var retainedOutput: Bitmap? = null

        for (i in 0 until iterations) {
            // Checked before each iteration so a stop request lets the current one finish.
            if (shouldStop()) {
                stoppedEarly = true
                break
            }

            val memBefore = currentAllocatedBytes()
            val startMs = SystemClock.elapsedRealtime()

            val result = try {
                engine.segment(inputBitmap)
            } catch (e: Exception) {
                Result.failure(e)
            }

            val wallClockMs = SystemClock.elapsedRealtime() - startMs
            val memUsed = (currentAllocatedBytes() - memBefore).coerceAtLeast(0L)

            result.fold(
                onSuccess = { segmentResult ->
                    val output = segmentResult.foregroundBitmap
                    if (!output.isRecycled) {
                        if (retainedOutput == null) {
                            // Keep the first successful output for validation.
                            retainedOutput = output
                        } else {
                            // Discard the rest: recycle now so the allocator can reuse the memory.
                            output.recycleSafely(inputBitmap)
                        }
                    }
                    samples.add(
                        BenchmarkSample(
                            runIndex = i,
                            // Prefer the engine's own timing; fall back to measured wall clock.
                            runtimeMs = segmentResult.executionTimeMs.takeIf { it > 0 } ?: wallClockMs,
                            memoryBytes = memUsed
                        )
                    )
                },
                onFailure = { error ->
                    samples.add(
                        BenchmarkSample(
                            runIndex = i,
                            runtimeMs = wallClockMs,
                            memoryBytes = 0L,
                            error = error.message ?: "unknown error"
                        )
                    )
                }
            )

            emitProgress(i + 1)
        }

        return buildStats(
            engine = engine,
            iterations = iterations,
            samples = samples,
            retainedOutput = retainedOutput,
            inputBitmap = inputBitmap,
            runId = runId,
            modelIndex = engineIndex
        ) to stoppedEarly
    }

    private fun buildStats(
        engine: Segmenter,
        iterations: Int,
        samples: List<BenchmarkSample>,
        retainedOutput: Bitmap?,
        inputBitmap: Bitmap,
        runId: Long,
        modelIndex: Int
    ): BenchmarkStats {
        val successful = samples.filter { it.error == null }
        val runtimes = successful.map { it.runtimeMs }.sorted()
        val memories = successful.map { it.memoryBytes }

        val avgRuntime = runtimes.averageOrZero()
        val variance = if (runtimes.size > 1) {
            runtimes.map { (it - avgRuntime).pow(2.0) }.average()
        } else {
            0.0
        }

        // Persist a downscaled copy and keep the small version for the results UI, so the
        // full-resolution bitmap can be released immediately.
        var displaySample: Bitmap? = null
        var savedPath: String? = null
        if (retainedOutput != null && !retainedOutput.isRecycled) {
            val saved = store.saveBenchmarkSample(runId, modelIndex, engine.name, retainedOutput)
            displaySample = saved.bitmap
            savedPath = saved.path
            // Never touch the caller's input bitmap.
            if (saved.bitmap !== retainedOutput && retainedOutput !== inputBitmap) {
                retainedOutput.recycleSafely(inputBitmap)
            }
        }

        return BenchmarkStats(
            engineId = engine.id,
            engineName = engine.name,
            iterations = iterations,
            successCount = successful.size,
            failureCount = samples.size - successful.size,
            avgRuntimeMs = avgRuntime,
            minRuntimeMs = runtimes.firstOrNull() ?: 0L,
            maxRuntimeMs = runtimes.lastOrNull() ?: 0L,
            stdDevRuntimeMs = sqrt(variance),
            p50RuntimeMs = percentile(runtimes, 0.50),
            p95RuntimeMs = percentile(runtimes, 0.95),
            p99RuntimeMs = percentile(runtimes, 0.99),
            avgMemoryBytes = memories.averageOrZero(),
            peakMemoryBytes = memories.maxOrNull() ?: 0L,
            sampleResultBitmap = displaySample,
            sampleSavedPath = savedPath,
            samples = samples
        )
    }

    /** Total allocated memory (Java heap + native heap) for the process, in bytes. */
    private fun currentAllocatedBytes(): Long {
        val runtime = Runtime.getRuntime()
        val javaHeap = runtime.totalMemory() - runtime.freeMemory()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        return javaHeap + nativeHeap
    }

    private fun percentile(sorted: List<Long>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index].toDouble()
    }

    private fun List<Long>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    /** Frees a discarded output bitmap; never touches the (borrowed) input bitmap. */
    private fun Bitmap.recycleSafely(input: Bitmap) {
        if (this !== input && !isRecycled) {
            try {
                recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recycle bitmap", e)
            }
        }
    }

    private fun BenchmarkReport.toCsv(): String {
        val header = "model,avg_runtime_ms,min_ms,max_ms,stddev_ms,p50_ms,p95_ms,p99_ms," +
            "avg_memory_kb,peak_memory_kb,success,failure,iterations"
        val rows = stats.joinToString("\n") { s ->
            val avgMemKb = String.format(Locale.US, "%.1f", s.avgMemoryBytes / 1024.0)
            listOf(
                s.engineName,
                String.format(Locale.US, "%.2f", s.avgRuntimeMs),
                s.minRuntimeMs,
                s.maxRuntimeMs,
                String.format(Locale.US, "%.2f", s.stdDevRuntimeMs),
                String.format(Locale.US, "%.1f", s.p50RuntimeMs),
                String.format(Locale.US, "%.1f", s.p95RuntimeMs),
                String.format(Locale.US, "%.1f", s.p99RuntimeMs),
                avgMemKb,
                s.peakMemoryBytes / 1024,
                s.successCount,
                s.failureCount,
                s.iterations
            ).joinToString(",")
        }
        return "$header\n$rows\n"
    }
}
