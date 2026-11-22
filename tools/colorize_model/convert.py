"""Converts Zhang et al.'s SIGGRAPH 2017 colorization network (automatic mode) to TFLite.

Input:  L channel of CIE Lab, 0..100, 256 x 256 (one channel).
Output: a and b channels of CIE Lab (about -110..110), 256 x 256.

The TFLite file uses 8-bit weights (dynamic range quantisation). The script checks the TFLite
result against PyTorch on a test photo and fails if they differ too much.
"""
import argparse
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image

SIZE = 256


def srgb_to_lab(rgb):
    """rgb: float array 0..1 (H, W, 3) -> Lab (D65)."""
    c = np.where(rgb > 0.04045, ((rgb + 0.055) / 1.055) ** 2.4, rgb / 12.92)
    m = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = c @ m.T
    xyz /= np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    L = 116 * f[..., 1] - 16
    a = 500 * (f[..., 0] - f[..., 1])
    b = 200 * (f[..., 1] - f[..., 2])
    return np.stack([L, a, b], -1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    sys.path.insert(0, args.repo)

    import torch
    import colorizers

    net = colorizers.siggraph17(pretrained=True).eval()

    class Auto(torch.nn.Module):
        """Automatic colorization: no colour hints."""
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, l):
            return self.m(l)

    model = Auto(net).eval()
    dummy = torch.zeros(1, 1, SIZE, SIZE) + 50.0
    onnx_path = os.path.join(args.out, "colorize.onnx")
    torch.onnx.export(model, dummy, onnx_path, opset_version=13, input_names=["l"], output_names=["ab"])

    tf_dir = os.path.join(args.out, "tf")
    subprocess.check_call(["onnx2tf", "-i", onnx_path, "-o", tf_dir, "-odrqt", "-n"])
    cands = glob.glob(os.path.join(tf_dir, "*dynamic_range_quant.tflite"))
    if not cands:
        raise SystemExit("No dynamic range quantised model produced: " + str(os.listdir(tf_dir)))
    tfl = os.path.join(args.out, "colorize-v1.tflite")
    shutil.copy(cands[0], tfl)

    # Check against PyTorch on a test photo.
    imgs = sorted(glob.glob(os.path.join(args.repo, "imgs", "*.jpg")))
    img = Image.open(imgs[0]).convert("RGB").resize((SIZE, SIZE), Image.BICUBIC)
    lab = srgb_to_lab(np.asarray(img).astype(np.float64) / 255.0)
    L = lab[..., 0].astype(np.float32)
    with torch.no_grad():
        ref = model(torch.from_numpy(L)[None, None]).numpy()[0]  # (2, H, W)

    import tensorflow as tf
    it = tf.lite.Interpreter(model_path=tfl, num_threads=4)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    in_shape = list(inp["shape"])
    nhwc = in_shape[-1] == 1
    x = L[None, :, :, None] if nhwc else L[None, None]
    it.set_tensor(inp["index"], x.astype(np.float32))
    it.invoke()
    y = it.get_tensor(out["index"])[0]
    out_shape = list(out["shape"])
    if out_shape[-1] == 2:
        y = np.transpose(y, (2, 0, 1))
    err = float(np.mean(np.abs(y - ref)))
    mag = float(np.mean(np.abs(ref)))
    print(f"input {in_shape} output {out_shape} mean |ab| {mag:.2f} mean error {err:.3f}")
    if mag < 1.0:
        raise SystemExit("Reference output is almost grey: weights did not load?")
    if err > max(3.0, mag * 0.25):
        raise SystemExit("TFLite result differs too much from PyTorch")

    data = open(tfl, "rb").read()
    manifest = {
        "name": "colorize-v1",
        "file": "colorize-v1.tflite",
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "inputSize": SIZE,
        "layout": "NHWC" if nhwc else "NCHW",
        "input": "CIE Lab L, 0..100",
        "output": "CIE Lab a, b",
        "license": "BSD 2-Clause, (c) 2016 Richard Zhang, Phillip Isola, Alexei A. Efros",
        "meanError": err,
    }
    json.dump(manifest, open(os.path.join(args.out, "colorize-v1.json"), "w"), indent=2)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
