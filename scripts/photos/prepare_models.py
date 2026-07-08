#!/usr/bin/env python3
"""Prepare the on-device ML model assets for the Photos app.

Produces three binaries the app loads from `photos/src/main/assets/`:

  1. BlazeFace face detector -> `blazeface.onnx`
     Downloaded as-is from the HuggingFace ONNX export `garavv/blazeface-onnx`
     (`blaze.onnx`, ~0.5 MB). Anchor decoding + NMS are baked into the graph
     (inputs: image[1,3,128,128] RGB in [0,1], conf_threshold, max_detections,
     iou_threshold; output [1,N,16] = normalised box + 6 landmarks). Plain fp32
     ONNX ops, so it runs on the ONNX Runtime Android package. Replaces the old
     MediaPipe face detector.

  2. U²-Net portable subject segmenter -> `u2netp.onnx`
     Downloaded as-is from `BritishWerewolf/U-2-Netp` (~4.6 MB, Apache-2.0).
     Salient-object detection for the editor's "select subject" tool. Input NCHW
     [1,3,320,320] RGB ImageNet-normalised; primary output [1,1,320,320] saliency
     (sigmoid). Replaces DeepLabV3 (which only knew the 20 Pascal-VOC classes),
     which — together with the BlazeFace swap — lets the app drop MediaPipe.

  3. EdgeFace face embedder (fp32) -> `edgeface.onnx`
     EdgeFace `edgeface-s-gamma-05` loaded from the transformers-format Hub
     mirror `anjith2006/edgeface` (bundles the model code + safetensors, so no
     GitHub checkout), exported to fp32 ONNX. Input NCHW [1,3,112,112]
     normalised (px-127.5)/127.5; output a 512-d embedding.

     NOT INT8-quantized on purpose: EdgeFace is a CNN, and dynamic quantization
     turns its Conv layers into `ConvInteger`, which the ONNX Runtime Android
     package cannot run (`ORT_NOT_IMPLEMENTED: ConvInteger`) — it would load fine
     on desktop but fail on-device, silently disabling face grouping.

     NOTE: EdgeFace ships under a NON-COMMERCIAL research licence. This project
     uses it deliberately (see the plan / FaceRecognizer docs). Swap it out if
     you need a permissive licence.

Semantic photo search (image/text CLIP embedding) is **no longer prepared here**:
it moved out of Photos into the OpenAssistant app, which downloads SigLIP2
(`onnx-community/siglip2-base-patch16-224-ONNX`) directly from HuggingFace at
runtime, on demand. Photos ships no CLIP assets.

The step runs a smoke inference and asserts the expected output before the file
is accepted, then prints the final on-disk size.

Usage:
    pip install -r requirements.txt
    python prepare_models.py

Needs `huggingface_hub`, `onnx`, `onnxruntime`, `numpy`, and `torch`. If your
environment lacks network access or torch, run this in an environment that has
them; the produced binary is all the app needs.
"""
from __future__ import annotations

import argparse
import os
import sys

# Repo layout: scripts/photos/prepare_models.py -> repo root is two levels up.
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS_DIR = os.path.join(REPO_ROOT, "photos", "src", "main", "assets")

# EdgeFace variant: "s" (small, gamma 0.5) is the size/accuracy sweet spot and
# outputs a 512-d embedding. We load the transformers-format mirror
# (anjith2006/edgeface), which bundles the model code + safetensors weights on
# the HuggingFace Hub, so no GitHub checkout is needed. Its preprocessor uses
# rescale 1/255 + normalize mean/std 0.5 == (px-127.5)/127.5, matching
# FaceRecognizer.embed(). See https://github.com/otroshi/edgeface for the source.
EDGEFACE_HF_REPO = "anjith2006/edgeface"
EDGEFACE_SUBFOLDER = "edgeface-s-gamma-05"
EDGEFACE_OUT = os.path.join(ASSETS_DIR, "edgeface.onnx")
FACE_INPUT_SIZE = 112
EMBED_DIM = 512

