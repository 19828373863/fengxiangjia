# 多视图房间轮廓：CPU 离线算法实验（Issue #73）

这是桌面离线研究工具，不是当前 Android 0.2.1 的功能。已完成的是**合成纹理房间图／合成压缩视频 → 实际 SfM 相机位姿 → 多视图墙角三角化 → 单条已知长度校米 → 二维闭合草稿**。没有验证真实房间、真实手机、白墙、测量准确度或自动门窗识别。

## 固定运行环境与数据流

- 实际环境：Windows x86-64、CPython 3.13.7，CPU PyCOLMAP 4.2.1 (`has_cuda=False`)、NumPy 2.3.2、OpenCV Python 5.0.0.93。
- 本次仅下载 24.0 MB 的 PyCOLMAP CPU wheel 到任务目录；没有下载大模型、CUDA/GPU runtime，也没有更新系统 Python 包。其余依赖使用本机既有版本。完整版本、wheel SHA-256 与许可证文件见 `dependency-provenance.json` / `THIRD_PARTY_NOTICES.md`。
- `pilot.py` 最终执行 SHA-256：`2d319fca41bba80ee4fc9fb0b3f121ebdad4932c65fcecb0212be511b595c787`，源码为 LF。
- 生成器用固定 seed 7301，逐像素射线与六个真实三维平面求交，纹理跨视角共享；输入是 32 张 640 × 480 的理想 PINHOLE 图，房间真值为 6 × 4 × 2.7 m。
- COLMAP 仅接收渲染图片与已知相机内参；运行 SIFT 提取、匹配、增量 SfM、bundle adjustment。**不传入 GT 相机位姿、位置 prior 或 GT 点云**。
- `annotations.json` 是每个墙角的精确合成二维点击，每角 6 个视角，模拟将来的人工作标。它不是自动角点检测。三角化只使用实际注册相机的位姿和这些二维坐标。
- `known_length` 的一条 6 m 墙长模拟独立实测参照；另外三条墙长没有用于校准。GT 只在渲染以及完成草稿后的 `evaluate` 中使用。相机中心的 similarity alignment 也仅用于后验位姿误差统计，不回馈草稿。
- `dataset_context.json` 明确标记 `synthetic_ray_render_only` 或 `synthetic_compressed_video_only`。本实验器当前只接受这些标记的合成实验；标签不构成对素材真实性的自动鉴定。

## 输出契约与拒绝门槛

独立 `estimated/room_draft.json` 是米制研究草稿，**不是 Android Room / HomeDocument**，不会写入 `home.json`。包含 `polygon`、`corner_order`、`units=meters`、来源、版本、源码哈希、单条尺度来源、注册率、每角视角数／重投影／视差及地面平面残差。局部平面轴不是住宅朝向或真北；正面积 CCW，末点不重复。`height=null`、`openings=[]`，均表示未知，不补默认测量值。

接收草稿须同时满足：注册率 ≥80%；每角至少 3 个不同已注册图像；重复标签拒绝；重投影中位数 ≤2 px、最大 ≤4 px；最大三角化视角差 ≥3°；地面角点共面残差 ≤10 cm；至少 3 个唯一有限顶点、每边 ≥10 cm、非相邻边不相交、有向面积 >0.1 m²；角点顺序唯一且完整，参照长度有限且为正。门槛是本实验的工程条件，不是手机精度承诺。

失败只有 `estimated/report.json` / 日志，没有 `room_draft.json`，退出码 2。已有 `estimated` 目录时打印“已有实验输出不可覆盖，另开目录”的机器可读拒绝，退出码 2，保留每个旧文件字节，不复用旧草稿当本次结果。生成器也独占创建新目录，不删除旧实验。

## 实际最终结果

| 输入 | 注册 | 稀疏点 | 平均重投影 | 独立尺寸验收 | 结果 |
|---|---:|---:|---:|---|---|
| 32 张合成 PNG | 32/32 | 13,046 | 0.231633 px | 未校准三边误差约 0.80 / 0.97 / 0.04 cm | 合成草稿 |
| MJPG 合成视频抽出的 32 个视角 | 32/32 | 13,687 | 0.261386 px | 三边误差约 0.24 / 0.04 / 0.14 cm | 合成草稿 |
| 无纹理合成图 | — | — | — | 没有尺寸输出 | 特征中位数 0，拒绝 |
| 原地转动合成图 | 3/32 | 0 | 不定义可用重建精度 | 没有尺寸输出 | 注册覆盖不足，拒绝 |

