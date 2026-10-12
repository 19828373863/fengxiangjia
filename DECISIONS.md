# 项目决策日志

- D001：iOS App Store 独立生活工具，非专业暖通工程仿真产品。
- D002：SwiftUI + 原生平台能力，优先本地计算和免登录。
- D003：手绘必备，RoomPlan 为兼容 LiDAR 设备入口，扫描后可手动修正。
- D004：住宅位置独立于手机实时位置；天气按住宅查询；窗外遮挡与楼层允许手工修正。
- D005：风线和建议共用计算快照，A/B 只能在相同天气条件下比较。
- D006：麦克风感知弱风、自动周边地形三维重建是实验性后续模块。
- D007：免费试用+一次性非消耗型内购是初步商业方案，价格需测试。

新增或修改决策需关联 Issue 和具体原因。
- D008（Issue #2）：最低支持 iOS 17.0（可用 ContentUnavailableView、Observation、SwiftData 选项；RoomPlan 需 iOS 16+ 且 LiDAR 设备，运行时判定）；Xcode 工程用 XcodeGen `project.yml` 生成，不提交 .xcodeproj；纯逻辑放本地 SPM 包 `FXJCore` 以便 Linux/CI 单测。
- D009（**提议，待讨论，未生效**）：持久化采用 Codable JSON + `schemaVersion` 字段与显式迁移，而非 SwiftData，便于导出、迁移测试与跨平台单测。需总调度在契约 Issue 中确认。
