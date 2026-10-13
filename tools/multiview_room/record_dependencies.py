"""Record runtime provenance and retain dependency license texts, not binaries."""
import hashlib
import importlib.metadata as metadata
import json
from pathlib import Path
import platform
import shutil
import pilot

root = Path(__file__).resolve().parent
licenses = root / "licenses"
licenses.mkdir(exist_ok=True)
records = []
for name in ["pycolmap", "numpy", "opencv-python", "matplotlib"]:
    distribution = metadata.distribution(name)
    copied = []
    for path in distribution.files or []:
        text = str(path)
        if ".dist-info" in text and ("LICENSE" in text.upper() or "COPYING" in text.upper()) and path.locate().is_file():
            target = licenses / f"{name}-{path.name}"
            shutil.copyfile(path.locate(), target)
            copied.append(target.name)
    records.append({"name": name, "version": distribution.version, "license_files": copied})
result = {"python": platform.python_version(), "platform": platform.system(), "architecture": platform.machine(),
          "dependencies": records, "cuda": False, "install_scope": "task .deps only for pycolmap; existing other packages reused",
          "wheels": [{"file": path.name, "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                     for path in (root / "wheels").glob("*.whl")]}
(root / "dependency-provenance.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
