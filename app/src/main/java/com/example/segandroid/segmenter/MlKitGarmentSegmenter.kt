package com.example.segandroid.segmenter

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device garment/subject segmenter powered by Google ML Kit Subject Segmentation.
 */
class MlKitGarmentSegmenter : GarmentSegmenter {

    override val id: String = "mlkit_subject"
    override val name: String = "MLKit_Subject_Single_v1"
    override val description: String = "Google ML Kit Single-Subject Background Removal (Hardware Accelerated)"

    private val options = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .build()

    private val segmenter by lazy {
        SubjectSegmentation.getClient(options)
    }

    override suspend fun segment(inputBitmap: Bitmap): Result<SegmentResult> {
        return try {
            val startTimeMs = SystemClock.elapsedRealtime()
            val inputImage = InputImage.fromBitmap(inputBitmap, 0)

            val subjectResult = segmenter.process(inputImage).awaitTask<SubjectSegmentationResult>()
            val executionTimeMs = SystemClock.elapsedRealtime() - startTimeMs

            val foregroundBitmap = subjectResult.foregroundBitmap
            if (foregroundBitmap != null) {
                Result.success(
                    SegmentResult(
                        foregroundBitmap = foregroundBitmap,
                        executionTimeMs = executionTimeMs,
                        engineName = name
                    )
                )
            } else {
                Result.failure(IllegalStateException("No subject/garment detected in image."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Helper extension to convert Google Play Services Task into a Kotlin suspend function.
 */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { exception ->
        if (continuation.isActive) continuation.resumeWithException(exception)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}
