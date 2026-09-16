"""
Train YOLO26n on a labeled ball dataset exported from Label Studio.

Export from Label Studio:
  Project → Export → YOLO with Images

Usage:
  uv run train.py --data /path/to/dataset [--epochs 100] [--imgsz 640] [--out runs/ball]
"""

import argparse
import random
import shutil
import sys
from pathlib import Path


def make_split(data_root: Path, val_ratio: float = 0.2) -> None:
    """Split flat images/ + labels/ into train/val subfolders (80/20 by default)."""
    images = sorted((data_root / "images").glob("*.*"))
    if not images:
        sys.exit(f"No images found in {data_root / 'images'}")

    random.seed(42)
    random.shuffle(images)
    n_val = max(1, int(len(images) * val_ratio))
    val_set = set(img.name for img in images[:n_val])

    for split in ("train", "val"):
        (data_root / "images" / split).mkdir(exist_ok=True)
        (data_root / "labels" / split).mkdir(exist_ok=True)

    for img in images:
        split = "val" if img.name in val_set else "train"
        shutil.copy2(img, data_root / "images" / split / img.name)
        label = data_root / "labels" / (img.stem + ".txt")
        if label.exists():
            shutil.copy2(label, data_root / "labels" / split / label.name)

    print(f"Split: {len(images) - n_val} train / {n_val} val")


def main() -> None:
    parser = argparse.ArgumentParser(description="Train YOLO26n on ball dataset")
    parser.add_argument("--data", required=True, help="Path to dataset root (contains images/ and labels/)")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--out", default=None, help="Output directory (default: <dataset>/training)")
    args = parser.parse_args()

    data_root = Path(args.data).resolve()
    if not data_root.exists():
        sys.exit(f"Dataset path not found: {data_root}")

    out_dir = Path(args.out).resolve() if args.out else data_root / "training"

    # Auto-split if no train/val subfolders exist yet
    if not (data_root / "images" / "train").exists():
        print("No train/val split found — creating 80/20 split...")
        make_split(data_root)

    yaml_path = data_root / "data.yaml"
    yaml_path.write_text(
        f"path: {data_root}\n"
        "train: images/train\n"
        "val: images/val\n"
        "nc: 1\n"
        "names:\n"
        "  0: ball\n"
    )
    print(f"Wrote {yaml_path}")

    try:
        from ultralytics import YOLO
    except ImportError:
        sys.exit("ultralytics not installed — run: uv add ultralytics")

    model = YOLO("yolo26n.pt")

    result = model.train(
        data=str(yaml_path),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        project=str(out_dir),
        name="train",
        exist_ok=True,
    )

    best = Path(result.save_dir) / "weights" / "best.pt"
    if not best.exists():
        sys.exit("Training finished but best.pt not found")

    print(f"\nTraining done. Best weights: {best}")
    print("Exporting to NCNN...")

    trained = YOLO(str(best))
    trained.export(format="ncnn", imgsz=args.imgsz, end2end=False)

    ncnn_dir = best.parent / "best_ncnn_model"
    print(f"\nExported to: {ncnn_dir}")
    print("Copy best.param and best.bin to the RPi and load with --model best.param")


if __name__ == "__main__":
    main()
