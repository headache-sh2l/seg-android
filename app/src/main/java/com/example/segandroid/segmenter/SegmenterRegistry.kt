package com.example.segandroid.segmenter

import android.content.Context

/**
 * Central registry for plug-and-play Garment Segmenter models.
 */
object SegmenterRegistry {

    private val engineFactories = mutableMapOf<String, (Context) -> GarmentSegmenter>()

    init {
        // Register default built-in engines
        register("mlkit_subject") { MlKitGarmentSegmenter() }
        register("u2net_cloth_litert") { MediaPipeGarmentSegmenter(it) }

        // SegFormer-B2 INT8 Clothes Parser (Active)
        register("segformer_b2_onnx") {
            OnnxSegFormerSegmenter(
                context = it,
                id = "segformer_b2_onnx",
                name = "SegFormer_B2_CP_ONNX_Q8",
                description = "SegFormer-B2 Clothes Parser (ONNX - INT8, 27.3 MB, 18-class)",
                modelAssetPath = "models/segformer_clothes.onnx"
            )
        }

        // SegFormer-B0 INT8 Clothes Parser (Prepared for segformer_b0_q8.onnx asset)
        register("segformer_b0_onnx") {
            OnnxSegFormerSegmenter(
                context = it,
                id = "segformer_b0_onnx",
                name = "SegFormer_B0_CP_ONNX_Q8",
                description = "SegFormer-B0 Clothes Parser (ONNX - INT8, ~14.9 MB, 18-class)",
                modelAssetPath = "models/segformer_b0_q8.onnx"
            )
        }

        // SegFormer-B3 INT8 Clothes Parser (Prepared for segformer_b3_q8.onnx asset)
        register("segformer_b3_onnx") {
            OnnxSegFormerSegmenter(
                context = it,
                id = "segformer_b3_onnx",
                name = "SegFormer_B3_CP_ONNX_Q8",
                description = "SegFormer-B3 Clothes Parser (ONNX - INT8, ~45 MB, 18-class)",
                modelAssetPath = "models/segformer_b3_q8.onnx"
            )
        }

        register("mock_emulator") { MockGarmentSegmenter() }
    }

    /**
     * Registers a new segmenter engine into the application.
     */
    fun register(id: String, factory: (Context) -> GarmentSegmenter) {
        engineFactories[id] = factory
    }

    /**
     * Returns instances of all registered segmenter engines.
     */
    fun getAllEngines(context: Context): List<GarmentSegmenter> {
        return engineFactories.values.map { it(context) }
    }

    /**
     * Retrieves a specific engine by ID.
     */
    fun getEngine(id: String, context: Context): GarmentSegmenter? {
        return engineFactories[id]?.invoke(context)
    }
}
