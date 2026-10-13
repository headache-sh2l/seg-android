package com.example.segandroid.segmenter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * On-device reusable fashion parsing segmenter powered by ONNX Runtime and SegFormer models.
 * Accepts any SegFormer ONNX asset file (B0, B2, B3) and handles input/output tensor shapes.
 */
class OnnxSegFormerSegmenter(
    private val context: Context,
    override val id: String = "segformer_clothes_onnx",
    override val name: String = "SegFormer_B2_CP_ONNX_Q8",
    override val description: String = "SegFormer-B2 Clothes Parser (ONNX - INT8, 18-class)",
    private val modelAssetPath: String = "models/segformer_clothes.onnx"
) : GarmentSegmenter {

    private val env by lazy { OrtEnvironment.getEnvironment() }

    private val session by lazy {
        val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
        env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    override suspend fun segment(inputBitmap: Bitmap): Result<SegmentResult> {
        return withContext(Dispatchers.Default) {
            try {
                val startTimeMs = SystemClock.elapsedRealtime()

                val inputInfo = session.inputInfo
                val inputName = inputInfo.keys.first()

                // Standard SegFormer input dimensions (512x512)
                val width = 512
                val height = 512

                val scaledBitmap = Bitmap.createScaledBitmap(inputBitmap, width, height, true)
                val inputFloatBuffer = bitmapToNormalizedFloatBuffer(scaledBitmap, width, height)

                val inputTensor = OnnxTensor.createTensor(
                    env,
                    inputFloatBuffer,
                    longArrayOf(1, 3, height.toLong(), width.toLong())
                )

                val results = session.run(mapOf(inputName to inputTensor))
                val executionTimeMs = SystemClock.elapsedRealtime() - startTimeMs

                val resultValue = results[0].value

                val foregroundBitmap = when (resultValue) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val logits4D = resultValue as Array<Array<Array<FloatArray>>>
                        extractClothingFrom4DArray(scaledBitmap, logits4D[0], width, height)
                    }
                    else -> {
                        val outputTensor = results[0] as OnnxTensor
                        extractClothingFromLogitsBuffer(scaledBitmap, outputTensor.floatBuffer, width, height)
                    }
                }

                inputTensor.close()
                results.close()

                Result.success(
                    SegmentResult(
                        foregroundBitmap = foregroundBitmap,
                        executionTimeMs = executionTimeMs,
                        engineName = name,
                        details = mapOf("model" to modelAssetPath, "input" to "${width}x${height}")
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Converts a Bitmap to an NCHW Normalized FloatBuffer using ImageNet mean & std.
     */
    private fun bitmapToNormalizedFloatBuffer(bitmap: Bitmap, width: Int, height: Int): FloatBuffer {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val floatBuffer = FloatBuffer.allocate(1 * 3 * height * width)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        val rOffset = 0
        val gOffset = height * width
        val bOffset = 2 * height * width

        val floats = FloatArray(3 * height * width)

        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (((px shr 16) and 0xFF) / 255.0f - mean[0]) / std[0]
            val g = (((px shr 8) and 0xFF) / 255.0f - mean[1]) / std[1]
            val b = ((px and 0xFF) / 255.0f - mean[2]) / std[2]

            floats[rOffset + i] = r
            floats[gOffset + i] = g
            floats[bOffset + i] = b
        }

        floatBuffer.put(floats)
        floatBuffer.rewind()
        return floatBuffer
    }

    /**
     * Decodes 3D logits array [numClasses, maskHeight, maskWidth] (18x128x128 for SegFormer).
     * Retains ONLY clothing classes (Upper-clothes, Skirt, Pants, Dress, Shoes) while
     * excluding Background (0), Skin (8), Face (11), and Arms (14, 15).
     */
    private fun extractClothingFrom4DArray(original: Bitmap, logits3D: Array<Array<FloatArray>>, width: Int, height: Int): Bitmap {
        val numClasses = logits3D.size
        val maskHeight = logits3D[0].size
        val maskWidth = if (maskHeight > 0) logits3D[0][0].size else 128

        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        // Clothing Category Class IDs in ATR dataset (4=Upper, 5=Skirt, 6=Pants, 7=Dress, 9=Shoe, 10=Shoe, 17=Scarf)
        val clothingClasses = setOf(4, 5, 6, 7, 9, 10, 17)

        for (y in 0 until height) {
            val maskY = (y * maskHeight / height).coerceIn(0, maskHeight - 1)
            for (x in 0 until width) {
                val maskX = (x * maskWidth / width).coerceIn(0, maskWidth - 1)

                var maxClass = 0
                var maxLogit = Float.NEGATIVE_INFINITY

                for (c in 0 until numClasses) {
                    val logit = logits3D[c][maskY][maskX]
                    if (logit > maxLogit) {
                        maxLogit = logit
                        maxClass = c
                    }
                }

                // If winning class is NOT clothing (e.g. background, arm, skin, face), set transparent!
                if (maxClass !in clothingClasses) {
                    pixels[y * width + x] = Color.TRANSPARENT
                }
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun extractClothingFromLogitsBuffer(original: Bitmap, outputBuffer: FloatBuffer, width: Int, height: Int): Bitmap {
        outputBuffer.rewind()
        val numClasses = 18
        val maskHeight = 128
        val maskWidth = 128
        val planeSize = maskHeight * maskWidth

        val floats = FloatArray(outputBuffer.remaining())
        outputBuffer.get(floats)

        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        val clothingClasses = setOf(4, 5, 6, 7, 9, 10, 17)

        for (y in 0 until height) {
            val maskY = (y * maskHeight / height).coerceIn(0, maskHeight - 1)
            for (x in 0 until width) {
                val maskX = (x * maskWidth / width).coerceIn(0, maskWidth - 1)
                val maskIndex = maskY * maskWidth + maskX

                var maxClass = 0
                var maxLogit = Float.NEGATIVE_INFINITY

                for (c in 0 until numClasses) {
                    val index = c * planeSize + maskIndex
                    if (index < floats.size) {
                        val logit = floats[index]
                        if (logit > maxLogit) {
                            maxLogit = logit
                            maxClass = c
                        }
                    }
                }

                if (maxClass !in clothingClasses) {
                    pixels[y * width + x] = Color.TRANSPARENT
                }
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}
