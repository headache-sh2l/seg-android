package com.example.segandroid.segmenter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device dedicated cloth segmenter powered by TensorFlow Lite Interpreter (Cloth-Segmentation-U2Net-LiteRT).
 * Natively processes both NCHW [1, 3, H, W] and NHWC [1, H, W, 3] model tensor shapes.
 */
class MediaPipeGarmentSegmenter(private val context: Context) : GarmentSegmenter {

    override val id: String = "u2net_cloth_litert"
    override val name: String = "U2Net_Cloth_LiteRT_v1"
    override val description: String = "Cloth-Segmentation-U2Net-LiteRT (HuggingFace Dedicated Cloth Model)"

    private val interpreter by lazy {
        val modelBuffer = loadModelFile(context, "models/clothseg.tflite")
        Interpreter(modelBuffer)
    }

    override suspend fun segment(inputBitmap: Bitmap): Result<SegmentResult> {
        return withContext(Dispatchers.Default) {
            try {
                val startTimeMs = SystemClock.elapsedRealtime()

                val inputTensor = interpreter.getInputTensor(0)
                val inputShape = inputTensor.shape() // e.g. [1, 3, 768, 768] (NCHW) or [1, 768, 768, 3] (NHWC)

                val isNchw = inputShape.size == 4 && inputShape[1] == 3
                val height = if (isNchw) inputShape[2] else inputShape[1]
                val width = if (isNchw) inputShape[3] else inputShape[2]

                // Scale input bitmap to model input dimensions
                val scaledBitmap = Bitmap.createScaledBitmap(inputBitmap, width, height, true)

                // Prepare input ByteBuffer (Float32 normalized 0..1)
                val inputBuffer = ByteBuffer.allocateDirect(1 * 3 * height * width * 4)
                inputBuffer.order(ByteOrder.nativeOrder())

                val pixels = IntArray(width * height)
                scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                if (isNchw) {
                    // Populate NCHW format: [Batch, Channel, Height, Width]
                    val rOffset = 0
                    val gOffset = height * width
                    val bOffset = 2 * height * width

                    val floats = FloatArray(3 * height * width)
                    for (i in pixels.indices) {
                        val px = pixels[i]
                        val r = ((px shr 16) and 0xFF) / 255.0f
                        val g = ((px shr 8) and 0xFF) / 255.0f
                        val b = (px and 0xFF) / 255.0f

                        floats[rOffset + i] = r
                        floats[gOffset + i] = g
                        floats[bOffset + i] = b
                    }
                    inputBuffer.asFloatBuffer().put(floats)
                } else {
                    // Populate NHWC format: [Batch, Height, Width, Channel]
                    for (px in pixels) {
                        inputBuffer.putFloat(((px shr 16) and 0xFF) / 255.0f)
                        inputBuffer.putFloat(((px shr 8) and 0xFF) / 255.0f)
                        inputBuffer.putFloat((px and 0xFF) / 255.0f)
                    }
                }

                // Prepare output buffer matching model output shape [1, numClasses, height, width]
                val outputTensor = interpreter.getOutputTensor(0)
                val outputShape = outputTensor.shape()
                val numClasses = if (outputShape.size == 4) outputShape[1] else 1

                val maskOutput = Array(1) { Array(numClasses) { Array(height) { FloatArray(width) } } }

                // Run inference
                interpreter.run(inputBuffer, maskOutput)
                val executionTimeMs = SystemClock.elapsedRealtime() - startTimeMs

                // Extract transparent cutout by selecting winning category class per pixel
                val outputPixels = IntArray(width * height)
                scaledBitmap.getPixels(outputPixels, 0, width, 0, 0, width, height)

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        var maxClass = 0
                        var maxConfidence = maskOutput[0][0][y][x]

                        for (c in 1 until numClasses) {
                            val conf = maskOutput[0][c][y][x]
                            if (conf > maxConfidence) {
                                maxConfidence = conf
                                maxClass = c
                            }
                        }

                        // Class 0 = Background. Non-background clothing categories are retained.
                        if (maxClass == 0 || maxConfidence < 0.5f) {
                            outputPixels[y * width + x] = Color.TRANSPARENT
                        }
                    }
                }

                val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                resultBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)

                Result.success(
                    SegmentResult(
                        foregroundBitmap = resultBitmap,
                        executionTimeMs = executionTimeMs,
                        engineName = name,
                        details = mapOf("input" to inputShape.joinToString("x"), "output" to outputShape.joinToString("x"))
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}
