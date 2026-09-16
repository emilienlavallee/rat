"""
Export a trained YOLO .pt to TFLite for the app's LiteRT backend.

The conversion goes .pt -> ONNX -> TF SavedModel -> .tflite, so it needs the
export toolchain on top of this project's deps. Install it in a throwaway venv:

  uv venv --python 3.11 /tmp/tfexport
  uv pip install --python /tmp/tfexport/bin/python \\
    ultralytics tensorflow onnx onnx2tf onnxslim sng4onnx onnx_graphsurgeon ai-edge-litert

Usage:
  /tmp/tfexport/bin/python export_tflite.py --weights phone_model_5/train/weights/best.pt
  /tmp/tfexport/bin/python export_tflite.py --weights best.pt --precision int8 --data /path/to/data.yaml

Pass --out to also copy the result into the app assets, e.g.
  --out ../app/app/src/main/assets/models/phone-model-5/model_fp32.tflite
"""

import argparse
import shutil
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Export YOLO weights to TFLite")
    parser.add_argument("--weights", required=True, help="Path to best.pt")
    parser.add_argument("--precision", choices=["fp32", "fp16", "int8"], default="fp32")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--data", default=None, help="data.yaml for int8 calibration (required for --precision int8)")
    parser.add_argument("--fraction", type=float, default=0.2, help="Fraction of the calibration set to use for int8")
    parser.add_argument("--out", default=None, help="Copy the exported model here (e.g. an app assets path)")
    args = parser.parse_args()

    weights = Path(args.weights).resolve()
    if not weights.exists():
        sys.exit(f"Weights not found: {weights}")

    if args.precision == "int8" and not args.data:
        sys.exit("--data is required for --precision int8 (path to the dataset's data.yaml)")

    data = None
    if args.data:
        data = Path(args.data).resolve()
        if not data.exists():
            sys.exit(f"Dataset config not found: {data}")

    try:
        from ultralytics import YOLO
    except ImportError:
        sys.exit("ultralytics not installed — see the venv setup in this file's docstring")

    export_kwargs = {"format": "tflite", "imgsz": args.imgsz}
    if args.precision == "fp16":
        export_kwargs["half"] = True
    elif args.precision == "int8":
        export_kwargs["int8"] = True
        export_kwargs["data"] = str(data)
        export_kwargs["fraction"] = args.fraction

    print(f"Exporting {weights.name} to {args.precision} TFLite at {args.imgsz}x{args.imgsz}...")
    model = YOLO(str(weights))
    exported = Path(model.export(**export_kwargs)).resolve()

    if not exported.exists():
        sys.exit(f"Export reported {exported} but the file is missing")

    print(f"\nExported to: {exported}")

    if args.out:
        dest = Path(args.out).resolve()
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(exported, dest)
        print(f"Copied to:   {dest}")

    print("Register the model in app/.../cv/ModelChoice.kt with tfliteAsset set to the asset filename.")


if __name__ == "__main__":
    main()
