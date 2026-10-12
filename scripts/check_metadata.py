#!/usr/bin/env python3
"""#23 App Store 元数据长度与占位检查。--strict 时占位（example.com、〔待定〕、空审核联系人）视为错误（提交前使用）。"""
import pathlib, sys
root = pathlib.Path(__file__).resolve().parent.parent / "fastlane/metadata"
limits = {"name.txt": 30, "subtitle.txt": 30, "keywords.txt": 100, "promotional_text.txt": 170,
          "description.txt": 4000, "release_notes.txt": 4000}
strict = "--strict" in sys.argv
errors, warns = [], []
for loc in [p for p in root.iterdir() if p.is_dir() and p.name != "review_information"]:
    for f, n in limits.items():
        p = loc / f
        if not p.exists(): errors.append(f"{loc.name}/{f} 缺失"); continue
        t = p.read_text(encoding="utf-8").strip()
        if not t: errors.append(f"{loc.name}/{f} 为空")
        if len(t) > n: errors.append(f"{loc.name}/{f} 长度 {len(t)} > {n}")
    kw = (loc / "keywords.txt").read_text(encoding="utf-8")
    if "，" in kw or " ," in kw: errors.append("关键词需用英文逗号分隔且无空格")
    for f in ["privacy_url.txt", "support_url.txt"]:
        u = (loc / f).read_text().strip()
        if not u.startswith("https://"): errors.append(f"{f} 需 https")
        if "example.com" in u: warns.append(f"{loc.name}/{f} 仍为占位")
for f in ["first_name.txt", "last_name.txt", "email_address.txt", "phone_number.txt"]:
    if not (root / "review_information" / f).read_text().strip(): warns.append(f"审核联系人 {f} 未填写")
if "待定" in (root / "copyright.txt").read_text(encoding="utf-8"): warns.append("版权信息为占位")
for w in warns: print("WARN", w)
for e in errors: print("ERROR", e)
bad = errors or (strict and warns)
print("metadata", "FAIL" if bad else "OK")
sys.exit(1 if bad else 0)
