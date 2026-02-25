#!/usr/bin/env python3
"""
Download GantMan nsfw_model and convert to ONNX for Pavlova.

Usage:
    python scripts/convert_model.py

Requirements:
    pip install tflite2onnx onnx

The script will:
1. Download nsfw_mobilenet_v2_140_224.zip from GitHub releases
2. Extract the TFLite model
3. Convert to ONNX format
4. Copy to android/app/src/main/assets/
"""

import os
import sys
import zipfile
import shutil
from pathlib import Path

try:
    import tflite2onnx
    import onnx
except ImportError:
    print("Missing dependencies. Install with:")
    print("  pip install tflite2onnx onnx")
    sys.exit(1)

SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
ASSETS_DIR = PROJECT_ROOT / "android" / "app" / "src" / "main" / "assets"

MODEL_ZIP = "nsfw_mobilenet_v2_140_224.zip"
MODEL_DIR = "mobilenet_v2_140_224"
TFLITE_FILE = "saved_model.tflite"
ONNX_FILE = "nsfw_mobilenet_v2_140_224.onnx"

RELEASE_URL = f"https://github.com/GantMan/nsfw_model/releases/download/1.1.0/{MODEL_ZIP}"


def download_model():
    """Download model zip from GitHub releases."""
    zip_path = SCRIPT_DIR / MODEL_ZIP
    if zip_path.exists():
        print(f"[OK] Model zip already downloaded: {zip_path}")
        return zip_path

    print(f"Downloading {MODEL_ZIP} from GitHub releases...")
    import urllib.request
    urllib.request.urlretrieve(RELEASE_URL, str(zip_path))
    size_mb = zip_path.stat().st_size / (1024 * 1024)
    print(f"[OK] Downloaded: {size_mb:.1f} MB")
    return zip_path


def extract_model(zip_path):
    """Extract TFLite model from zip."""
    tflite_path = SCRIPT_DIR / MODEL_DIR / TFLITE_FILE
    if tflite_path.exists():
        print(f"[OK] TFLite model already extracted: {tflite_path}")
        return tflite_path

    print("Extracting model...")
    with zipfile.ZipFile(str(zip_path), 'r') as z:
        z.extractall(str(SCRIPT_DIR))
    print(f"[OK] Extracted to {SCRIPT_DIR / MODEL_DIR}")
    return tflite_path


def convert_to_onnx(tflite_path):
    """Convert TFLite to ONNX."""
    onnx_path = SCRIPT_DIR / ONNX_FILE
    if onnx_path.exists():
        print(f"[OK] ONNX model already exists: {onnx_path}")
        return onnx_path

    print("Converting TFLite -> ONNX...")
    tflite2onnx.convert(str(tflite_path), str(onnx_path))
    size_mb = onnx_path.stat().st_size / (1024 * 1024)
    print(f"[OK] ONNX model created: {size_mb:.1f} MB")
    return onnx_path


def verify_model(onnx_path):
    """Verify ONNX model structure."""
    print("Verifying ONNX model...")
    model = onnx.load(str(onnx_path))

    for inp in model.graph.input:
        shape = [d.dim_value if d.dim_value else d.dim_param
                 for d in inp.type.tensor_type.shape.dim]
        print(f"  Input:  {inp.name}, shape={shape}")

    for out in model.graph.output:
        shape = [d.dim_value if d.dim_value else d.dim_param
                 for d in out.type.tensor_type.shape.dim]
        print(f"  Output: {out.name}, shape={shape}")

    print(f"  Opset:  {model.opset_import[0].version}")
    print("[OK] Model verified")


def copy_to_assets(onnx_path):
    """Copy ONNX model to Android assets."""
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    dest = ASSETS_DIR / ONNX_FILE
    shutil.copy2(str(onnx_path), str(dest))
    print(f"[OK] Copied to {dest}")


def main():
    print("=" * 60)
    print("GantMan NSFW Model → ONNX Converter for Pavlova")
    print("=" * 60)
    print()

    os.chdir(str(SCRIPT_DIR))

    zip_path = download_model()
    tflite_path = extract_model(zip_path)
    onnx_path = convert_to_onnx(tflite_path)
    verify_model(onnx_path)
    copy_to_assets(onnx_path)

    print()
    print("Done! Model is ready at:")
    print(f"  {ASSETS_DIR / ONNX_FILE}")


if __name__ == "__main__":
    main()
