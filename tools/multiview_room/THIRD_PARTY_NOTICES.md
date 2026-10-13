# CPU 原型依赖与许可记录

本次源码没有复制依赖源码或二进制到 Android 应用。任务专用 `.deps` 仅安装 PyCOLMAP 的固定 CPU wheel；没有大模型或 GPU runtime。运行所用其他包来自本机已有环境。

| 包 | 固定版本 | 顶层许可 | 用途 |
|---|---|---|---|
| PyCOLMAP / COLMAP | 4.2.1 | BSD-3-Clause | 特征、匹配、增量 SfM / bundle adjustment |
| NumPy | 2.3.2 | BSD-3-Clause；wheel 的附带依赖另有许可 | 线性代数／几何 |
| opencv-python | 5.0.0.93 | wheel 包装为 MIT、OpenCV 为 Apache-2.0；附带依赖另有许可 | 合成射线图着色／读写图片 |
| Matplotlib（仅可选报告） | 3.10.6 | Matplotlib / BSD-compatible license；附带素材另有许可 | 静态证据图 |

PyCOLMAP wheel：`pycolmap-4.2.1-cp313-cp313-win_amd64.whl`，23,974,670 bytes，SHA-256 `2aa586acd21154ed92c1d674d5da9b1b05e1dd9e2156627caf0ae42278423f02`，实际 `has_cuda=False`。

任务目录 `licenses/` 保存所用发行包提供的原文：`pycolmap-COPYING.txt`、`numpy-LICENSE.txt`、`opencv-python-LICENSE.txt`、`opencv-python-LICENSE-3RD-PARTY.txt`、`matplotlib-LICENSE`。NumPy wheel 有 OpenBLAS 和编译器运行时许可／例外说明；OpenCV wheel 的第三方条目见其随包文件。顶层许可标签不代替这些附带组件的许可。此记录只针对本次工具环境，不宣称任何未来 Android/JNI 二进制分发已完成许可审查。

静态报告使用本机 Windows Microsoft YaHei 字体，不复制／分发字体文件。

来源：[PyCOLMAP 官方包](https://pypi.org/project/pycolmap/)、实际 wheel 的 COPYING、各固定发行包的 dist-info 许可原文；版本和文件哈希记录在 `dependency-provenance.json`。源码集成时不复制 `.deps`、wheel 或依赖运行库。
