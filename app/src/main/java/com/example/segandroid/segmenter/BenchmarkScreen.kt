package com.example.segandroid.segmenter

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private val ITERATION_OPTIONS = listOf(5, 10, 100, 200, 500, 1000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engines = remember(context) { SegmenterRegistry.getAllEngines(context) }
    val benchmarkEngine = remember { BenchmarkEngine(context) }

    var selectedIds by remember { mutableStateOf(engines.map { it.id }.toSet()) }
    var iterations by remember { mutableStateOf(10) }
    var iterationsMenuOpen by remember { mutableStateOf(false) }

    var testBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<BenchmarkProgress?>(null) }
    var report by remember { mutableStateOf<BenchmarkReport?>(null) }

    // A plain flag read from the benchmark thread; mirrors isStopping in Compose state.
    val stopFlag = remember { AtomicBoolean(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            errorMessage = null
            report = null
            testBitmap = try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    // Software allocation avoids GPU-backed bitmaps that ML runtimes can't read.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load image: ${e.message}"
                null
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Extra bottom padding keeps the running row (progress + Stop) clear of the nav bar.
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Segmenter Benchmark",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        // 1. Model selection
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("1. Models to benchmark", fontWeight = FontWeight.SemiBold)
                engines.forEach { engine ->
                    val checked = engine.id in selectedIds
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !isRunning,
                            onCheckedChange = { isChecked ->
                                selectedIds = if (isChecked) {
                                    selectedIds + engine.id
                                } else {
                                    selectedIds - engine.id
                                }
                            }
                        )
                        Column {
                            Text(engine.name, fontWeight = FontWeight.Medium)
                            Text(
                                engine.description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 2. Iteration count
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("2. Iterations per model", fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = iterationsMenuOpen,
                    onExpandedChange = { if (!isRunning) iterationsMenuOpen = !iterationsMenuOpen }
                ) {
                    OutlinedTextField(
                        value = iterations.toString(),
                        onValueChange = {},
                        readOnly = true,
                        enabled = !isRunning,
                        label = { Text("Iterations") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = iterationsMenuOpen)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = iterationsMenuOpen,
                        onDismissRequest = { iterationsMenuOpen = false }
                    ) {
                        ITERATION_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.toString()) },
                                onClick = {
                                    iterations = option
                                    iterationsMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3. Test image
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("3. Test image", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isRunning
                ) {
                    Text(if (testBitmap == null) "Select Photo" else "Change Photo")
                }
                testBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Test image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
            }
        }

        Button(
            onClick = {
                val bitmap = testBitmap
                when {
                    selectedIds.isEmpty() -> errorMessage = "Select at least one model."
                    bitmap == null -> errorMessage = "Select a test image first."
                    else -> {
                        val chosen = engines.filter { it.id in selectedIds }
                        stopFlag.set(false)
                        isRunning = true
                        isStopping = false
                        errorMessage = null
                        report = null
                        progress = null
                        scope.launch {
                            try {
                                report = benchmarkEngine.generateReport(
                                    engines = chosen,
                                    inputBitmap = bitmap,
                                    iterations = iterations,
                                    shouldStop = { stopFlag.get() },
                                    onProgress = { progress = it }
                                )
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Benchmark failed."
                            } finally {
                                isRunning = false
                                isStopping = false
                                progress = null
                            }
                        }
                    }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning) "Running benchmark..." else "Run Benchmark")
        }

        if (isRunning) {
            BenchmarkProgressRow(
                progress = progress,
                isStopping = isStopping,
                onStop = {
                    if (!isStopping) {
                        // Sets the flag; the engine stops after the in-flight iteration.
                        stopFlag.set(true)
                        isStopping = true
                    }
                }
            )
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }

        report?.let { currentReport ->
            HorizontalDivider()
            Text(
                text = "Results",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = buildString {
                    append("Total time: ${currentReport.totalDurationMs / 1000.0}s")
                    append(" · Image: ${currentReport.imageWidth}x${currentReport.imageHeight}")
                    if (currentReport.stopped) append(" · stopped early")
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            currentReport.stats.forEach { stats ->
                BenchmarkResultCard(stats)
            }
            currentReport.reportSavedPath?.let { path ->
                ReportCard(
                    path = path,
                    onShare = {
                        shareFile(
                            context = context,
                            file = File(path),
                            mimeType = "text/csv",
                            chooserTitle = "Share benchmark report"
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun BenchmarkProgressRow(
    progress: BenchmarkProgress?,
    isStopping: Boolean,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = progress?.let {
                    "${it.engineName} · ${it.completedIterations}/${it.iterationCount} iterations" +
                        " (${(it.fraction * 100).toInt()}%)"
                } ?: "Starting...",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Small stop button next to the progress indicator.
        FilledTonalButton(
            onClick = onStop,
            enabled = !isStopping,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(if (isStopping) "Stopping..." else "Stop", fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReportCard(path: String, onShare: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Report saved", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                TextButton(onClick = onShare) {
                    Text("Share CSV")
                }
            }
            Text(path, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun BenchmarkResultCard(stats: BenchmarkStats) {
    var viewingImage by remember { mutableStateOf(false) }

    val sample = stats.sampleResultBitmap
    if (viewingImage && sample != null && !sample.isRecycled) {
        ImageViewerDialog(
            bitmap = sample,
            title = stats.engineName,
            subtitle = "avg ${formatMs(stats.avgRuntimeMs)} ms · min ${stats.minRuntimeMs} ms · " +
                "max ${stats.maxRuntimeMs} ms",
            onDismiss = { viewingImage = false }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: model name + retained sample thumbnail
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stats.engineName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${stats.successCount}/${stats.iterations} ok" +
                            if (stats.failureCount > 0) " · ${stats.failureCount} failed" else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SampleThumbnail(
                    stats = stats,
                    onClick = { if (sample != null) viewingImage = true }
                )
            }

            // Headline: average runtime
            Text(
                text = formatMs(stats.avgRuntimeMs) + " avg",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Runtime statistics
            StatRow("min / max", "${stats.minRuntimeMs} / ${stats.maxRuntimeMs} ms")
            StatRow("std dev", formatMs(stats.stdDevRuntimeMs) + " ms")
            StatRow(
                "p50 / p95 / p99",
                "${formatMs(stats.p50RuntimeMs)} / ${formatMs(stats.p95RuntimeMs)} / " +
                    "${formatMs(stats.p99RuntimeMs)} ms"
            )

            HorizontalDivider()

            // Cost-to-run statistics
            StatRow("avg memory", formatKb(stats.avgMemoryBytes) + " KB")
            StatRow("peak memory", "${stats.peakMemoryBytes / 1024} KB")

            stats.sampleSavedPath?.let { path ->
                Text(
                    text = "Sample: $path",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SampleThumbnail(stats: BenchmarkStats, onClick: () -> Unit) {
    val bitmap = stats.sampleResultBitmap
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(Color.DarkGray, RoundedCornerShape(8.dp))
            .clickable(enabled = bitmap != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Tap to view ${stats.engineName} output full size",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("n/a", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

