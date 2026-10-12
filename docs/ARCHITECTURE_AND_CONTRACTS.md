# 架构与接口契约 v0.1

## 模块
Domain（房屋/房间/门窗/设备/场景） → Geometry（坐标/拓扑） → Simulation（轻量流场） → Recommendation（候选比较与解释） → UI（SwiftUI）；外部输入通过独立 Adapters（RoomPlan、Location、Weather、StoreKit）。模块间不得反向依赖 UI。

## 坐标与单位
室内二维右手笛卡尔坐标；米为长度单位，度为用户输入方向（内部转换弧度），风向必须记录“来向/去向”的定义及向量转换。RoomPlan 坐标先转换到 Domain 坐标。每个门窗记录宿主墙段、开口起止及连通的内/外区域；不得穿墙或制造隐形通道。

## 实体
Home{id,name,location?,orientation,rooms[],constraints}；Room{id,polygon,height?,purpose}；Opening{id,wallId,connects,kind,openFraction,locked}；Obstacle{id,footprint,heightClass}；Fan{id,position,yaw,power,kind}；Target{id,shape,objective}；Environment{source,observedAt,windFromDegrees,windSpeed?,temperature?,rain?,obstructionByOpening}；Scenario{id,geometryRevision,environmentSnapshot,layoutSnapshot,targets}；SimulationResult{scenarioHash,modelVersion,status,field,metrics,explanations}。

## 不变量
- 场景及环境输入变化时旧结果 invalidated。
- A/B 比较共享同一 Environment 快照与图例范围。
- 建议来自用于绘制的 SimulationResult；来源未知时标注“不确定”。
- 全闭合外围护结构中的风扇不得被解释成室外换气增加。
- RoomPlan 扫描结果可编辑；定位失败不阻断流程；天气过期明确显示；拒绝麦克风也可完成主流程。

## 持久化
保存 Schema 版本、实体 id 和场景条件；支持迁移、删除和导出。私密住宅数据只在本地默认存储；日志脱敏。

## Android Issue #70 当前契约追加（2026-10-13）

本节限定 Android 的可用产品实现，保留前述跨平台与 iOS 历史契约。共享 `HomeDocument.CURRENT_SCHEMA_VERSION = 2` 和跨平台主 JSON 结构没有因本轮 UI 重建改变。

### 原生界面、坐标和连续操作

Android 使用 Kotlin / Compose / Material 3，主目的地为我家、试摆、方案、建议；原生语义主题和系统 SansSerif/sp 由 Theme.kt 提供。视口把米制世界坐标转换为画布屏幕位置，手势期间锁定视口；pointerInput 的生命周期按房间 id 与 EditMode 维持，而不按每次预览对象重启。拖动移动只更新预览，松手完成一次有效编辑与重新计算，取消丢弃预览。

位置字段向用户显示相对于房间 `minX/minY` 的米值；提交时把偏移加回世界坐标。风扇出风朝向沿用平面图 0°向右、90°向上，与天气“来向”分开解释。物品列表、数值、步进和 ±15° 方向操作构成手势替代。房间几何/实体输入经过有限值、边界、占位及拓扑校验，失败不得悄悄替换当前房间或破坏撤销/重做分支。

### 同源求解、A/B 与建议

试摆计算绑定捕获的 SimulationInput 和 ScenarioHash，完成后只应用仍与当前输入一致的结果。A/B 捕获 Home、两份 Scenario、geometryRevision 和统一 Environment，不在工作线程读取可变 AppModel；预览与比较指标由对应求解结果生成，图例范围相同。

建议搜索捕获不可变输入/hash，结果返回与用户点击采用时均重新核对房间和输入。过期候选不能采用，输入变化或失败要明确显示并保留可重试状态。结构不兼容的旧方案不能恢复或参与 A/B；环境与几何改变引起的旧气流必须标记待更新。生活示意模型与源码回归不构成真实风场准确度证据。

### 主文件与 Android 会话

- `home.json`：共享 schema v2 的 HomeDocument，仍保存住宅、房间、方案、feedback 与 geometryRevision。
- `home.json.session.json`：仅 Android 使用，当前字段为 `version = 1`、`homeId`、`selectedRoomId`、`environment`（对象或 JSON null）及可选 `weatherForecast`。Environment 保存实际来源/观测时间；WeatherForecast 保存预报来源、时间、归属等现有序列化字段。它不把会话状态新增到跨平台主 JSON 或导出契约。
- 仅在主文件成功加载后恢复 session；homeId 不匹配时不采用该 session，selectedRoomId 仅在仍属于当前 Home 时采用。session 损坏不丢弃主住宅与方案，而以明确消息要求重新确认风况；保存失败状态保持可见。
- 恢复 forecast 时按当前 clock 重新判定新鲜度并标记缓存；恢复不改变观测时间，不把旧/演示天气称为当前位置实时测量。手动 Environment 与显式无室外风的 null 都应在重启后保留。

### 持久化成功、重试与不可读数据保护

LocalFileStorage 为每份文件先完整写入临时文件、同步，再优先执行原子替换；文件系统不支持原子 move 时使用完整文件替换。旧目标不会为强行 rename 而先删除。主文件与 session 分别写入，**不承诺跨文件事务**。

AppModel 分别跟踪 `documentNeedsSave` 与 `sessionNeedsSave`；只有两者均清除才能报告 `lastSaveSucceeded`。任何一份失败都保留内存中的当前修改、持续显示重试状态；retrySave 重新写当前最新住宅和 session，不能拿稍后的内存编辑成功掩盖持久化失败。失败的保存方案不能新增幽灵方案或清空名称输入。

对不能解码的主文件，包括 unsupported future schema，HomeRepository 在示例回退的修改覆盖前先创建本地恢复副本：`home.json.recovery.json`，有不同既存副本时依次使用 `.recovery.1.json` 等。复制采用临时文件，核对长度和 SHA-256 的字节内容后才提升为副本；相同原文件可复用已核对副本。副本无法保存时停止覆盖原文件，后来的恢复不替换早先恢复副本。这是应用本地存储/JVM 机制，不等于设备 shell 文本抓取的原始文件备份。明确删除本机全部数据时，同时清理 session、匹配的恢复副本与相关临时文件。

### 完整测试构建与正式权益

AppModel 的 `fullExperience` 默认 false；本轮 MainActivity 仅在 BuildConfig.DEBUG 时显式开启。它绕过测试体验所需的 PaidFeature 额度墙，和 Play EntitlementState 独立，不创建购买凭证。release 的免费额度/购买恢复仍按真实权益状态运行；debug 体验不能说明 Google Play 的商品、签名、交易或恢复已通过。位置、网络、商店与 AR 的平台前提也没有被 debug 权限消除。

本轮版本为 0.2.0 / versionCode 2。候选 APK、本地测试和限定设备操作的证据见 docs/ANDROID_STATUS.md；物理 AR 测量、传感器准确度、真实流场与真实 Play 交易仍未验证。在线天气当前只有模拟器公开测试位置的成功证据，离线降级仍待记录，独立原生视觉审阅仍待结论。住宅、私有截图、设备数据和恢复副本不进入仓库或远端协作记录。
