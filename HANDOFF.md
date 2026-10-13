# 项目交接

## 当前阶段
正在初始化 GitHub 文档与任务。尚无经验证的 iOS 工程或可安装版本。

## 新 AI 开工步骤
1. 阅读 AGENTS.md、README.md、docs/PRODUCT_AND_DEVELOPMENT.md、docs/ARCHITECTURE_AND_CONTRACTS.md。
2. 查看开放 Issue 与关联 PR；选择没有认领的最小任务。
3. 在 Issue 留认领及文件范围；独立分支实现，附测试证据发 PR。
4. 在本文件追加 SHA、测试、未完成项和下一步。切勿覆盖其他 Agent 的工作。

## 优先事项
从 P0 最小 SwiftUI 工程、核心数据实体与一间示例房间开始，再纵向实现编辑、试摆、A/B 对比；不要首先开发云服务、AI 聊天或复杂 CFD。

## Android Issue #70 当前交接（2026-10-13）

此节是本轮 Android 工作的追加记录，不改写上方 iOS 初始化历史。

- 任务：Issue #70，重建可以实际操作的「我家 / 试摆 / 方案 / 建议」原生流程，并以设备运行证据验证核心编辑、试摆、保存与恢复。
- 基线：`328f22d785e7d5d4b410a3f095d32e12e3765ede`。
- 工作分支：`fix/70-android-usable-product`。
- 本轮源码提交 SHA：`9effffb2cca12e8bb141a138b04f727eeab0bc4f`；最终设计与交接文字在后续独立文档提交中冻结，Android 源码不再变化。
- 当前候选：Android `0.2.0` / versionCode `2`，debug 完整体验构建；APK SHA-256 为 `5519C4AC3A2B46958BE4FB2D90FFA6509524CF97B61405C077FDD401B8F8CBEE`。

### 已完成内容与所属文件

原生主题、文字/图标、明暗模式与导航由 Theme.kt、UiComponents.kt、MainActivity.kt 统一；HomeScreen.kt、TrialScreen.kt、PlansScreen.kt、SuggestScreen.kt 与 RoomCanvas.kt 实现房间入口、连续拖动/精确调整、方案恢复/A-B 比较和建议采用。AppModel.kt 与 core 的 HomeDocument.kt/LocalFileStorage 维护校验、持久化状态、会话恢复、重试和不可读文件副本；相关 JVM 回归位于 android/app/src/test 与 android/core/src/test。本节列出主要影响面，最终逐文件清单以提交 diff 为准。

PRODUCT.md、docs/ANDROID_USABILITY_BRIEF.md、DESIGN.md、.impeccable/design.json 记录本轮 Android 产品与视觉约束；Android 当前状态、决策及契约分别追加于 docs/ANDROID_STATUS.md、DECISIONS.md、docs/ARCHITECTURE_AND_CONTRACTS.md。跨平台主 JSON schema 仍为 v2，Android 会话放在本机 sidecar，不扩充共享 JSON 契约。

### 已执行验证

本地 `test + assembleDebug` 通过，日志留在会话本地 `work/build-70-corrected.log`。XML 合计为 core 79、app debug 64、app release 64，共 207 次执行，失败/错误/跳过均为 0；独立测试定义为 core 79 + app 64。未将 app 的两个构建变体计为 128 个不同用例。

实施负责人已安装候选到 K30 与 `emulator-5582`，运行检查涵盖拖动、相对坐标输入、新建 4.5 × 3 m 房间与物品、撤销/重做、3 个保存方案、恢复、带实际风线与不同指标的 A/B、两台设备的建议采用、原生 PNG 分享选择器、重启会话及失败保存持续重试后写入最新状态。详细范围见 docs/ANDROID_STATUS.md；截图与私有布局只留在本地。

模拟器已用公开测试位置完成 Open-Meteo 实际读取，返回来向 311°、2 m/s、UTC 10-12 17:00，来源/条款/新鲜度可见；90° 手动风况设置已成功。没有采样用户手机的真实位置；断开模拟器 Wi-Fi 与移动网络后，天气读取进入可重试状态，保留原来来向 311°/2 m/s；随后网络设置恢复。