视频由主代理把每张合成图重复 4 帧，以 8 fps 编成 MJPG，素材准备工具每 0.25 秒抽样，保留 32 / 拒绝重复 32。准备工具源码 SHA-256 为 `80354f932eb17d927e7de6bfab6c4d666ff597728c0c4b285fbfe0b209addbaf`。`prepare_synthetic_video_case.py` 逐帧核对与原合成图的对应关系后，仅为这条合成夹具重映射二维标注；不能生成真实视频的人工标注或真值。最终视频结果来自对压缩抽帧图的另一次实际 SfM，未复用 PNG 的位姿。

实际质量检查为 `gate-checks.json` 的 11/11 通过、失败 0。涵盖正面积自交反例、重复／不足视角、重复顶点、短边、非有限输入、角点顺序／尺度、已有 accepted 输出拒绝且逐文件字节不变，以及两条负例不输出草稿。固定图片可逐字节重现；CPU 多线程 SfM 数值可能轻微变化，不承诺不同平台结果逐位相同。

前期日志保留封装层缺输出目录和 `has_pose` API 类型修正的失败；不把这些退出或旧结果算作最终验收。最终结果只认相同冻结源码哈希的四个运行目录和下述 JSON 条件。

## 重复运行

在工具目录建立任务专用 `.deps`（不改系统包）：

```powershell
python -m pip install --only-binary=:all: --target .deps -r requirements.txt
python pilot.py render runs/photo-new --case textured
python pilot.py reconstruct runs/photo-new
python pilot.py evaluate runs/photo-new
python pilot.py render runs/white-new --case textureless
python pilot.py reconstruct runs/white-new
python pilot.py render runs/rotation-new --case rotation
python pilot.py reconstruct runs/rotation-new
python validate_gates.py runs/photo-new --negative runs/white-new --negative runs/rotation-new
```

PowerShell 重定向记录日志后，可用 `exit $LASTEXITCODE` 将 Python 的退出码传递给调用方。正例 `reconstruct` 退出 0，预期负例退出 2。

**`evaluate` 的退出 0 只表示比较报告已生成，不表示质量合格。** 判读必须同时核对 `evaluation.json` 的 `evaluated=true`、`holdout_sides_pass=true`，以及 `report.json` 的 `status=accepted_synthetic_draft` 和对应源码／输入哈希。评估按 `corner_order` 的 ID 映射取真值，动态排除实际 `calibration_side_index`，`holdout_side_indexes` 列出独立检验的边；如果用对角线校准，边长全部作独立检验。失败或尺寸不符需要重新采集／确认标注与尺度，不能写回房间。

最终本地数据为 `runs/photos-accepted`、`runs/video-accepted`、`runs/no-texture-rejected`、`runs/rotation-rejected`；小型汇总为 `final-packet.json`，对照图为 `synthetic-room-evidence.png`。仓库应仅纳入源码、requirements、说明及小型事实记录；不纳入 `.deps`、wheel、缓存、图像集合、数据库、点云或原始视频。

## 产品下一步

在 Android 集成之前，需用独立测量的真实房间数据检验不同纹理、遮挡、反光、镜头畸变、对焦、视频稳定处理和人工点击误差。通过后再设计设备端重建与可编辑草稿确认入口。将来保存照片／视频也需要同步原来“不保存相机画面”的隐私与删除约定。本轮没有 Android 采集 UI 或手机建模入口。

技术来源：[PyCOLMAP](https://pypi.org/project/pycolmap/)、[COLMAP 多视图采集与 SfM](https://colmap.github.io/tutorial.html)、[无 CUDA 边界](https://colmap.github.io/faq.html#available-functionality-without-gpu-cuda)、[ARCore 环境理解](https://developers.google.com/ar/develop/fundamentals)。
