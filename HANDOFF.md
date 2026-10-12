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

## 2026-10-12 · Issue #2 FXJ-01（Grok）
- 分支：`feat/2-ios-project-ci`（PR 见 Issue #2）
- 新增：`project.yml`（XcodeGen，iOS 17）、`App/FengXiangJia`（TabView：我家/试摆/方案 占位）、`App/FengXiangJiaTests`（冒烟测试）、`Packages/FXJCore`（占位 + 单测）、`.github/workflows/ios.yml`
- 已测：FXJCore `swift test` 于 Linux（Swift 6.0.3）1 项通过；iOS 编译/模拟器测试以 PR 的 GitHub Actions macos-15 结果为准（见 PR）。
- 未测：真机、签名、TestFlight（无开发者账号/证书）。
- 阻塞：公共契约缺口见 Issue #26，#3/#4 接口需其确认。
- 下一步：#26 确认后实现 #3 Domain 模型与 JSON 序列化迁移测试，随后 #4。

## 2026-10-12 · Issue #3 FXJ-02（Grok）
- 分支：`feat/3-domain-models`（基于 `feat/2-ios-project-ci`，堆叠 PR）
- 新增：`FXJCore/Domain`（Vec2、Home、Location、Room、Wall、Opening、ConnectionTarget、Obstacle、Fan、Target、Environment、Scenario），`FXJCore/Persistence/HomeDocument.swift`（schemaVersion=1、v0→v1 迁移、未来版本拒绝）
- 契约：**暂定**，采用 #26 中开发侧提议，待总调度确认。
- 已测：Linux `swift test` 9 项通过；macOS CI 见 PR。
- 未做：scenarioHash（留给模拟模块 #8）、SwiftData 方案对比。

## 2026-10-12 · Issue #4 FXJ-03（Grok）
- 分支：`feat/4-geometry-topology`（基于 `feat/3-domain-models`）
- 新增：`FXJCore/Geometry/Polygon.swift`（面积、线段相交、自交、点在多边形内、墙段派生、绕向归一化保持开口位置）、`Validation.swift`（RoomIssue 校验）、`Topology.swift`（开口内外连接推导、连接图、室外可达性）
- 规则：墙体按零厚度中心线；邻室墙段反向共线且距离 ≤0.05 m 视为共享；**只有开口能连通区域**，openFraction=0 视为关闭。
- 已测：Linux `swift test` 累计 22 项通过（新增 13）；macOS CI 见 PR。
- 未做：墙厚、房间之间互相重叠检测、曲线墙。

## 2026-10-12 · Issue #5 FXJ-04（Grok）
- 分支：`feat/5-sample-room-nav`（基于 `feat/4-geometry-topology`）
- 新增：FXJCore `SampleData`（固定 ID 示例客厅，含窗/门/家具/风扇/目标）、`HomeRepository`（本地 JSON 文件，HomeLoadState：loading/empty/loaded/failed）；App `AppModel`（@Observable）、我家/试摆/方案三页、`LoadStateView` 统一加载/空/错误态、`RoomCanvasView`（Canvas 绘制 + 拖动风扇，越界拒绝）
- 已测：Linux `swift test` 累计 27 项；App 单测（AppModel/坐标变换）在 macOS CI 运行，见 PR。
- 未测：模拟器中手动交互、截图、VoiceOver；真机。

## 2026-10-12 · Issue #6 FXJ-05（Grok）
- 分支：`feat/6-floorplan-editor`（基于 `feat/5-sample-room-nav`）
- 新增：FXJCore `Editor/RoomTemplate`（矩形、L 形）、`Viewport`（平移、锚点缩放、适配）、`RoomEditor`（撤销/重做、历史上限 100、网格吸附 5 cm、顶点移动合法性校验、家具/风扇增删移、门窗吸附最近墙段并夹在墙内、防重叠）；App `FloorplanEditorView`（Canvas、工具栏、模板菜单、撤销重做、拖动顶点与家具、双指缩放）；“我家”页“编辑户型（手绘）”与空状态“新建房间（手绘）”入口；保存后刷新连接、geometryRevision+1
- 已测：Linux `swift test` 累计 43 项通过（新增 16）；App 单测见 PR CI。
- 未测：编辑器手势在模拟器/真机中的实际体验、截图；家具旋转与尺寸编辑、开口拖动 UI（逻辑已有 moveOpening）未接入。

