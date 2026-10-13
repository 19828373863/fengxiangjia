"""Prepare local photos or video frames; this does not reconstruct a room."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / ".deps"))
import cv2
import numpy as np


def prepare(output: Path, *, video: Path | None = None, photos: list[Path] | None = None,
            interval: float = .5, limit: int = 80, blur_threshold: float = 80.0):
    if not math.isfinite(interval) or not .2 <= interval <= 10 or not 6 <= limit <= 200:
        raise ValueError("interval must be between 0.2 and 10 seconds; image limit must be between 6 and 200")
    if not math.isfinite(blur_threshold) or blur_threshold < 0:
        raise ValueError("blur threshold must be finite and nonnegative")
    if bool(video) == bool(photos):
        raise ValueError("choose exactly one video or a list of photos")
    if output.exists():
        raise FileExistsError("output already exists; choose a new experiment directory")
    output.mkdir(parents=True, exist_ok=False)
    images = output / "images"
    images.mkdir()
    report = {"version": 1, "status": "started", "inputKind": "video" if video else "photos",
              "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "roomReconstructed": False, "retained": [], "rejected": [],
              "heuristicOnly": True, "imageLimit": limit, "warnings": [
                  "Quality screening does not prove overlap, useful parallax or SfM registration.",
                  "Use one fixed lens/zoom; prepare camera intrinsics and multi-view corner annotations.",
                  "Metric output requires an independently measured reference length."]}
    reference_size = None
    previous = None
    hashes = set()
    cap = None

    def accept(image, label, timestamp=None):
        nonlocal reference_size, previous
        if image is None:
            report["rejected"].append({"input": label, "reason": "image could not be decoded"})
            return
        height, width = image.shape[:2]
        size = [width, height]
        if reference_size is not None and size != reference_size:
            report["rejected"].append({"input": label, "reason": "different image size; shared camera intrinsics require matching dimensions"})
            return
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        factor = min(1.0, 640.0 / width)
        test = cv2.resize(gray, (max(1, round(width * factor)), max(1, round(height * factor))))
        sharpness = float(cv2.Laplacian(test, cv2.CV_64F).var())
        if sharpness < blur_threshold:
            report["rejected"].append({"input": label, "reason": "low sharpness or texture", "score": sharpness})
            return
        digest = hashlib.sha256(image.tobytes()).hexdigest()
        thumb = cv2.resize(gray, (160, 120)).astype(np.float32)
        difference = None if previous is None else float(np.sqrt(np.mean((thumb - previous) ** 2)))
        if digest in hashes or (difference is not None and difference < 3.0):
            report["rejected"].append({"input": label, "reason": "duplicate or nearly unchanged frame"})
            return
        name = f"frame-{len(report['retained']):04d}.png"
        if not cv2.imwrite(str(images / name), image):
            raise IOError("prepared image could not be saved")
        reference_size, previous = size, thumb
        hashes.add(digest)
        report["retained"].append({"image": name, "input": label, "timestampSeconds": timestamp,
                                   "sharpness": sharpness, "differenceFromPrevious": difference,
                                   "decodedPixelSha256": digest})

    try:
        if photos:
            for index, path in enumerate(photos):
                if len(report["retained"]) >= limit:
                    report["warnings"].append("Input list exceeded the image limit; remaining photos were not processed.")
                    break
                # imdecode supports Windows Unicode paths, unlike some VideoCapture backends.
                accept(cv2.imdecode(np.frombuffer(path.read_bytes(), np.uint8), cv2.IMREAD_COLOR), f"photo-{index:04d}")
        else:
            cap = cv2.VideoCapture(str(video))
            fps = float(cap.get(cv2.CAP_PROP_FPS))
            count = float(cap.get(cv2.CAP_PROP_FRAME_COUNT))
            if not cap.isOpened() or not math.isfinite(fps) or fps <= 0 or not math.isfinite(count) or count <= 0:
                raise ValueError("video could not be decoded with a valid frame rate and length")
            duration = count / fps
            if duration > 180:
                raise ValueError("prototype accepts videos up to 180 seconds; use a shorter capture")
            report["durationSeconds"] = duration
            report["samplingIntervalSeconds"] = interval
            for index, seconds in enumerate(np.arange(0, duration, interval)):
                if len(report["retained"]) >= limit:
                    report["warnings"].append("Image limit reached; later video frames were not processed.")
                    break
                cap.set(cv2.CAP_PROP_POS_MSEC, float(seconds) * 1000)
                ok, image = cap.read()
                accept(image if ok else None, f"video-sample-{index:04d}", float(seconds))
        report["imageSize"] = reference_size
        if len(report["retained"]) < 6:
            raise ValueError("fewer than six usable images; capture more clear overlapping views while changing position")
        report["status"] = "prepared_images_only"
    except Exception as error:
        report["status"] = "rejected"
        report["failureReason"] = f"{type(error).__name__}: {error}"
    finally:
        if cap is not None:
            cap.release()
        (output / "preparation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--video", type=Path)
    source.add_argument("--photos", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval", type=float, default=.5)
    parser.add_argument("--limit", type=int, default=80)
    parser.add_argument("--blur-threshold", type=float, default=80)
    args = parser.parse_args()
    try:
        report = prepare(args.output, video=args.video, photos=args.photos, interval=args.interval,
                         limit=args.limit, blur_threshold=args.blur_threshold)
        print(json.dumps({"status": report["status"], "retained": len(report["retained"]),
                          "rejected": len(report["rejected"]), "roomReconstructed": False,
                          "failureReason": report.get("failureReason")}, ensure_ascii=False))
        return 0 if report["status"] == "prepared_images_only" else 2
    except Exception as error:
        print(f"Preparation rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
