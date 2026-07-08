# Photos model assets

`prepare_models.py` regenerates the on-device ML binaries the Photos app ships in
`photos/src/main/assets/`. Run it whenever you want to refresh the models; the app
loads whatever is on disk and is otherwise inert (no crash) if an asset is missing.

## What it produces

| Asset | Source | Format | ~Size |
| --- | --- | --- | --- |
| `blazeface.onnx` | BlazeFace ONNX (garavv/blazeface-onnx, `blaze.onnx`) | fp32, decode+NMS in-graph | ~0.5 MB |
| `u2netp.onnx` | U²-Net portable (BritishWerewolf/U-2-Netp) | fp32 salient-object | ~4.6 MB |
| `edgeface.onnx` | EdgeFace `edgeface-s-gamma-05` (anjith2006/edgeface, transformers mirror) | fp32 | ~16 MB |

> **Not INT8-quantized on purpose.** EdgeFace is a CNN; `quantize_dynamic` turns
> its Conv layers into `ConvInteger`, which the ONNX Runtime **Android** package
> can't run (`ORT_NOT_IMPLEMENTED: ConvInteger`) — it loads on desktop but fails
> on-device, silently disabling face grouping. Use *static* quantization
> (calibration images → supported `QLinearConv`) if you need the size back.

Everything runs on ONNX Runtime now: face **detection** (BlazeFace), face
**embedding** (EdgeFace), and subject **segmentation** (U²-Net). The app no longer
depends on MediaPipe at all.

### Semantic (CLIP) search moved to OpenAssistant

Photos no longer bundles any CLIP/SigLIP assets. Semantic image/text search is
served by the **OpenAssistant** app, which downloads SigLIP2
(`onnx-community/siglip2-base-patch16-224-ONNX`) directly from HuggingFace at
runtime, on demand. There is nothing to prepare here for search — see
`SiglipEmbedder.kt` in the openassistant module.

## Usage

```bash
cd scripts/photos
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

python prepare_models.py            # EdgeFace
```

The script exports the model, runs a smoke inference (asserting the 512-d
output), writes it into `photos/src/main/assets/`, and prints the final on-disk
size.

## Notes

- **Network + torch:** EdgeFace needs network access (HuggingFace) plus `torch` +
  `transformers` (the `anjith2006/edgeface` mirror bundles the model code on the
  Hub and is loaded with `trust_remote_code=True`, so no GitHub checkout is
  required). If the step can't run in your environment, run it elsewhere and copy
  the resulting binary in.
- **EdgeFace licence:** EdgeFace is released for **non-commercial research**.
  This project uses it deliberately (see `FaceRecognizer` docs). If you need a
  permissive licence, substitute a different 112x112 ArcFace-style embedder and
  update `FaceRecognizer.kt` (`EMBEDDER_ASSET`, normalisation, `EMBEDDER_VERSION`).
- **Version bumps:** changing the face model/preprocessing requires bumping
  `FaceRecognizer.EMBEDDER_VERSION` so the app rebuilds face clusters.