# BlazeFace face DETECTOR, downloaded as-is from the HuggingFace ONNX export
# `garavv/blazeface-onnx`. It bakes anchor decoding + NMS into the graph (inputs:
# image[1,3,128,128], conf_threshold, max_detections, iou_threshold; output
# [1,N,16] of normalised box + 6 landmarks). Plain fp32 ONNX ops only, so it runs
# on the ONNX Runtime Android package. Replaces the old MediaPipe detector.
BLAZEFACE_HF_REPO = "garavv/blazeface-onnx"
BLAZEFACE_SRC_FILE = "blaze.onnx"
BLAZEFACE_OUT = os.path.join(ASSETS_DIR, "blazeface.onnx")

# U²-Net portable: salient-object segmentation used by the editor's "select
# subject" tool. Downloaded as-is from the ONNX export `BritishWerewolf/U-2-Netp`
# (Apache-2.0, ~4.6 MB). Replaces DeepLabV3 (which only knew the 20 Pascal-VOC
# classes) so arbitrary subjects can be selected. Standard fp32 ONNX ops.
U2NETP_HF_REPO = "BritishWerewolf/U-2-Netp"
U2NETP_SRC_FILE = "onnx/model.onnx"
U2NETP_OUT = os.path.join(ASSETS_DIR, "u2netp.onnx")


def _mb(path: str) -> str:
    return f"{os.path.getsize(path) / (1024 * 1024):.1f} MB"


def prepare_u2netp() -> None:
    """Download the U²-Net portable segmenter and validate it in place."""
    import numpy as np
    import onnxruntime as ort
    import shutil
    from huggingface_hub import hf_hub_download

    print(f"[U2Netp] downloading {U2NETP_SRC_FILE} from {U2NETP_HF_REPO} ...")
    cached = hf_hub_download(repo_id=U2NETP_HF_REPO, filename=U2NETP_SRC_FILE)
    os.makedirs(ASSETS_DIR, exist_ok=True)
    shutil.copyfile(cached, U2NETP_OUT)
    print(f"[U2Netp] wrote {U2NETP_OUT} ({_mb(U2NETP_OUT)})")

    sess = ort.InferenceSession(U2NETP_OUT, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    out = sess.run(None, {in_name: np.zeros((1, 3, 320, 320), dtype=np.float32)})[0]
    assert out.shape[-1] == 320 and out.shape[-2] == 320, f"[U2Netp] unexpected output {out.shape}"
    print(f"[U2Netp] smoke inference OK, output shape {out.shape}")


def prepare_blazeface() -> None:
    """Download the BlazeFace ONNX detector and validate it in place."""
    import numpy as np
    import onnxruntime as ort
    import shutil
    from huggingface_hub import hf_hub_download

    print(f"[BlazeFace] downloading {BLAZEFACE_SRC_FILE} from {BLAZEFACE_HF_REPO} ...")
    cached = hf_hub_download(repo_id=BLAZEFACE_HF_REPO, filename=BLAZEFACE_SRC_FILE)
    os.makedirs(ASSETS_DIR, exist_ok=True)
    shutil.copyfile(cached, BLAZEFACE_OUT)
    print(f"[BlazeFace] wrote {BLAZEFACE_OUT} ({_mb(BLAZEFACE_OUT)})")

    sess = ort.InferenceSession(BLAZEFACE_OUT, providers=["CPUExecutionProvider"])
    out = sess.run(None, {
        "image": np.zeros((1, 3, 128, 128), dtype=np.float32),
        "conf_threshold": np.array([0.5], dtype=np.float32),
        "max_detections": np.array([25], dtype=np.int64),
        "iou_threshold": np.array([0.3], dtype=np.float32),
    })[0]
    assert out.shape[-1] == 16, f"[BlazeFace] expected 16 cols per box, got {out.shape}"
    print(f"[BlazeFace] smoke inference OK, output shape {out.shape}")


def _load_edgeface_torch():
    """Load the pretrained EdgeFace model (eval mode) from the HuggingFace mirror.

    `anjith2006/edgeface` ships the model definition (`modeling_edgeface.py`) and
    safetensors weights on the Hub, so `trust_remote_code=True` builds the model
    without a GitHub checkout. Returns an nn.Module whose forward takes an NCHW
    `[N,3,112,112]` float tensor and yields a 512-d embedding.
    """
    import torch
    from torch import nn
    from transformers import AutoModel

    print(f"[EdgeFace] loading '{EDGEFACE_SUBFOLDER}' from {EDGEFACE_HF_REPO} ...")
    base = AutoModel.from_pretrained(
        EDGEFACE_HF_REPO,
        subfolder=EDGEFACE_SUBFOLDER,
        trust_remote_code=True,
    )
    base.eval()

    class EmbeddingWrapper(nn.Module):
        """Normalise the model output to a single 512-d embedding tensor."""

        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, pixel_values):
            out = self.model(pixel_values)
            if isinstance(out, torch.Tensor):
                return out
            for attr in ("image_embeds", "embeddings", "pooler_output", "last_hidden_state"):
                val = getattr(out, attr, None)
                if val is not None:
                    return val
            if isinstance(out, (tuple, list)):
                return out[0]
            raise TypeError(f"Unrecognised EdgeFace output type: {type(out)}")

    wrapper = EmbeddingWrapper(base)
    wrapper.eval()

    # Probe the output shape so we fail fast on a wrong variant/wrapper.
    with torch.no_grad():
        probe = wrapper(torch.zeros(1, 3, FACE_INPUT_SIZE, FACE_INPUT_SIZE))
    assert probe.shape[-1] == EMBED_DIM, (
        f"[EdgeFace] expected {EMBED_DIM}-d embedding, got {tuple(probe.shape)}"
    )
    print(f"[EdgeFace] loaded, embedding shape {tuple(probe.shape)}")
    return wrapper


