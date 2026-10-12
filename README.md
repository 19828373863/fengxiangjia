# 风向家 FengXiangJia

面向普通用户的 iOS 室内通风和风扇试摆助手。产品目标：看见家里的风，找到舒服的摆法。

## 产品定位
- App Store 独立售卖的生活工具，不提供工程级 CFD 计算或专业暖通设计认证。
- 用户主流程：示例体验 → 画房间／兼容设备摄像头扫描 → 确认住宅位置、朝向、窗外遮挡与天气 → 放置门窗/风扇/生活目标 → A/B 试摆 → 保存与反馈。
- 计算结果标记为简化模拟；区分室外换气、室内循环、个人风感。

## 文档入口
- [AI 工作规范](AGENTS.md)
- [协作与 PR 规范](CONTRIBUTING.md)
- [产品和开发方案](docs/PRODUCT_AND_DEVELOPMENT.md)
- [技术契约](docs/ARCHITECTURE_AND_CONTRACTS.md)
- [任务与状态](TASK_STATUS.md)
- [当前交接](HANDOFF.md)

## 开发原则
SwiftUI 优先；本地处理和离线可用；先实现可验证的 2D 简化模型，不提前许诺手机实时三维 CFD。扫描依赖 RoomPlan 兼容硬件，保留手动录入；天气需允许手动输入。麦克风测风和自动地形重建属于实验增强功能。

## 状态
初始化文档阶段；尚无经验证的 iOS 成品、真机测试或 App Store 上架。
## 构建与测试
需要 macOS + Xcode 16+（iOS 17 SDK 以上）与 [XcodeGen](https://github.com/yonaskolb/XcodeGen)。`.xcodeproj` 由 `project.yml` 生成，不入库。

```bash
brew install xcodegen
xcodegen generate
xcodebuild test -project FengXiangJia.xcodeproj -scheme FengXiangJia \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO

# 纯逻辑核心包（macOS 或 Linux，Swift 5.10+）
cd Packages/FXJCore && swift test
```

CI：`.github/workflows/ios.yml`（macos-15 模拟器构建测试 + Linux 上 FXJCore `swift test`）。

## 目录
- `App/FengXiangJia`：SwiftUI App（UI 层）
- `App/FengXiangJiaTests`：App 单元测试
- `Packages/FXJCore`：Domain/Geometry/Simulation 纯逻辑，不依赖 UI