## 2026-10-12 · Issue #7 FXJ-06（Grok）
- 分支：`feat/7-fan-targets`（基于 `feat/6-floorplan-editor`）
- 新增：FXJCore `Editor/FanTargetEditing.swift`（FanPlacement 规则：房内、离墙 ≥0.2 m、不压非低矮家具；放置/移动/朝向 5° 吸附/对准/档位 0.1 吸附/类型；目标区域增删移、半径 0.2–3 m、目标切换；Obstacle 旋转矩形包含、Target 中心/包含）；App `TryoutView`（拖动、点选检查器、撤销重做、添加菜单），`RoomCanvasView` 支持目标区域与叠加层；`AppModel.updateLayout`（不提升 geometryRevision）
- 已测：Linux `swift test` 累计 49 项；App 测试见 PR CI。未手动运行 UI。

## 2026-10-12 · Issue #8 FXJ-07（Grok）
- 分支：`feat/8-airflow-solver`（基于 `feat/7-fan-targets`）
- 新增：FXJCore `Simulation/`：`SHA256.swift`（纯 Swift，含标准向量测试）、`ScenarioHash.swift`（规范化 JSON：键排序、1 mm/0.1° 量化、字典按 id 排序 + 模型版本）、`SimulationTypes.swift`（SimulationInput/Options、FlowField、Level、SimulationMetrics、Explanation、SimulationResult.isCurrent）、`AirflowSolver.swift`（模型 fxj-2d-0.1：网格掩码、风扇射流+视线遮挡、吊扇径向、穿堂风入流、邻域平滑、定性换气等级）
- 不变量已测：全封闭/开口全关 → 对外换气 none（风扇仅室内循环）；相同输入逐位相同；输入变化 → isCurrent=false
- 已测：Linux `swift test` 累计 65 项（新增 16）。
- 限制：生活示意模型，未与实测或 CFD 校准；无回流/压力求解；单房间网格（邻室仅用于判断通路）。

## 2026-10-12 · Issue #9 FXJ-08（Grok）
- 分支：`feat/9-airflow-visualization`（基于 `feat/8-airflow-solver`）
- 新增：FXJCore `Simulation/Visualization.swift`（FlowVisualization：RK2 流线、静态箭头、固定图例 0–1.5 m/s，携带 scenarioHash 与结果同源）；App `FlowOverlayView`（TimelineView 流线动画/静态箭头，减少动态效果、低电量、非前台时暂停）、`FlowLegendView`、`ExplanationText`；试摆页随输入变化防抖重算（后台线程），过期结果淡化并标“待更新”，展示定性指标与 ≤3 条解释
- 已测：Linux `swift test` 累计 71 项（新增 6：同源 hash、确定性、流线不穿固体、箭头方向、图例夹取、无效结果）。UI 未手动运行。

## 2026-10-12 · Issue #10 FXJ-09（Grok）
- 分支：`feat/10-ab-compare`（基于 `feat/9-airflow-visualization`）
- 新增：FXJCore `Recommendation/Comparison.swift`（Scenario.capture/applied；ScenarioComparison：强制同一环境快照与网格参数、共享固定图例；维度=目标有风/直吹风险/对外换气/室内循环/调整成本，均为定性等级与“A/B/相近”，无伪精确分数；备注=环境统一、户型已改、封闭房间、无效；建议 ≤3 条）；App `ComparisonView`、方案页多选比较与删除、试摆页“保存方案”；`AppModel.saveScenario/deleteScenario`
- 已测：Linux `swift test` 累计 78 项（新增 7）。UI 未手动运行。
