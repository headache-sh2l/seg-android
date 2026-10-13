# seg-android

An on-device **garment segmentation workbench** for Android. Swap between several
segmentation engines, run them side by side, and benchmark their runtime and memory
cost — all offline, on the GPU/CPU of the device.

Built with Kotlin + Jetpack Compose (Material 3).

---

## Features

**Segment** — pick an engine, pick a photo, get a cut-out. Tap the result to inspect it
full-screen.

**Benchmark** — select any subset of engines, choose an iteration count
(`5 / 10 / 100 / 200 / 500 / 1000`) and run them. Each engine is warmed up first, then
measured. You get, per model:

| Runtime | Cost to run |
|---|---|
| avg, min, max, std dev | avg memory |
| p50, p95, p99 | peak memory |

A **Stop** button halts the run *after* the in-flight iteration and still produces a
report from whatever completed. Results can be exported as CSV and shared.

**History** — the last **10 benchmark runs** and **20 segment runs** are kept on device
(older ones are pruned automatically). Tap any entry to reopen the full report, or tap a
thumbnail to view the output full-screen. Benchmark CSVs can be re-shared from here.

---

## Requirements

| | |
|---|---|
| Android Studio | recent (AGP 8.x) |
| JDK | 11+ |
| Android SDK | compileSdk 37, **minSdk 34** (a physical device or API 34+ emulator) |
| Device | arm64 recommended; the TFLite/ONNX runtimes are heavy |

---

## Quick start

```bash
git clone git@github-workspace:headache-sh2l/seg-android.git
cd seg-android
./scripts/download_models.sh     # see below — models are NOT in the repo
```

Then open the folder in Android Studio and Run. Or from the CLI:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

---

## Getting the models

> [!IMPORTANT]
> The model binaries are **not committed** to this repo. They total ~247 MB, and
> `clothseg.tflite` alone is 176 MB, which exceeds GitHub's 100 MB per-file hard limit.

Put them in **`app/src/main/assets/models/`**. Expected files:

| File | Size | Used by |
|---|---|---|
| `segformer_clothes.onnx` | 28.6 MB | SegFormer_B2_CP_ONNX_Q8 |
| `segformer_b0_q8.onnx` | 4.4 MB | SegFormer_B0_CP_ONNX_Q8 |
| `segformer_b3_q8.onnx` | 49 MB | SegFormer_B3_CP_ONNX_Q8 |
| `clothseg.tflite` | 176 MB | U2Net_Cloth_LiteRT_v1 |

**Either:**

1. Run `./scripts/download_models.sh` — set the `URL_*` variables at the top of that
   script to your sources first (they are intentionally blank in the repo), **or**
2. Drop the four files into `app/src/main/assets/models/` by hand and run the script to
   verify them (it checks names and exact byte sizes).

`MLKit_Subject_Single_v1` needs **no** asset — its model comes from Google Play Services.
`Mock_Segmenter_V1` is a synthetic centre-cutout used for fast, dependency-free testing.

The app **builds fine without the models**; only the engines that need a missing file will
fail at runtime.

---

## Engines

| Dropdown entry | Class | Backend | Asset |
|---|---|---|---|
| `MLKit_Subject_Single_v1` | `MlKitGarmentSegmenter` | ML Kit subject segmentation | — (Play Services) |
| `U2Net_Cloth_LiteRT_v1` | `MediaPipeGarmentSegmenter` | raw TFLite `Interpreter` | `clothseg.tflite` |
| `SegFormer_B2_CP_ONNX_Q8` | `OnnxSegFormerSegmenter` | ONNX Runtime | `segformer_clothes.onnx` |
| `SegFormer_B0_CP_ONNX_Q8` | `OnnxSegFormerSegmenter` | ONNX Runtime | `segformer_b0_q8.onnx` |
| `SegFormer_B3_CP_ONNX_Q8` | `OnnxSegFormerSegmenter` | ONNX Runtime | `segformer_b3_q8.onnx` |
| `Mock_Segmenter_V1` | `MockGarmentSegmenter` | none | — |

Add your own by registering a factory in `SegmenterRegistry`:

```kotlin
register("my_engine") { MyGarmentSegmenter(it) }
```

Any class implementing `GarmentSegmenter` (`id`, `name`, `description`, `suspend segment()`)
shows up automatically in both the Segment and Benchmark screens.

> [!NOTE]
> Naming gotcha: `MediaPipeGarmentSegmenter` does **not** use MediaPipe — it drives a plain
> TensorFlow Lite `Interpreter`, and `clothseg.tflite` is the U2Net "cloth segmentation"
> model.

---

## Project layout

```
app/src/main/java/com/example/segandroid/
├── MainActivity.kt                 # Segment / Benchmark / History tabs
├── segmenter/
│   ├── GarmentSegmenter.kt         # the plug-in interface
│   ├── SegmenterRegistry.kt        # registers every engine
│   ├── *GarmentSegmenter.kt        # the concrete engines
│   ├── BenchmarkEngine.kt          # warm-up, iteration loop, stats
│   ├── BenchmarkResult.kt          # sample / stats / report models
│   ├── HistoryStore.kt             # persistence, thumbnails, 10+20 retention
│   ├── *Screen.kt                  # Compose UI
│   ├── ImageViewer.kt              # full-screen output inspection
│   └── Sharing.kt                  # FileProvider share helper
└── ui/theme/                       # Material 3 theme
```

---

## Notes on storage

Runs are persisted under the app's external files dir:

```
Android/data/com.example.segandroid/files/Pictures/segmenter_runs/
├── bench_<runId>.csv                    # a benchmark report
├── bench_<runId>_<index>_<engine>.png   # per-model sample
└── seg_<runId>_<durationMs>_<engine>.png
```

- Thumbnails are downscaled to **512 px** and stored as WebP (~36 KB each), so a full
  history stays under ~1 MB.
- Retention is **10 benchmark runs + 20 segment runs**; older runs are deleted on write.
- These are app-private: use the in-app **Share CSV** button to get a report out, or
  *History → Clear all* to wipe.
- Benchmark memory figures are per-iteration deltas of Java + native heap and are
  indicative, not exact (see `BenchmarkEngine.currentAllocatedBytes()`).

---

## Notes on benchmarking methodology

- Engines run **sequentially**, so they don't compete for CPU and skew each other.
- Every engine gets one **warm-up** pass before measurement (JIT / lazy model loading).
- Only the **first successful output** is retained per model; all other per-iteration
  bitmaps are recycled immediately. A full-resolution result is tens of MB, so retaining
  every iteration would exhaust memory — this was a real bug worth not reintroducing.
- Large models will make long runs (e.g. 1000 iterations) take a long time. Start small.