def prepare_edgeface() -> None:
    """Export EdgeFace to fp32 ONNX and validate.

    We deliberately do **not** INT8-quantize. EdgeFace is a CNN, and
    `quantize_dynamic` turns its Conv layers into `ConvInteger` ops, which the
    ONNX Runtime **Android** package has no kernel for — the model then throws
    `ORT_NOT_IMPLEMENTED: ConvInteger` at session creation on-device and face
    grouping silently produces nothing. fp32 is a few MB larger but loads
    everywhere. (If you need the size back, use *static* quantization with
    calibration images so Conv becomes the supported `QLinearConv`, not
    `ConvInteger` — dynamic quant is only safe for MatMul/Transformer models.)
    """
    import numpy as np
    import onnxruntime as ort
    import torch

    model = _load_edgeface_torch()

    os.makedirs(ASSETS_DIR, exist_ok=True)
    dummy = torch.zeros(1, 3, FACE_INPUT_SIZE, FACE_INPUT_SIZE, dtype=torch.float32)
    print(f"[EdgeFace] exporting fp32 ONNX -> {EDGEFACE_OUT} ...")
    # Static [1,3,112,112] via the legacy (TorchScript) exporter: the app only
    # ever embeds one crop at a time, and a static graph keeps the op set small.
    torch.onnx.export(
        model,
        dummy,
        EDGEFACE_OUT,
        input_names=["input"],
        output_names=["embedding"],
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    print(f"[EdgeFace] wrote {EDGEFACE_OUT} ({_mb(EDGEFACE_OUT)})")

    sess = ort.InferenceSession(EDGEFACE_OUT, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    dummy = np.zeros((1, 3, FACE_INPUT_SIZE, FACE_INPUT_SIZE), dtype=np.float32)
    out = sess.run(None, {in_name: dummy})[0]
    assert out.shape[-1] == EMBED_DIM, f"[EdgeFace] expected {EMBED_DIM}-d output, got {out.shape}"
    print(f"[EdgeFace] smoke inference OK, output shape {out.shape}")


def main() -> int:
    argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    ).parse_args()

    try:
        prepare_blazeface()
        prepare_u2netp()
        prepare_edgeface()
    except Exception as e:  # noqa: BLE001
        print(f"[prepare] FAILED: {e}", file=sys.stderr)
        print("\n=== Summary ===")
        for path, label in ((BLAZEFACE_OUT, "blazeface.onnx"), (U2NETP_OUT, "u2netp.onnx"), (EDGEFACE_OUT, "edgeface.onnx")):
            state = _mb(path) if os.path.exists(path) else "missing"
            print(f"  {label:26s} {state}")
        return 1

    print("\n=== Summary ===")
    for path, label in ((BLAZEFACE_OUT, "blazeface.onnx"), (U2NETP_OUT, "u2netp.onnx"), (EDGEFACE_OUT, "edgeface.onnx")):
        state = _mb(path) if os.path.exists(path) else "missing"
        print(f"  {label:26s} {state}")
    print("\nModels prepared.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
