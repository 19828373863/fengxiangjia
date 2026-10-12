#!/usr/bin/env python3
"""#20 合规静态检查：隐私清单、权限用途说明、禁用的追踪 SDK、开发语言。CI 与本地均可运行。"""
import pathlib, plistlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
errors = []
p = root / "App/FengXiangJia/PrivacyInfo.xcprivacy"
try:
    d = plistlib.loads(p.read_bytes())
    if d.get("NSPrivacyTracking") is not False: errors.append("NSPrivacyTracking 必须为 false")
    if d.get("NSPrivacyCollectedDataTypes") != []: errors.append("声明了收集数据类型，请同步更新隐私政策与 App Store 申报")
    cats = {x["NSPrivacyAccessedAPIType"] for x in d.get("NSPrivacyAccessedAPITypes", [])}
    if "NSPrivacyAccessedAPICategoryUserDefaults" not in cats: errors.append("缺少 UserDefaults 必需理由声明")
except Exception as e:
    errors.append(f"PrivacyInfo.xcprivacy 无法解析: {e}")
yml = (root / "project.yml").read_text(encoding="utf-8")
for key in ["NSCameraUsageDescription", "NSLocationWhenInUseUsageDescription"]:
    m = re.search(key + r':\s*"([^"]+)"', yml)
    if not m or not re.search(r"[\u4e00-\u9fff]", m.group(1)): errors.append(f"{key} 缺失或非中文")
if "developmentLanguage: zh-Hans" not in yml: errors.append("开发语言应为 zh-Hans")
src = "\n".join(f.read_text(encoding="utf-8") for f in (root / "App").rglob("*.swift"))
for banned in ["AdSupport", "AppTrackingTransparency", "FirebaseAnalytics", "ASIdentifierManager"]:
    if banned in src: errors.append(f"发现追踪相关依赖: {banned}")
if "UserDefaults" in src and "CA92.1" not in p.read_text(): errors.append("使用 UserDefaults 但未声明理由")
if not (root / "docs/PRIVACY_POLICY.md").exists(): errors.append("缺少 docs/PRIVACY_POLICY.md")
print("\n".join(errors) if errors else "compliance OK")
sys.exit(1 if errors else 0)
