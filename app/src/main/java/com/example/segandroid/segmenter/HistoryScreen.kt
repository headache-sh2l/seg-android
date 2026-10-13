package com.example.segandroid.segmenter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists the retained runs (last 10 benchmark sessions + last 20 single segmentations),
 * newest first. Cards show their statistics inline; tapping one opens the full report
 * (benchmark) or the run's output and timing (segment).
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { HistoryStore(context) }
    var entries by remember { mutableStateOf(store.listHistory()) }
    var selected by remember { mutableStateOf<RunHistoryEntry?>(null) }

    val current = selected
    if (current == null) {
        HistoryList(
            modifier = modifier,
            entries = entries,
            onClearAll = {
                store.clearAll()
                entries = store.listHistory()
            },
            onOpen = { selected = it },
            onShareCsv = { path ->
                shareFile(context, File(path), "text/csv", "Share benchmark report")
            }
        )
    } else {
        HistoryDetail(
            modifier = modifier,
            store = store,
            entry = current,
            onBack = { selected = null }
        )
    }
}

@Composable
private fun HistoryList(
    modifier: Modifier,
    entries: List<RunHistoryEntry>,
    onClearAll: () -> Unit,
    onOpen: (RunHistoryEntry) -> Unit,
    onShareCsv: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Run history", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Keeps the last 10 benchmark runs and 20 segment runs",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClearAll, enabled = entries.isNotEmpty()) {
                Text("Clear all")
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = "No runs yet. Run a segmentation or a benchmark and it will appear here.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.type.name + it.runId }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onOpen = { onOpen(entry) },
                        onShareCsv = onShareCsv
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: RunHistoryEntry,
    onOpen: () -> Unit,
    onShareCsv: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StaticThumbnail(path = entry.thumbnailPath)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = when (entry.type) {
                        RunType.BENCHMARK -> "Benchmark run"
                        RunType.SEGMENT -> "Segment run"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                when (entry.type) {
                    RunType.SEGMENT -> {
                        Text(entry.segmentEngineName ?: "unknown", fontSize = 13.sp)
                        Text(
                            text = "${entry.segmentTimeMs ?: 0L} ms",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    RunType.BENCHMARK -> {
                        // Same per-model statistics as a live benchmark, right on the card.
                        entry.models.take(MAX_INLINE_MODELS).forEach { model ->
                            Text(
                                text = "${model.engineName} · ${formatMs(model.avgRuntimeMs)} ms avg" +
                                    " (${model.iterations})",
                                fontSize = 13.sp
                            )
                        }
                        if (entry.models.size > MAX_INLINE_MODELS) {
                            Text(
                                text = "+${entry.models.size - MAX_INLINE_MODELS} more model(s)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = formatTimestamp(entry.runId),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            entry.csvPath?.let { path ->
                TextButton(onClick = { onShareCsv(path) }) {
                    Text("Share CSV")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ detail

@Composable
private fun HistoryDetail(
    modifier: Modifier,
    store: HistoryStore,
    entry: RunHistoryEntry,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back") }

        Text(
            text = when (entry.type) {
                RunType.BENCHMARK -> "Benchmark report"
                RunType.SEGMENT -> "Segment run"
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = formatTimestamp(entry.runId),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (entry.type) {
            RunType.BENCHMARK -> BenchmarkDetail(store = store, entry = entry)
            RunType.SEGMENT -> SegmentDetail(store = store, entry = entry)
        }
    }
}

@Composable
private fun BenchmarkDetail(store: HistoryStore, entry: RunHistoryEntry) {
    val detail = remember(entry.runId) { store.loadBenchmarkRun(entry.runId) }

    if (detail == null || detail.models.isEmpty()) {
        Text("Report could not be loaded.", color = MaterialTheme.colorScheme.error)
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("${detail.models.size} model(s)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        ShareButton(entry.csvPath)
    }

    Text(
        text = detail.csvPath,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    detail.models.forEach { model ->
        BenchmarkResultCard(model.stats)
    }
}

@Composable
private fun SegmentDetail(store: HistoryStore, entry: RunHistoryEntry) {
    val detail = remember(entry.runId) { store.loadSegmentRun(entry) }
    var viewingFull by remember { mutableStateOf(false) }

    if (detail == null) {
        Text("Run could not be loaded.", color = MaterialTheme.colorScheme.error)
        return
    }

    val bitmap = detail.thumbnail
    if (viewingFull && bitmap != null && !bitmap.isRecycled) {
        ImageViewerDialog(
            bitmap = bitmap,
            title = detail.engineName,
            subtitle = "Segmented in ${detail.timeTakenMs} ms",
            onDismiss = { viewingFull = false }
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(detail.engineName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                text = "Time taken: ${detail.timeTakenMs} ms",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(Color.DarkGray, RoundedCornerShape(8.dp))
                    .clickable(enabled = bitmap != null) { viewingFull = true },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Tap to view full size",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("n/a", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ShareButton(csvPath: String?) {
    val context = LocalContext.current
    csvPath ?: return
    TextButton(
        onClick = { shareFile(context, File(csvPath), "text/csv", "Share benchmark report") }
    ) {
        Text("Share CSV")
    }
}

// ------------------------------------------------------------------ helpers

private const val MAX_INLINE_MODELS = 3

@Composable
private fun StaticThumbnail(path: String?) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(Color.DarkGray, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap: Bitmap? = remember(path) {
            path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun formatTimestamp(runId: Long): String =
    SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.US).format(Date(runId))
