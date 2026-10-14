# StylerApp Architecture & Design System

## Overview & Core Principles

This document defines the architectural guidelines and design patterns for the **StylerApp / HelloWorld** Android application. All new features, ML models, data persistence, and UI components **must strictly adhere** to the patterns established here.

### Key Principles
1. **Fully On-Device**: All ML inference, database storage, and image processing happen locally on the user's device.
2. **Clean Architecture & Layer Separation**: Clear separation between Domain (pure Kotlin), Data (Android framework, Room, storage), and UI (Compose).
3. **Open/Closed Principle (SOLID)**: System components (especially ML engines and data repositories) are **open for extension, closed for modification**.
4. **Unidirectional Data Flow (UDF)**: UI state flows down from ViewModel / StateFlow, and user actions flow up as events.

---

## Plug-and-Play ML Segmenter Subsystem

The segmentation pipeline is engineered using the **Registry Pattern** and **Strategy Pattern** to ensure new ML models (ML Kit, MediaPipe, custom TFLite, ONNX, etc.) can be added as **plug-and-play modules without modifying existing UI or screen logic**.

```
                       ┌─────────────────────────┐
                       │        Segmenter        │ (Interface)
                       │  - id, name, description│
                       │  - category             │
                       └────────────▲────────────┘
                                    │
           ┌────────────────────────┼────────────────────────┐
           │                        │                        │
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│   MlKitSegmenter    │  │ MediaPipeSegmenter  │  │   MockSegmenter     │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
           │                        │                        │
           └────────────────────────┴────────────────────────┘
                                    │
                       ┌────────────▼────────────┐
                       │    SegmenterRegistry    │ (Central Registry)
                       │  - register()           │
                       │  - getAllEngines()      │
                       └────────────▲────────────┘
                                    │
                       ┌────────────┴────────────┐
                       │   SegmenterTestScreen   │ (Dynamic UI Dropdown)
                       └─────────────────────────┘
```

### Component Contracts

#### 1. Interface: `Segmenter`
Every segmentation engine must implement [Segmenter](file:///Users/jvk/Workspace/AndroidStudio/seg-android/app/src/main/java/com/example/segandroid/segmenter/Segmenter.kt):
* `id: String` — Unique identifier key (e.g. `"mlkit_subject"`).
* `name: String` — Human-readable display name for UI dropdowns.
* `description: String` — Brief summary of model architecture/technology.
* `category: SegmenterCategory` — Broad grouping (e.g. `Fashion`) used by the two-level picker; defaults to `FASHION`.
* `suspend fun segment(inputBitmap: Bitmap): Result<SegmentResult>` — Asynchronous execution returning transparent foreground cutout and benchmark metadata.

#### 2. Result Model: `SegmentResult`
Output container [SegmentResult](file:///Users/jvk/Workspace/AndroidStudio/HelloWorld/app/src/main/java/com/example/myapplication/segmenter/SegmentResult.kt) holding:
* `foregroundBitmap: Bitmap` — Transparent background cutout.
* `executionTimeMs: Long` — Latency benchmark in milliseconds.
* `engineName: String` — Engine that produced the result.
* `details: Map<String, String>` — Optional model metadata (e.g., input resolution, model size, confidence threshold).

#### 3. Central Registry: `SegmenterRegistry`
Singleton [SegmenterRegistry](file:///Users/jvk/Workspace/AndroidStudio/HelloWorld/app/src/main/java/com/example/myapplication/segmenter/SegmenterRegistry.kt) managing engine providers:
* Maintains a map of `(Context) -> Segmenter` factory functions.
* Dynamically supplies `getAllEngines(context)` to UI dropdowns.

### How to Add a New ML Model (Developer Guide)
1. **Create Engine Class**: Implement `Segmenter` in `segmenter/` (e.g., `MediaPipeSegmenter.kt`).
2. **Register Engine**: In `SegmenterRegistry.init`, add `register("engine_id") { MediaPipeSegmenter(it) }`.
3. **Done**: The UI dropdown automatically discovers and displays the new model with zero UI code modifications!

---

## Layer Boundaries & Dependencies

```text
app/src/main/java/com/example/myapplication/
├── domain/
│   └── model/           # Pure Kotlin data classes (ClothingItem, Enums, ScoreBreakdown)
├── data/
│   ├── db/              # Room Entity, DAO, Converters, StylerDatabase
│   ├── image/           # On-device ImageStorage (internal storage saver)
│   └── repository/      # ClothingRepository mapping Entities <-> Domain Models
├── segmenter/           # Plug-and-Play Segmenter Subsystem
│   ├── Segmenter.kt
│   ├── SegmenterCategory.kt
│   ├── SegmentResult.kt
│   ├── SegmenterRegistry.kt
│   ├── MlKitSegmenter.kt
│   ├── MediaPipeSegmenter.kt
│   ├── OnnxSegFormerSegmenter.kt
│   └── MockSegmenter.kt
└── ui/                  # Compose UI Screens & ViewModels
    └── segmenter/
        └── SegmenterTestScreen.kt
```

### Layer Rules
* **Domain Layer**: Must remain pure Kotlin (no `android.*` dependencies).
* **Data Layer**: Manages Room DB, SQLite, local disk image saving, and TFLite model wrappers.
* **UI Layer**: Built using Jetpack Compose and Material 3. Interacts with Data/Domain solely through ViewModels and Coroutines.

---

## Architectural Change Log

| Date | Version | Change Description | Author |
| :--- | :--- | :--- | :--- |
| 2026-02 | 1.0.0 | Established initial Clean Architecture, Plug-and-Play Segmenter Subsystem (`GarmentSegmenter` interface, `SegmenterRegistry`, `SegmentResult` benchmark model), and Edge-to-Edge Compose scaffolding. | Core Dev |
| 2026-02 | 1.1.0 | Added Google MediaPipe Tasks Vision dependency (`com.google.mediapipe:tasks-vision`), implemented `MediaPipeGarmentSegmenter`, registered in `SegmenterRegistry`. | Core Dev |
| 2026-02 | 1.2.0 | Added ONNX Runtime for Android (`com.microsoft.onnxruntime:onnxruntime-android`), implemented `OnnxSegFormerSegmenter` (SegFormer Vision Transformer apparel parser), registered in `SegmenterRegistry`. | Core Dev |
| 2026-02 | 1.3.0 | Generalized beyond apparel: added `SegmenterCategory` (currently `Fashion`) with a two-level Category + Model picker; renamed `GarmentSegmenter` → `Segmenter` (and `MlKitGarmentSegmenter`/`MediaPipeGarmentSegmenter`/`MockGarmentSegmenter` → `MlKitSegmenter`/`MediaPipeSegmenter`/`MockSegmenter`). | Core Dev |
