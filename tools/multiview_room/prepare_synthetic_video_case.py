"""Fixture-only annotation remapping for the root's compressed synthetic video test.

This cannot create human labels or truth for real videos. The input must correspond,
one-to-one and in order, to the exact known synthetic renderer frames.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import numpy as np
import cv2


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("prepared", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    context = json.loads((args.reference / "dataset_context.json").read_text(encoding="utf-8"))
    preparation = json.loads((args.prepared / "preparation.json").read_text(encoding="utf-8"))
    if context["input_kind"] != "synthetic_ray_render_only" or preparation["inputKind"] != "video":
        raise ValueError("fixture remapping requires synthetic renderer frames and prepared video frames")
    originals = sorted((args.reference / "images").glob("*.png"))
    retained = preparation["retained"]
    if len(originals) != len(retained):
        raise ValueError("kept views must correspond one-to-one to known synthetic frames")
    mapping, checks = {}, []
    for original, retained_frame in zip(originals, retained):
        destination = args.prepared / "images" / retained_frame["image"]
        reference_pixels = cv2.imread(str(original))
        compressed_pixels = cv2.imread(str(destination))
        if reference_pixels is None or compressed_pixels is None or reference_pixels.shape != compressed_pixels.shape:
            raise ValueError("missing frame or changed image geometry")
        mean_difference = float(np.mean(np.abs(reference_pixels.astype(float) - compressed_pixels.astype(float))))
        if mean_difference > 15:
            raise ValueError(f"frame {original.name} does not match its compressed counterpart")
        mapping[original.name] = retained_frame["image"]
        checks.append({"original": original.name, "prepared": retained_frame["image"],
            "mean_absolute_pixel_difference": mean_difference,
            "prepared_sha256": hashlib.sha256(destination.read_bytes()).hexdigest()})
    args.output.mkdir(exist_ok=False)
    (args.output / "images").mkdir()
    for retained_frame in retained:
        shutil.copyfile(args.prepared / "images" / retained_frame["image"], args.output / "images" / retained_frame["image"])
    annotation = json.loads((args.reference / "annotations.json").read_text(encoding="utf-8"))
    for labels in annotation["corners"].values():
        for label in labels:
            label["image"] = mapping[label["image"]]
    truth = json.loads((args.reference / "ground_truth.json").read_text(encoding="utf-8"))
    truth["poses"] = {mapping[name]: pose for name, pose in truth["poses"].items()}
    for name, data in [("annotations.json", annotation), ("ground_truth.json", truth),
            ("dataset_context.json", {"input_kind": "synthetic_compressed_video_only", "real_room": False,
            "mobile_capture": False, "codec": "MJPG", "perfect_pinhole_source": True,
            "annotation_source": "exact synthetic 2D clicks remapped to compressed frames",
            "preparation_source_sha256": preparation["source_sha256"]}),
            ("video_provenance.json", {"mapping_checks": checks, "preparation": preparation,
            "important": "GT poses only copied for later evaluation; SfM never reads this truth file"})]:
        (args.output / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    shutil.copyfile(args.reference / "calibration.json", args.output / "calibration.json")
    print(json.dumps({"output": str(args.output), "retained": len(retained), "input_kind": "synthetic_compressed_video_only",
                      "max_mean_pixel_difference": max(item["mean_absolute_pixel_difference"] for item in checks)}))


if __name__ == "__main__":
    main()
