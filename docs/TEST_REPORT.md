# 测试与性能报告（#21，2026-10-12）

## 自动测试
| 套件 | 平台 | 数量 | 结果 | 证据 |
|---|---|---|---|---|
| FXJCore `swift test` | Linux（swift 6.0.3，本地 box + CI `swift:6.0` 容器） | 141 | 全部通过 | 本地运行；CI job “FXJCore swift test (Linux)” |
| App `xcodebuild test` | macOS-15 / Xcode 16.4 / iPhone 模拟器（CI） | 见 PR checks | 见 PR checks | CI job “iOS build & test” |
| 合规检查 `scripts/check_compliance.py` | CI ubuntu | 1 | 通过 | CI job “Privacy & permission checks” |

覆盖矩阵：
- **确定性场景**：`GoldenScenarios`（无风/南风 3 m/s）冻结 scenarioHash 与关键指标；同输入重复求解逐位一致；iOS 模拟器上同哈希（App `testGoldenHashesMatchOnIOS`）。
- **存储迁移**：v0→v2、v1→v2（含冻结 v1 JSON 夹具）、完整文档往返、损坏/未来版本报错（DomainModelTests/LibraryTests/PersistenceV2Tests/RegressionSuiteTests）。注意：时间戳以秒精度存储。
- **天气故障**：离线/未授权/不可用/无位置不产生环境；缓存新鲜/过期/失效；自动失败回退手动（WeatherTests、RegressionSuite、App）。
- **购买状态**：成功/取消/待批准/失败/未验证/恢复/无可恢复/离线保留/退款撤销（StoreTests、App 2 项）。
- **环境联动**：天气/朝向/楼层/遮挡/开口/几何/布局变化均使结果过期（EnvironmentRegressionTests）。

## 性能（本地 box：Intel Xeon 8 vCPU，Linux）
| 项目 | Debug | Release | 预算（Debug 测试断言） |
|---|---|---|---|
| 示例房间单次求解（50×40 网格） | ~32 ms | ~1.8 ms | < 500 ms |
| 30×20 m 大厅（网格上限生效） | — | ~2 ms | < 1 s |
| 自动布局搜索（3 候选，含全分辨率复算） | ~1.2 s | ~57–95 ms | < 8 s |

## 未运行 / 阻塞
- **低端真机耗时与发热评测：未运行**（无真机；建议 iPhone XR/SE 2 级别，用 Instruments Time Profiler + Energy Log，连续试摆 10 分钟记录温度状态 `ProcessInfo.thermalState`）。
- App 内 XCTest `measure` 结果仅在 CI 模拟器上产生，模拟器数据不代表真机。
- UI 自动化（XCUITest）未建立；当前 App 测试为逻辑级冒烟测试。
