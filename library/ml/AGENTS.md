# AGENTS.md — library/ml/ (':library:ml')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/ml/` + allowed shared modules. Do not root-scan.

- Gradle: :library:ml / dir: library/ml/
- Package roots present: (on-device inference runtimes — one package + Rust/native bridges)
- Entry files: per-model `*Handle` (no MainActivity/Route — library)
- Deps: (none — ONNX/LiteRT/ExecuTorch/Vulkan sessions live here)
- Metadata: metadata_data/library-ml.md (absent — libraries have none by design)
- Rust: yes / screenshotTest: no

## Overview
**Purpose:** All on-device inference — session wrappers for every runtime (ONNX, LiteRT, ExecuTorch, Vulkan compute) plus per-model handles: Whisper (speech), NLLB (translate), Gemma (assistant), Maia (chess), CLIP/faces/segmentation (photos/camera), Supertonic (TTS), OCR post. No vendor SDKs.
**How to use:** Pick the runtime session (`OnnxSessions` / `LiteRtSessions` / `ExecutorchSessions` / `VulkanSessions`), then the model handle (`WhisperHandle`, `NllbHandle`, `GemmaTypes`, `MaiaHandle`, `ClipHandle`, `SelfieSegmenter`, …). Pre/post via `OnnxPreprocess`, `OcrPost`, `SegmentationMask`.
**Entry points:** The `*Handle` for your model → its `*Sessions` runtime → `VulkanBridge`/`VulkanWire` if GPU.
**Gotchas:** Model files are large and versioned per consumer — never share weights across models without checking compatibility; GPU paths need device-specific testing.

## Key files
- com/vayunmathur/library/ml/OnnxSessions.kt
- com/vayunmathur/library/ml/LiteRtSessions.kt
- com/vayunmathur/library/ml/ExecutorchSessions.kt
- com/vayunmathur/library/ml/VulkanSessions.kt
- com/vayunmathur/library/ml/VulkanBridge.kt
- com/vayunmathur/library/ml/VulkanWire.kt
- com/vayunmathur/library/ml/WhisperHandle.kt
- com/vayunmathur/library/ml/NllbHandle.kt
- com/vayunmathur/library/ml/GemmaTypes.kt
- com/vayunmathur/library/ml/MaiaHandle.kt
- com/vayunmathur/library/ml/ClipHandle.kt
- com/vayunmathur/library/ml/Faces.kt
- com/vayunmathur/library/ml/SelfieSegmenter.kt
- com/vayunmathur/library/ml/SubjectSegmenter.kt
- com/vayunmathur/library/ml/SegmentationMask.kt
- com/vayunmathur/library/ml/SupertonicSynthesizer.kt
- com/vayunmathur/library/ml/TextRecognizer.kt
- com/vayunmathur/library/ml/TextRecognizerEt.kt
- com/vayunmathur/library/ml/EtTensors.kt
- com/vayunmathur/library/ml/OcrPost.kt
- com/vayunmathur/library/ml/OnnxPreprocess.kt

## Verify (this module only)
```
./gradlew :library:ml:compileDevKotlin
./gradlew :library:ml:lint
```
