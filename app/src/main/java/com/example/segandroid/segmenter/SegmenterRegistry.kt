package com.example.segandroid.segmenter

import android.content.Context

/**
 * Central registry for plug-and-play Segmenter models.
 */
object SegmenterRegistry {

    private val engineFactories = mutableMapOf<String, (Context) -> Segmenter>()

    init {
        // Register default built-in engines
        register("mlkit_subject") { MlKitSegmenter() }
        register("u2net_cloth_litert") { MediaPipeSegmenter(it) }

        // SegFormer-B2 INT8 — mattmdjaga/segformer_b2_clothes (ATR-18)
        register("segformer_b2_onnx") {
            OnnxSegFormerSegmenter(
                context = it,
                id = "segformer_b2_onnx",
                name = "SegFormer_B2_CP_ONNX_Q8",
                description = "SegFormer-B2 Clothes Parser (ONNX - INT8, 27.3 MB, 18-class)",
                labels = ModelLabels.ATR_18,
                modelAssetPath = "models/segformer_clothes.onnx"
            )
        }

        // SegFormer-B0 INT8 — Xenova/segformer_b0_clothes (ATR-18)
        register("segformer_b0_onnx") {
            OnnxSegFormerSegmenter(
                context = it,
                id = "segformer_b0_onnx",
                name = "SegFormer_B0_CP_ONNX_Q8",
                description = "SegFormer-B0 Clothes Parser (ONNX - INT8, ~14.9 MB, 18-class)",
                labels = ModelLabels.ATR_18,
                modelAssetPath = "models/segformer_b0_q8.onnx"
            )
        }

        register("mock_emulator") { MockSegmenter() }
    }

    /**
     * Registers a new segmenter engine into the application.
     */
    fun register(id: String, factory: (Context) -> Segmenter) {
        engineFactories[id] = factory
    }

    /**
     * Returns instances of all registered segmenter engines.
     */
    fun getAllEngines(context: Context): List<Segmenter> {
        return engineFactories.values.map { it(context) }
    }
}
