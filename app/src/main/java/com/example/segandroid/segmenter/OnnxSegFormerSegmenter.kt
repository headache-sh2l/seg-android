package com.example.segandroid.segmenter

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Generic ONNX Runtime segmenter for SegFormer-style semantic-segmentation models
 * (B0 / B2 / B3 / ...).
 *
 * Model *facts* — input size and output class count — are read from the ONNX graph at load
 * time, and the class *labels* are supplied per model (see [ModelLabels]). Which labels to
 * keep is decided by [GarmentKeepPolicy] by name, so models with different label spaces
 * (ATR-18, Fashionpedia-47, ...) all work without per-model class-id hardcoding.
 */
class OnnxSegFormerSegmenter(
    private val context: Context,
    override val id: String = "segformer_clothes_onnx",
    override val name: String = "SegFormer_B2_CP_ONNX_Q8",
    override val description: String = "SegFormer-B2 Clothes Parser (ONNX - INT8, 18-class)",
    override val category: SegmenterCategory = SegmenterCategory.FASHION,
    /** Index -> class name for this model (from its HuggingFace `config.json` `id2label`). */
    private val labels: List<String> = ModelLabels.ATR_18,
    private val modelAssetPath: String = "models/segformer_clothes.onnx",
    private val mean: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    private val std: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),
    /** Fallback input edge length, used only when the ONNX input shape is dynamic. */
    private val fallbackInputSize: Int = 512,
) : Segmenter {

    private val env by lazy { OrtEnvironment.getEnvironment() }

    private val session by lazy {
        val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
        env.createSession(modelBytes, OrtSession.SessionOptions()).also { validateModel(it) }
    }

    override suspend fun segment(inputBitmap: Bitmap): Result<SegmentResult> {
        return withContext(Dispatchers.Default) {
            try {
                val startTimeMs = SystemClock.elapsedRealtime()

                val inputName = session.inputInfo.keys.first()
                val width = resolveInputSize(session)
                val height = width

                val scaledBitmap = Bitmap.createScaledBitmap(inputBitmap, width, height, true)
                val inputFloatBuffer = bitmapToNormalizedFloatBuffer(scaledBitmap, width, height)

                val inputTensor = OnnxTensor.createTensor(
                    env,
                    inputFloatBuffer,
                    longArrayOf(1, 3, height.toLong(), width.toLong())
                )

                val results = session.run(mapOf(inputName to inputTensor))
                val executionTimeMs = SystemClock.elapsedRealtime() - startTimeMs

                val outputTensor = results[0] as? OnnxTensor
                    ?: error("Model '$name' did not return a tensor output.")
                val foregroundBitmap = try {
                    decodeOutput(outputTensor, scaledBitmap, width, height)
                } finally {
                    inputTensor.close()
                    results.close()
                }

                Result.success(
                    SegmentResult(
                        foregroundBitmap = foregroundBitmap,
                        executionTimeMs = executionTimeMs,
                        engineName = name,
                        details = mapOf(
                            "model" to modelAssetPath,
                            "input" to "${width}x$height",
                            "classes" to labels.size.toString(),
                        )
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ----- model introspection -------------------------------------------------

    private fun shapeOf(infos: Map<String, NodeInfo>): LongArray? =
        (infos.values.firstOrNull()?.info as? TensorInfo)?.shape

    /**
     * Fails loudly (rather than silently producing an empty mask) when a model's declared
     * labels don't line up with the number of classes it actually outputs.
     */
    private fun validateModel(session: OrtSession) {
        val inputShape = shapeOf(session.inputInfo)?.joinToString("x") ?: "?"
        val outputShape = shapeOf(session.outputInfo)?.joinToString("x") ?: "?"
        val outputClasses = outputClassCount(session)
        Log.i(TAG, "'$name' ($modelAssetPath): input=[$inputShape] output=[$outputShape] labels=${labels.size}")
        if (outputClasses > 0 && outputClasses != labels.size) {
            error(
                "Label count mismatch for '$name': the ONNX model outputs $outputClasses classes but " +
                    "${labels.size} labels are configured ($modelAssetPath). Update ModelLabels for this model."
            )
        }
    }

    /** Input edge length from the ONNX input shape, falling back when it is dynamic. */
    private fun resolveInputSize(session: OrtSession): Int {
        val dims = shapeOf(session.inputInfo)?.map { it.toInt() } ?: return fallbackInputSize
        val edge = when {
            dims.size == 4 && dims[1] == 3 -> dims.last()       // NCHW [1,3,H,W]
            dims.size == 4 && dims.last() == 3 -> dims[1]       // NHWC [1,H,W,3]
            else -> -1
        }
        return if (edge > 0) edge else fallbackInputSize
    }

    /** Number of output classes from the ONNX output shape, or -1 when it is dynamic. */
    private fun outputClassCount(session: OrtSession): Int {
        val dims = shapeOf(session.outputInfo)?.map { it.toInt() } ?: return -1
        return if (dims.size == 4 && dims[1] > 0) dims[1] else -1
    }

    // ----- output decoding -----------------------------------------------------

    private fun decodeOutput(output: OnnxTensor, original: Bitmap, width: Int, height: Int): Bitmap {
        return when (val value = output.value) {
            // SegFormer logits as a nested array: [1][classes][maskHeight][maskWidth]
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                val logits = value as Array<Array<Array<FloatArray>>>
                val perClass = logits[0]
                val classes = perClass.size
                val maskHeight = if (classes > 0) perClass[0].size else 0
                val maskWidth = if (maskHeight > 0) perClass[0][0].size else 0
                buildCutout(original, classes, maskHeight, maskWidth, width, height) { c, y, x ->
                    perClass[c][y][x]
                }
            }
            // Flat float buffer: [1][classes][maskHeight][maskWidth] in NCHW order
            else -> {
                val shape = output.info.shape
                val classes = shape[1].toInt()
                val maskHeight = shape[2].toInt()
                val maskWidth = shape[3].toInt()
                val buffer = output.floatBuffer
                buffer.rewind()
                val floats = FloatArray(buffer.remaining())
                buffer.get(floats)
                val plane = maskHeight * maskWidth
                buildCutout(original, classes, maskHeight, maskWidth, width, height) { c, y, x ->
                    floats[c * plane + y * maskWidth + x]
                }
            }
        }
    }

    /**
     * Picks, per output pixel, the highest-logit class and keeps the pixel only when
     * [GarmentKeepPolicy] keeps that class's label. The mask is nearest-neighbour upsampled
     * to [width] x [height].
     */
    private fun buildCutout(
        original: Bitmap,
        classes: Int,
        maskHeight: Int,
        maskWidth: Int,
        width: Int,
        height: Int,
        logit: (c: Int, y: Int, x: Int) -> Float,
    ): Bitmap {
        if (classes != labels.size) {
            error(
                "Label count mismatch for '$name': model produced $classes classes but " +
                    "${labels.size} labels are configured."
            )
        }

        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            val maskY = (y * maskHeight / height).coerceIn(0, maskHeight - 1)
            for (x in 0 until width) {
                val maskX = (x * maskWidth / width).coerceIn(0, maskWidth - 1)

                var maxClass = 0
                var maxLogit = Float.NEGATIVE_INFINITY
                for (c in 0 until classes) {
                    val value = logit(c, maskY, maskX)
                    if (value > maxLogit) {
                        maxLogit = value
                        maxClass = c
                    }
                }

                if (!GarmentKeepPolicy.shouldKeep(labels[maxClass])) {
                    pixels[y * width + x] = Color.TRANSPARENT
                }
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /** Converts a Bitmap to an NCHW normalized FloatBuffer using this model's mean/std. */
    private fun bitmapToNormalizedFloatBuffer(bitmap: Bitmap, width: Int, height: Int): FloatBuffer {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val plane = height * width
        val floats = FloatArray(3 * plane)

        for (i in pixels.indices) {
            val px = pixels[i]
            floats[i] = (((px shr 16) and 0xFF) / 255.0f - mean[0]) / std[0]
            floats[plane + i] = (((px shr 8) and 0xFF) / 255.0f - mean[1]) / std[1]
            floats[2 * plane + i] = ((px and 0xFF) / 255.0f - mean[2]) / std[2]
        }

        val floatBuffer = FloatBuffer.allocate(3 * plane)
        floatBuffer.put(floats)
        floatBuffer.rewind()
        return floatBuffer
    }

    private companion object {
        const val TAG = "OnnxSegmenter"
    }
}
