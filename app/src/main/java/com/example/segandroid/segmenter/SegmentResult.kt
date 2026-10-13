package com.example.segandroid.segmenter

import android.graphics.Bitmap

/**
 * Result of an image segmentation operation.
 *
 * @property foregroundBitmap The extracted object/garment with a transparent background.
 * @property executionTimeMs Time taken to process the segmentation in milliseconds.
 * @property engineName Name of the ML engine used.
 * @property details Additional benchmark metadata.
 */
data class SegmentResult(
    val foregroundBitmap: Bitmap,
    val executionTimeMs: Long,
    val engineName: String = "",
    val details: Map<String, String> = emptyMap()
)
