package com.example.segandroid.segmenter

/**
 * Available ML segmenter engines for benchmark comparison.
 */
enum class SegmenterEngine(val displayName: String) {
    ML_KIT_SUBJECT("mlkit.vision.segmentation.subject"),
    MOCK("Mock Segmenter (Emulator Safe)");

    fun createSegmenter(): GarmentSegmenter {
        return when (this) {
            ML_KIT_SUBJECT -> MlKitGarmentSegmenter()
            MOCK -> MockGarmentSegmenter()
        }
    }
}
