"""Meaningful rejection checks against the actual registered synthetic model."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import pilot


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--negative", type=Path, action="append", default=[])
    args = parser.parse_args()
    results = []
    def rejects(name, callback):
        try:
            callback()
        except ValueError as error:
            results.append({"name": name, "passed": True, "rejection": str(error)})
        else:
            raise AssertionError(f"{name}: invalid input was accepted")
    rejects("positive-area crossing polygon", lambda: pilot.validate_polygon([[0,0],[4,4],[0,4],[2,0]]))
    rejects("repeated vertex", lambda: pilot.validate_polygon([[0,0],[6,0],[6,4],[0,4],[0,0]]))
    rejects("edge shorter than 10 cm", lambda: pilot.validate_polygon([[0,0],[.05,0],[6,4],[0,4]]))
    rejects("nonfinite polygon", lambda: pilot.validate_polygon([[0,0],[6,0],[6,float("nan")],[0,4]]))
    annotation = json.loads((args.dataset / "annotations.json").read_text(encoding="utf-8"))
    bad_order = dict(annotation, order=annotation["order"][:-1])
    rejects("omitted corner from order", lambda: pilot.validate_order(bad_order))
    bad_reference = dict(annotation, known_length=dict(annotation["known_length"], meters=float("nan")))
    rejects("nonfinite metric reference", lambda: pilot.validate_order(bad_reference))
    reconstruction = pilot.pycolmap.Reconstruction(args.dataset / "estimated" / "selected_model")
    labels = annotation["corners"][annotation["order"][0]]
    rejects("A/A/B is not three distinct registered views",
            lambda: pilot.triangulate_corner([labels[0], labels[0], labels[1]], reconstruction))
    rejects("two distinct registered views below required three",
            lambda: pilot.triangulate_corner(labels[:2], reconstruction))
    output = args.dataset / "estimated"
    before = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in output.rglob("*") if path.is_file()}
    rerun = subprocess.run([sys.executable, str(Path(pilot.__file__)), "reconstruct", str(args.dataset)], capture_output=True, text=True)
    after = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in output.rglob("*") if path.is_file()}
    if rerun.returncode != 2 or before != after or json.loads(rerun.stdout)["existing_outputs_reused"]:
        raise AssertionError("rerun must reject with exit 2 and preserve every old result byte")
    results.append({"name": "existing accepted run refuses overwrite", "passed": True, "exit_code": rerun.returncode,
                    "all_old_output_bytes_preserved": before == after})
    for case in args.negative:
        report = json.loads((case / "estimated" / "report.json").read_text(encoding="utf-8"))
        if report["status"] != "rejected" or not report["failureReason"] or (case / "estimated" / "room_draft.json").exists():
            raise AssertionError(f"{case}: rejection must retain a reason and produce no room draft")
        results.append({"name": str(case), "passed": True, "rejection": report["failureReason"], "no_room_draft": True})
    result = {"source_sha256": hashlib.sha256(Path(pilot.__file__).read_bytes()).hexdigest(), "checks": results,
              "passed": len(results), "failed": 0, "evidence": "synthetic/model-input gates only"}
    Path("gate-checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
