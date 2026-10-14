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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmenterTestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engines = remember(context) { SegmenterRegistry.getAllEngines(context) }
    val historyStore = remember(context) { HistoryStore(context) }
    val categories = remember(engines) { engines.map { it.category }.distinct().sortedBy { it.ordinal } }
    var selectedCategory by remember { mutableStateOf(categories.first()) }
    var selectedId by remember { mutableStateOf(engines.first().id) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultState by remember { mutableStateOf<SegmentResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastSavedPath by remember { mutableStateOf<String?>(null) }
    var viewingResult by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            errorMessage = null
            resultState = null
            originalBitmap = try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load image: ${e.message}"
                null
            }
        }
    }

    val modelsInCategory = engines.filter { it.category == selectedCategory }
    val currentEngine = modelsInCategory.firstOrNull { it.id == selectedId } ?: modelsInCategory.first()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Segmenter",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        // 1. Category ("type") selector
        ExposedDropdownMenuBox(
            expanded = categoryMenuOpen,
            onExpandedChange = { categoryMenuOpen = !categoryMenuOpen }
        ) {
            OutlinedTextField(
                value = selectedCategory.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen)
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = categoryMenuOpen,
                onDismissRequest = { categoryMenuOpen = false }
            ) {
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.displayName) },
                        onClick = {
                            selectedCategory = category
                            // Snap the model selection to the first model of the new category.
                            engines.firstOrNull { it.category == category }?.let { selectedId = it.id }
                            categoryMenuOpen = false
                        }
                    )
                }
            }
        }

        // 2. Model selector, filtered to the chosen category
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = !dropdownExpanded }
        ) {
            OutlinedTextField(
                value = currentEngine.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                supportingText = { Text(currentEngine.description) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                modelsInCategory.forEach { engine ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(engine.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    engine.description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            selectedId = engine.id
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Text("Select Photo")
            }

            Button(
                onClick = {
                    val bitmap = originalBitmap ?: return@Button
                    isProcessing = true
                    errorMessage = null
                    resultState = null
                    lastSavedPath = null
                    scope.launch {
                        val result = currentEngine.segment(bitmap)
                        isProcessing = false
                        result.fold(
                            onSuccess = { segmentResult ->
                                resultState = segmentResult
                                // Retain a downscaled thumbnail; the store keeps the last 20 runs.
                                lastSavedPath = historyStore.saveSegmentRun(
                                    engineName = currentEngine.name,
                                    timeTakenMs = segmentResult.executionTimeMs,
                                    bitmap = segmentResult.foregroundBitmap
                                )
                            },
                            onFailure = { errorMessage = it.message ?: "Segmentation failed" }
                        )
                    }
                },
                enabled = originalBitmap != null && !isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isProcessing) "Segmenting..." else "Segment")
            }
        }

        if (isProcessing) {
            CircularProgressIndicator()
        }

        errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }

        resultState?.let { result ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Segmented in ${result.executionTimeMs} ms",
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (lastSavedPath != null) {
                        Text(
                            text = "Saved to history",
                            color = Color(0xFF2E7D32),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            originalBitmap?.let { bitmap ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Original", fontWeight = FontWeight.SemiBold)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Original photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )
                }
            }

            resultState?.foregroundBitmap?.let { segmentedBitmap ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Segmented", fontWeight = FontWeight.SemiBold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .background(Color.DarkGray, RoundedCornerShape(8.dp))
                            .clickable { viewingResult = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = segmentedBitmap.asImageBitmap(),
                            contentDescription = "Tap to view the segmented result full size",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    // Full-screen inspection of the segmented output.
    val resultBitmap = resultState?.foregroundBitmap
    if (viewingResult && resultBitmap != null && !resultBitmap.isRecycled) {
        ImageViewerDialog(
            bitmap = resultBitmap,
            title = currentEngine.name,
            subtitle = "Segmented in ${resultState?.executionTimeMs} ms",
            onDismiss = { viewingResult = false }
        )
    }
}
