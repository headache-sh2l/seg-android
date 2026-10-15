package com.example.segandroid.segmenter

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * An individual extracted item or character instance within a segmentation result.
 *
 * @property id Unique item/instance key (e.g. "subject_0", "Dress_1").
 * @property label Human-readable label (e.g. "Person 1", "Dress", "Upper-clothes").
 * @property bitmap Standalone cutout bitmap for this item.
 * @property bounds Bounding box relative to original image coordinate space, if available.
 */
data class SegmentItem(
    val id: String,
    val label: String,
    val bitmap: Bitmap,
    val bounds: Rect? = null
)

/**
 * Result of an image segmentation operation.
 *
 * @property foregroundBitmap The full extracted foreground cutout with a transparent background.
 * @property items List of individual separated item or character instances.
 * @property executionTimeMs Time taken to process the segmentation in milliseconds.
 * @property engineName Name of the ML engine used.
 * @property details Additional benchmark metadata.
 */
data class SegmentResult(
    val foregroundBitmap: Bitmap,
    val items: List<SegmentItem> = emptyList(),
    val executionTimeMs: Long,
    val engineName: String = "",
    val details: Map<String, String> = emptyMap()
)
