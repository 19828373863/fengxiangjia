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
