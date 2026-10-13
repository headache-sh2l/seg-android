package com.example.segandroid.segmenter

import android.graphics.Bitmap

/**
 * Interface for plug-and-play on-device garment segmenters.
 */
interface GarmentSegmenter {
    /** Unique identifier key (e.g. "mlkit_subject") */
    val id: String

    /** Display name shown in UI dropdowns */
    val name: String

    /** Brief description of the ML model/algorithm */
    val description: String

    /** Executes segmentation on an input image */
    suspend fun segment(inputBitmap: Bitmap): Result<SegmentResult>
}
