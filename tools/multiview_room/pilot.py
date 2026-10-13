"""Issue #73: CPU SfM experiment. Synthetic rendering is NOT real-room evidence.

Only render() writes ground_truth.json. reconstruct() never reads it or accepts poses.
The annotations model human 2D corner selections and ONE known metric length.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / ".deps"))
import cv2
import numpy as np
import pycolmap

WIDTH, HEIGHT, FOCAL = 640, 480, 410.0
K = np.array([[FOCAL, 0, WIDTH / 2], [0, FOCAL, HEIGHT / 2], [0, 0, 1.0]])
ROOM = np.array([6.0, 4.0, 2.7])
SEED = 7301


def dump(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def unit(v):
    length = np.linalg.norm(v)
    if length < 1e-12:
        raise ValueError("degenerate vector")
    return v / length


def look_at(center, target):
    forward = unit(target - center)
    right = unit(np.cross(forward, np.array([0.0, 0.0, 1.0])))
    down = np.cross(forward, right)
    rotation = np.vstack([right, down, forward])
    return rotation, -rotation @ center


def texture(seed: int, textured: bool):
    if not textured:
        return np.full((1024, 1024, 3), 225, np.uint8)
    rng = np.random.default_rng(seed)
    # Non-repeating, persistent surface texture, shared by all views.
    small = rng.integers(70, 210, (64, 64, 3), dtype=np.uint8)
    image = cv2.resize(small, (1024, 1024), interpolation=cv2.INTER_NEAREST)
    for i in range(400):
        x, y = rng.integers(15, 1009, 2)
        color = tuple(int(v) for v in rng.integers(0, 255, 3))
        cv2.circle(image, (int(x), int(y)), int(rng.integers(3, 14)), color, -1)
    for i in range(70):
        x, y = rng.integers(15, 970, 2)
        cv2.putText(image, f"{i:X}", (int(x), int(y)), cv2.FONT_HERSHEY_SIMPLEX,
                    0.6, (15, 15, 15), 2, cv2.LINE_AA)
    return image


def ray_render(center, rotation, textures):
    xx, yy = np.meshgrid(np.arange(WIDTH) + 0.5, np.arange(HEIGHT) + 0.5)
    rays_cam = np.stack([(xx - K[0, 2]) / FOCAL, (yy - K[1, 2]) / FOCAL,
                         np.ones_like(xx)], axis=-1)
    rays = rays_cam @ rotation
    best = np.full((HEIGHT, WIDTH), np.inf)
    face_ids = np.zeros((HEIGHT, WIDTH), np.int32)
    for axis in range(3):
        for side in range(2):
            with np.errstate(divide="ignore", invalid="ignore"):
                distance = (side * ROOM[axis] - center[axis]) / rays[..., axis]
            valid = (distance > 0) & (distance < best)
            best[valid] = distance[valid]
            face_ids[valid] = axis * 2 + side
    points = center + best[..., None] * rays
    output = np.empty((HEIGHT, WIDTH, 3), np.uint8)
    for face in range(6):
        axis = face // 2
        others = [a for a in range(3) if a != axis]
        u = np.clip(points[..., others[0]] / ROOM[others[0]] * 1023, 0, 1023).astype(np.float32)
        v = np.clip(points[..., others[1]] / ROOM[others[1]] * 1023, 0, 1023).astype(np.float32)
        colors = cv2.remap(textures[face], u, v, cv2.INTER_LINEAR)
        selected = face_ids == face
        output[selected] = colors[selected]
    return output


def render(output: Path, case: str, count: int = 32):
    output.mkdir(parents=True, exist_ok=False)
    images = output / "images"
    images.mkdir()
    textures = [texture(SEED + face, case != "textureless") for face in range(6)]
    corners = np.array([[0., 0., 0.], [6., 0., 0.], [6., 4., 0.], [0., 4., 0.]])
    annotations = {f"corner_{i}": [] for i in range(4)}
    truth = {"kind": "synthetic_only", "seed": SEED, "room_meters": ROOM.tolist(),
             "corners_meters": corners.tolist(),
             "corners_by_id": {f"corner_{i}": corners[i].tolist() for i in range(4)}, "poses": {}}
    for index in range(count):
        angle = 2 * math.pi * index / count
        center = np.array([3 + 1.15 * math.cos(angle), 2 + 0.8 * math.sin(angle), 1.4])
        if case == "rotation":
            center = np.array([3.0, 2.0, 1.4])
        target = np.array([3 + 4 * math.cos(angle), 2 + 3 * math.sin(angle), 0.35])
        rotation, translation = look_at(center, target)
        name = f"image_{index:04d}.png"
        image = ray_render(center, rotation, textures)
        cv2.imwrite(str(images / name), image)
        truth["poses"][name] = {"R": rotation.tolist(), "t": translation.tolist(), "center": center.tolist()}
        camera_points = corners @ rotation.T + translation
        projected = camera_points @ K.T
        projected = projected[:, :2] / projected[:, 2:]
        for corner, uv in enumerate(projected):
            if camera_points[corner, 2] > 0 and 4 <= uv[0] < WIDTH - 4 and 4 <= uv[1] < HEIGHT - 4:
                # Labels emulate precise human click coordinates, NOT 3D coordinates or poses.
                annotations[f"corner_{corner}"].append({"image": name, "pixel": uv.tolist()})
    dump(output / "annotations.json", {"kind": "synthetic_exact_2d_clicks", "corners": annotations,
        "order": [f"corner_{i}" for i in range(4)],
        "known_length": {"start": "corner_0", "end": "corner_1", "meters": 6.0}})
    dump(output / "calibration.json", {"model": "PINHOLE", "width": WIDTH, "height": HEIGHT,
                                       "params": [FOCAL, FOCAL, WIDTH / 2, HEIGHT / 2]})
    dump(output / "ground_truth.json", truth)
    dump(output / "dataset_context.json", {"input_kind": "synthetic_ray_render_only", "case": case,
         "real_room": False, "mobile_capture": False, "perfect_calibrated_pinhole": True})
    dump(output / "render.json", {"case": case, "images": count, "label_views": {k: len(v) for k, v in annotations.items()},
                                 "truth_usage": "renderer and evaluation only; no SfM pose priors"})
    montage = np.vstack([np.hstack([cv2.imread(str(images / f"image_{i:04d}.png")) for i in row])
                         for row in ([0, 4, 8, 12], [16, 20, 24, 28])])
    cv2.imwrite(str(output / "views.jpg"), montage)
    print(json.dumps({"rendered": str(output), "case": case, "labels": {k: len(v) for k, v in annotations.items()}}), flush=True)


def triangulate_corner(observations, reconstruction):
    by_name = {image.name: image for image in reconstruction.images.values() if image.has_pose}
    rows, used = [], []
    seen = set()
    for observation in observations:
        if observation["image"] in seen:
            raise ValueError("duplicate image annotation for a corner")
        seen.add(observation["image"])
        image = by_name.get(observation["image"])
        if image is None:
            continue
        camera = reconstruction.cameras[image.camera_id]
        # The pilot uses a calibrated PINHOLE camera; other models require explicit undistortion.
        if camera.model_name != "PINHOLE":
            raise ValueError("pilot currently requires PINHOLE calibration")
        uv = np.array(observation["pixel"], float)
        if uv.shape != (2,) or not np.isfinite(uv).all() or not (0 <= uv[0] < camera.width and 0 <= uv[1] < camera.height):
            raise ValueError("invalid corner pixel")
        matrix = image.cam_from_world().matrix()
        intrinsics = camera.calibration_matrix()
        ray = np.linalg.inv(intrinsics) @ np.r_[uv, 1.0]
        rows.extend([ray[0] * matrix[2] - matrix[0], ray[1] * matrix[2] - matrix[1]])
        used.append((image, camera, uv))
    if len(used) < 3:
        raise ValueError(f"corner has {len(used)} registered views; need at least 3")
    _, singular, vh = np.linalg.svd(np.asarray(rows))
    point = vh[-1, :3] / vh[-1, 3]
    if not np.isfinite(point).all():
        raise ValueError("nonfinite corner")
    errors, rays = [], []
    for image, camera, uv in used:
        camera_point = image.cam_from_world() * point
        if camera_point[2] <= 0:
            raise ValueError("corner behind registered camera")
        projected = camera.img_from_cam(camera_point)
        errors.append(float(np.linalg.norm(projected - uv)))
        rays.append(unit(point - image.projection_center()))
    max_angle = max(math.degrees(math.acos(float(np.clip(np.dot(a, b), -1, 1))))
                    for i, a in enumerate(rays) for b in rays[i + 1:])
    if np.median(errors) > 2.0 or max(errors) > 4.0 or max_angle < 3.0:
        raise ValueError(f"corner quality gate: median={np.median(errors):.3f}px max={max(errors):.3f}px parallax={max_angle:.3f}deg")
    return point, {"registered_views": len(used), "median_reprojection_px": float(np.median(errors)),
                   "max_reprojection_px": max(errors), "max_parallax_degrees": max_angle,
                   "design_singular_values": singular.tolist()}


def validate_order(annotations):
    order = annotations["order"]
    if len(order) < 3 or len(set(order)) != len(order) or set(order) != set(annotations["corners"]):
        raise ValueError("corner order must uniquely include every annotated corner")
    known = annotations["known_length"]
    if known["start"] == known["end"] or known["start"] not in order or known["end"] not in order:
        raise ValueError("reference length must join two different annotated corners")
    metric = float(known["meters"])
    if not math.isfinite(metric) or metric <= 0:
        raise ValueError("reference length must be finite and positive")
    return order


def validate_polygon(polygon):
    polygon = np.asarray(polygon, float)
    if polygon.ndim != 2 or polygon.shape[1] != 2 or len(polygon) < 3 or not np.isfinite(polygon).all():
        raise ValueError("polygon needs at least three finite 2D vertices")
    if any(np.linalg.norm(polygon[i] - polygon[j]) < 1e-8 for i in range(len(polygon)) for j in range(i + 1, len(polygon))):
        raise ValueError("polygon vertices must be unique")
    if np.min(np.linalg.norm(np.roll(polygon, -1, axis=0) - polygon, axis=1)) < .1:
        raise ValueError("polygon edge shorter than 10 cm")
    def cross(a, b):
        return float(a[0] * b[1] - a[1] * b[0])
    def intersects(a, b, c, d):
        def orient(p, q, r):
            return cross(q - p, r - p)
        def on_segment(p, q, r):
            return abs(orient(p, q, r)) < 1e-9 and np.all(r >= np.minimum(p, q) - 1e-9) and np.all(r <= np.maximum(p, q) + 1e-9)
        o1, o2, o3, o4 = orient(a, b, c), orient(a, b, d), orient(c, d, a), orient(c, d, b)
        return (o1 * o2 < 0 and o3 * o4 < 0) or on_segment(a, b, c) or on_segment(a, b, d) or on_segment(c, d, a) or on_segment(c, d, b)
    n = len(polygon)
    for i in range(n):
        for j in range(i + 1, n):
            if j == (i + 1) % n or i == (j + 1) % n:
                continue
            if intersects(polygon[i], polygon[(i + 1) % n], polygon[j], polygon[(j + 1) % n]):
                raise ValueError("polygon has intersecting nonadjacent edges")
    area = float(np.sum(polygon[:, 0] * np.roll(polygon[:, 1], -1) - polygon[:, 1] * np.roll(polygon[:, 0], -1)) / 2)
    if area <= .1:
        raise ValueError("polygon must have positive area above 0.1 square meters")
    return area


def reconstruct(dataset: Path):
    # IMPORTANT: no read of ground_truth.json, pose import or input reconstruction.
    out = dataset / "estimated"
    if out.exists():
        report = {"status": "rejected", "failureReason": "Existing experiment outputs cannot be overwritten; use a new dataset/run directory.",
                  "existing_outputs_reused": False, "new_artifacts_written": False,
                  "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
        print(json.dumps(report, indent=2), flush=True)
        return report
    out.mkdir(exist_ok=False)
    started = time.time()
    report = {"evidence": "synthetic_algorithm_only", "pycolmap": pycolmap.__version__,
              "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "has_cuda": pycolmap.has_cuda, "pose_source": "COLMAP incremental_mapping from images only",
              "status": "started", "failureReason": None, "gates": {"min_registration_ratio": .8, "min_corner_views": 3,
              "max_median_reprojection_px": 2., "max_reprojection_px": 4., "min_parallax_degrees": 3.}}
    try:
        context = json.loads((dataset / "dataset_context.json").read_text(encoding="utf-8"))
        if context.get("input_kind") not in {"synthetic_ray_render_only", "synthetic_compressed_video_only"}:
            raise ValueError("this experimental pilot currently accepts labeled synthetic cases only")
        report["evidence"] = context["input_kind"]
        report["dataset_context"] = context
        calibration = json.loads((dataset / "calibration.json").read_text(encoding="utf-8"))
        annotations = json.loads((dataset / "annotations.json").read_text(encoding="utf-8"))
        order = validate_order(annotations)
        images = sorted((dataset / "images").glob("*.png"))
        report["input_sha256"] = {str(path.relative_to(dataset)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in [dataset / "dataset_context.json", dataset / "calibration.json", dataset / "annotations.json", *images]}
        database = out / "database.db"
        pycolmap.extract_features(database, dataset / "images", camera_mode=pycolmap.CameraMode.SINGLE,
            reader_options={"camera_model": calibration["model"], "camera_params": ",".join(map(str, calibration["params"]))},
            extraction_options={"num_threads": 4, "use_gpu": False, "sift": {"max_num_features": 3000}}, device=pycolmap.Device.cpu)
        with sqlite3.connect(database) as connection:
            features = [row[0] for row in connection.execute("select rows from keypoints")]
        report["features"] = {"minimum": min(features), "median": float(np.median(features)), "maximum": max(features)}
        if np.median(features) < 100:
            raise ValueError("insufficient texture: median feature count below 100")
        pycolmap.match_exhaustive(database, matching_options={"num_threads": 4, "use_gpu": False,
            "max_num_matches": 5000}, device=pycolmap.Device.cpu)
        maps = pycolmap.incremental_mapping(database, dataset / "images", out / "sparse",
            options={"num_threads": 4, "random_seed": SEED, "max_runtime_seconds": 180,
                     "ba_refine_focal_length": False, "ba_refine_extra_params": False,
                     "ba_refine_principal_point": False, "min_model_size": 6,
                     "mapper": {"init_min_tri_angle": 8.0, "init_min_num_inliers": 80,
                                "abs_pose_refine_focal_length": False, "abs_pose_refine_extra_params": False}})
        if not maps:
            raise ValueError("SfM produced no registered reconstruction; insufficient multi-view geometry")
        reconstruction = max(maps.values(), key=lambda rec: rec.num_reg_images())
        (out / "selected_model").mkdir()
        reconstruction.write(out / "selected_model")
        report.update({"registered_images": reconstruction.num_reg_images(), "input_images": len(images),
            "registration_ratio": reconstruction.num_reg_images() / len(images), "points3D": reconstruction.num_points3D(),
            "mean_track_length": reconstruction.compute_mean_track_length(),
            "mean_reprojection_error_px": reconstruction.compute_mean_reprojection_error()})
        if report["registration_ratio"] < .8:
            raise ValueError("SfM registration coverage below 80%")
        points, quality = {}, {}
        for name in order:
            points[name], quality[name] = triangulate_corner(annotations["corners"][name], reconstruction)
        known = annotations["known_length"]
        metric = float(known["meters"])
        length_units = np.linalg.norm(points[known["end"]] - points[known["start"]])
        if not math.isfinite(metric) or metric <= 0 or length_units < 1e-9:
            raise ValueError("invalid metric reference")
        scale = metric / length_units
        xyz = np.stack([points[name] for name in order])
        plane_center = xyz.mean(axis=0)
        _, _, vh = np.linalg.svd(xyz - plane_center)
        normal = vh[-1]
        axis_x = unit(xyz[1] - xyz[0])
        normal = unit(normal - np.dot(normal, axis_x) * axis_x)
        axis_y = unit(np.cross(normal, axis_x))
        if np.dot(xyz[-1] - xyz[0], axis_y) < 0:
            normal, axis_y = -normal, -axis_y
        polygon = np.stack([(xyz - xyz[0]) @ axis_x, (xyz - xyz[0]) @ axis_y], axis=1) * scale
        floor_error = float(np.max(np.abs((xyz - plane_center) @ normal)) * scale)
        if floor_error > .1:
            raise ValueError("floor-corner coplanarity residual exceeds 10 cm")
        area = validate_polygon(polygon)
        draft = {"version": 1, "units": "meters", "status": "draft_requires_dimension_confirmation",
            "source": context["input_kind"], "closed": True, "polygon": polygon.tolist(), "corner_order": order,
            "source_sha256": report["source_sha256"], "failureReason": None,
            "registration_ratio": report["registration_ratio"], "coordinate_axes": "local floor plane; not compass or true north",
            "known_length": known, "scale_meters_per_sfm_unit": scale,
            "floor_max_residual_meters": floor_error, "corner_quality": quality,
            "height": None, "openings": [], "automatic_semantics": False}
        dump(out / "room_draft.json", draft)
        report["status"] = "accepted_synthetic_draft"
        report["polygon_area_m2"] = area
        report["floor_max_residual_meters"] = floor_error
        report["corner_quality"] = quality
    except Exception as exception:
        report["status"] = "rejected"
        report["reason"] = f"{type(exception).__name__}: {exception}"
        report["failureReason"] = report["reason"]
    report["duration_seconds"] = round(time.time() - started, 3)
    dump(out / "report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    return report


def evaluate(dataset: Path):
    # Ground truth is read ONLY after SfM+triangulation+metric calibration have finished.
    report = json.loads((dataset / "estimated" / "report.json").read_text(encoding="utf-8"))
    if report["status"] != "accepted_synthetic_draft":
        result = {"evaluated": False, "reason": report.get("reason"), "status": report["status"]}
    else:
        truth = json.loads((dataset / "ground_truth.json").read_text(encoding="utf-8"))
        draft = json.loads((dataset / "estimated" / "room_draft.json").read_text(encoding="utf-8"))
        annotation = json.loads((dataset / "annotations.json").read_text(encoding="utf-8"))
        order = draft["corner_order"]
        if order != annotation["order"]:
            raise ValueError("draft corner IDs no longer match its source annotations")
        corners = np.asarray([truth["corners_by_id"][name] for name in order])
        polygon = np.asarray(draft["polygon"])
        true_lengths = np.linalg.norm(np.roll(corners, -1, axis=0) - corners, axis=1)
        estimated = np.linalg.norm(np.roll(polygon, -1, axis=0) - polygon, axis=1)
        errors = np.abs(estimated - true_lengths)
        reference = annotation["known_length"]
        calibration_side = next((index for index in range(len(order))
            if {order[index], order[(index + 1) % len(order)]} == {reference["start"], reference["end"]}), None)
        # Exclude the actual calibration edge; a diagonal reference calibrates none of the edges.
        holdouts = [index for index in range(len(order)) if index != calibration_side]
        gates = np.maximum(.1, .05 * true_lengths)
        reconstruction = pycolmap.Reconstruction(dataset / "estimated" / "selected_model")
        centers_estimated, centers_truth, camera_rotations_estimated, camera_rotations_truth = [], [], [], []
        for image in reconstruction.images.values():
            if image.has_pose and image.name in truth["poses"]:
                centers_estimated.append(image.projection_center())
                centers_truth.append(truth["poses"][image.name]["center"])
                camera_rotations_estimated.append(image.cam_from_world().rotation.matrix())
                camera_rotations_truth.append(np.array(truth["poses"][image.name]["R"]))
        a, b = np.array(centers_estimated), np.array(centers_truth)
        ac, bc = a - a.mean(axis=0), b - b.mean(axis=0)
        u, singular, vh = np.linalg.svd(bc.T @ ac / len(a))
        sign = np.eye(3)
        sign[-1, -1] = np.linalg.det(u @ vh)
        rotation = u @ sign @ vh
        scale = np.sum(singular * np.diag(sign)) / np.mean(np.sum(ac * ac, axis=1))
        aligned = (scale * (rotation @ ac.T)).T + b.mean(axis=0)
        center_errors = np.linalg.norm(aligned - b, axis=1)
        orientation_errors = []
        for estimated_r, true_r in zip(camera_rotations_estimated, camera_rotations_truth):
            aligned_r = estimated_r @ rotation.T
            orientation_errors.append(math.degrees(math.acos(float(np.clip((np.trace(aligned_r @ true_r.T) - 1) / 2, -1, 1)))))
        result = {"evaluated": True, "synthetic_only": True, "ground_truth_used_for": "post-run validation only",
            "side_lengths_meters": estimated.tolist(), "truth_side_lengths_meters": true_lengths.tolist(),
            "absolute_side_errors_meters": errors.tolist(), "calibration_side_index": calibration_side,
            "holdout_side_indexes": holdouts, "holdout_sides_pass": bool(np.all(errors[holdouts] <= gates[holdouts])),
            "holdout_tolerance_meters": gates[holdouts].tolist(),
            "camera_center_similarity_alignment": "evaluation only; never feeds room draft or engine",
            "camera_center_median_error_meters": float(np.median(center_errors)),
            "camera_center_max_error_meters": float(np.max(center_errors)),
            "camera_orientation_median_error_degrees": float(np.median(orientation_errors)),
            "camera_orientation_max_error_degrees": float(np.max(orientation_errors)),
            "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "real_room_claim": False, "mobile_camera_claim": False}
    dump(dataset / "evaluation.json", result)
    print(json.dumps(result, indent=2), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    rendering = commands.add_parser("render")
    rendering.add_argument("output", type=Path)
    rendering.add_argument("--case", choices=["textured", "textureless", "rotation"], default="textured")
    for command in ["reconstruct", "evaluate"]:
        commands.add_parser(command).add_argument("dataset", type=Path)
    args = parser.parse_args()
    if args.command == "render":
        render(args.output, args.case)
    elif args.command == "reconstruct":
        report = reconstruct(args.dataset)
        if report["status"] == "rejected":
            raise SystemExit(2)
    else:
        evaluate(args.dataset)


if __name__ == "__main__":
    main()