K30 的原有 room/scenarios 在临时测试后已恢复并核对不变；规范化了 ADB shell 捕获的 CRLF 文本差异。不得将这次捕获描述为原始私有文件的逐字节备份，也不得发布用户住宅或原生截图。

### 未完成、限制与下一个动作

1. 离线检查完成。独立原生审阅发现的唯一 P2 标签重叠已按同名 K30 浅/深色大字体截图评分 resolved，disposition: ship；最终用户体验仍由用户自行评价。
2. Android 源码已冻结于上述提交。按当前验收记录提交聚焦草稿 PR，目标为原始功能基线分支；CI 与合并仍须依据实际结果。
3. 真实 Play 购买/恢复、正式签名发布/商店提交、物理 AR 测量、传感器准确度与真实气流模型效果继续保持未验证。debug 完整体验不是购买凭据。
4. 交接时只转交源码、契约、测试范围与可公开的说明；不上传构建缓存、工具目录、设备数据、恢复副本、私人布局或原生截图。


### 最终设备候选冻结

最终 APK SHA-256：`5519C4AC3A2B46958BE4FB2D90FFA6509524CF97B61405C077FDD401B8F8CBEE`。标签修正后 `:app:testDebugUnitTest assembleDebug` 通过（64 个 debug 用例，日志 `work/build-70-label-final.log`）；core/持久化/推荐逻辑未再次修改，复用前一轮完整 207 次执行。最终候选成功安装到 K30 与 emulator-5582；K30 安装路径中的 APK 原始字节再次读取并核对与交付包哈希相同。K30 原有完整 home.json 内容按 JSON 语义核对不变，已恢复 1.0 字体缩放与浅色系统设置，交付停留「我家」。

后段测试模拟器曾被其他开发会话切到另一应用；无效截图已弃用，不作为证据。最终浅色、深色及 1.3 倍字体界面以 K30 重新采集的真实截图为准。模拟器功能操作的有效截图/JSON 保留在本会话本地。物理扫描与真实 Play 交易不属于已验证范围。

## Android Issue #72 交接（2026-10-13）

任务与分支：原生动效和相机准备可靠性，`feat/72-native-motion-and-camera`，以 #70 分支为基线。源码冻结 `063c51aa9b7b300518f931ccc001501dd1c25814`；0.2.1 / code 3 debug APK SHA-256 `1FBF549728CD6263B36D3DAF9CAE19AB7BAE42590ECFE2E425978DCD4B815195`，设计/证据文档在后续提交追加。

主要文件：新增 Motion.kt；MainActivity、HomeScreen、SuggestScreen、RoomCanvas、TrialScreen 为原生 motion；ScanSupport/ScanActivity 为能力、安装、权限与失败恢复；AppModel.importRoom 与 AppModelReliabilityTest 为局部扫描原点导入修正。设计与当前能力见 DESIGN.md、.impeccable/design.json、docs/ANDROID_MOTION_CAMERA.md；没有共享 schema/求解器变更。

实际检查：完整 JVM core 79 + app debug 68 + release 68（147 个独立定义/215 次执行）通过；最后箭头配对微调后 debug 68 与 assembleDebug 通过，core/release 复用上一轮。独立视觉/功能复审发现均 Resolved，Approve。K30 早期候选实际动效、40 次快速导航已记录并恢复系统倍率 1；最终补丁还未再次安装 K30。FXJ_Motion_72 / emulator-5584 最终候选安装哈希、正常倍率录屏、主住宅字节保留与 crash buffer 已核对；关闭动画、深色大字体、拖动撤销、画布固定边界和静态 PNG 内容也已检查。所有真实设备媒体/住宅文件只保留本地。

限制与下一步：K30 当前被另一任务独占，不得抢占或操作；收到明确交还后才更新最终包并保留当天最新住宅，禁止恢复过夜旧备份。该手机本轮 AR 服务缺失且 SDK UNKNOWN；实际扫描、Depth/量测准确度未通过。照片/视频重建另有 #73，先做离线算法验证，Android 端当前未集成。远端 CI、正式 Play 交易/发布及真实风场证据不可由本地测试替代。
