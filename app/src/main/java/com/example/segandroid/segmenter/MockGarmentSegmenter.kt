package com.example.segandroid.segmenter

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Emulator-safe segmenter that simulates background removal visually
 * without triggering native TFLite SIMD crashes on the ARM64 macOS emulator.
 */
class MockGarmentSegmenter : GarmentSegmenter {

    override val id: String = "mock_emulator"
    override val name: String = "Mock_Segmenter_V1"
    override val description: String = "Mock Segmenter (Emulator Safe Center-Cutout)"

    override suspend fun segment(inputBitmap: Bitmap): Result<SegmentResult> {
        val startTime = SystemClock.elapsedRealtime()
        delay(30) // Simulate fast 30ms ML inference

        val width = inputBitmap.width
        val height = inputBitmap.height

        val pixels = IntArray(width * height)
        inputBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = width / 2f
        val centerY = height / 2f
        val radiusX = width * 0.38f
        val radiusY = height * 0.42f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val normalizedDistance = (dx * dx) + (dy * dy)

                // Make outer background pixels transparent to simulate cutout on emulator
                if (normalizedDistance > 1.0f) {
                    pixels[index] = Color.TRANSPARENT
                }
            }
        }

        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outputBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val executionTimeMs = SystemClock.elapsedRealtime() - startTime

        return Result.success(
            SegmentResult(
                foregroundBitmap = outputBitmap,
                executionTimeMs = executionTimeMs,
                engineName = name
            )
        )
    }
}
