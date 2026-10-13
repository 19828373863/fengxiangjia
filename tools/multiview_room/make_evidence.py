"""Static, explicitly synthetic evidence figure; no rendering or estimation changes."""
import json
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
import numpy as np
import cv2

ROOT = Path(__file__).resolve().parent
FONT = FontProperties(fname=r"C:\Windows\Fonts\msyh.ttc")
plt.rcParams["axes.unicode_minus"] = False
photo = ROOT / "runs" / "photos-accepted"
video = ROOT / "runs" / "video-accepted"

def read(path):
    return json.loads(path.read_text(encoding="utf-8"))

draft = read(photo / "estimated" / "room_draft.json")
report = read(photo / "estimated" / "report.json")
evaluation = read(photo / "evaluation.json")
video_report = read(video / "estimated" / "report.json")
video_eval = read(video / "evaluation.json")
polygon = np.array(draft["polygon"])
truth = read(photo / "ground_truth.json")
reference = np.array([truth["corners_by_id"][name][:2] for name in draft["corner_order"]])
fig = plt.figure(figsize=(12, 9.0), facecolor="#FAF8F2", layout="constrained")
grid = fig.add_gridspec(3, 4, height_ratios=[.95, 1.45, .13])
fig.suptitle("合成纹理房间算法实验", fontproperties=FONT, fontsize=22, color="#163D48")
for col, index in enumerate([4, 12, 20, 28]):
    ax = fig.add_subplot(grid[0, col])
    image = cv2.cvtColor(cv2.imread(str(photo / "images" / f"image_{index:04d}.png")), cv2.COLOR_BGR2RGB)
    ax.imshow(image)
    ax.set_title(f"合成视角 {index + 1}/32", fontproperties=FONT, fontsize=12)
    ax.axis("off")
ax = fig.add_subplot(grid[1, :2])
closed = np.vstack([polygon, polygon[0]])
reference_closed = np.vstack([reference, reference[0]])
ax.plot(reference_closed[:,0], reference_closed[:,1], "--", color="#8A8580", linewidth=3, label="参考轮廓（仅事后验收）")
ax.plot(closed[:,0], closed[:,1], "o-", color="#176478", linewidth=1.6, markersize=5, label="SfM 位姿 + 三角化 + 单边校米")
ax.set_aspect("equal")
ax.set_xlim(-.35, 6.4)
ax.set_ylim(-.35, 4.4)
ax.set_xlabel("局部平面 x / 米", fontproperties=FONT)
ax.set_ylabel("局部平面 y / 米", fontproperties=FONT)
ax.legend(prop=FONT, fontsize=10, loc="lower center", bbox_to_anchor=(.5, -.3))
ax.set_title("二维轮廓：只用一条 6 m 墙长校准", fontproperties=FONT, fontsize=13)
ax.grid(alpha=.18)
stats = fig.add_subplot(grid[1, 2:])
stats.axis("off")
holdout_errors = [evaluation["absolute_side_errors_meters"][i] * 100 for i in evaluation["holdout_side_indexes"]]
lines = ["实际引擎输出（CPU PyCOLMAP 4.2.1）",
         f"照片：{report['registered_images']}/{report['input_images']} 个位姿注册",
         f"稀疏点：{report['points3D']:,}；平均重投影 {report['mean_reprojection_error_px']:.3f} px",
         "每个墙角：6 个独立注册视角",
         "未参与校准的三边误差：",
         "  " + " / ".join(f"{value:.2f} cm" for value in holdout_errors),
         "", "压缩视频 → 抽帧 → 实际 SfM：",
         f"{video_report.get('registered_images', 0)}/32 位姿注册；" + ("独立边长验收通过" if video_eval.get('holdout_sides_pass') else "未通过尺寸验收"),
         "", "失败样本：无纹理、原地转动均拒绝",
         "拒绝时不输出房间草稿，不改已有房间。",
         "", "理想纹理、固定内参、精确合成点击；",
         "这组误差不代表真实手机测量精度。"]
stats.text(.0, .98, "\n".join(lines), va="top", fontproperties=FONT, fontsize=12, linespacing=1.5, color="#193F4C")
footer = fig.add_subplot(grid[2, :])
footer.axis("off")
footer.text(.0, .5, "仅合成算法证据；未验证真实房间、白墙、手机扫描或自动门窗识别。源码 " + report["source_sha256"][:12],
            fontproperties=FONT, fontsize=10, color="#66615B")
fig.savefig(ROOT / "synthetic-room-evidence.png", dpi=150)
plt.close(fig)
print("synthetic-room-evidence.png")
