# TestFlight 与真机验收（#22）

> **状态：阻塞（需人工 + Apple 开发者账号 + 真机）。** 本文件为可直接执行的清单与记录模板；仓库内已完成的准备见末尾。
> 任何一项未在真机上实际执行，均不得标为通过。

## 0. 前置（Tom / 账号持有人）
- [ ] 加入 Apple Developer Program；确定正式 Bundle ID（替换 `com.example.fengxiangjia.app`，同步 `project.yml`、`ProductID.fullVersion`、`.storekit`）
- [ ] 在 `project.yml` 填 `DEVELOPMENT_TEAM`；开启 WeatherKit capability（App ID 勾选 WeatherKit 并在 build settings 加 `SWIFT_ACTIVE_COMPILATION_CONDITIONS: FXJ_WEATHERKIT`）
- [ ] App Store Connect 创建 App 与非消耗型商品（ID 与 `ProductID.fullVersion` 一致）、沙盒测试账号
- [ ] 生成 App Store Connect API Key，配置 fastlane（见 `fastlane/README.md`）

## 1. 设备矩阵（建议最少）
| 设备 | iOS | 用途 | 记录人 | 日期 | 结果 |
|---|---|---|---|---|---|
| iPhone 12 Pro 或更新（LiDAR） | 17.x / 18.x | RoomPlan 扫描 | | | 未运行 |
| iPhone SE 2/3 或 XR（无 LiDAR、低端） | 17.x | 手绘路线、性能/发热、扫描降级 | | | 未运行 |
| 任一 iPhone | 最新 | 离线、购买恢复、权限拒绝 | | | 未运行 |

## 2. 验收用例（每项附截图/录屏文件名与设备）
| # | 用例 | 预期 | 证据 | 结果 |
|---|---|---|---|---|
| D1 | 首次启动 → 打开示例房间 | 无需登录，3 个页签可用 | | 未运行 |
| D2 | 手绘新房间、加门窗、撤销/重做 | 非法形状被拒且有提示 | | 未运行 |
| D3 | LiDAR 机型扫描卧室 → 校准 → 保存 | 墙体闭合，门窗位置误差 < 30 cm；警告可读 | | 未运行 |
| D4 | 无 LiDAR 机型点“扫描新房间” | 显示不支持并引导手绘 | | 未运行 |
| D5 | 相机/定位权限拒绝 | 仍可手绘/手输位置/手动风向；“打开设置”可用 | | 未运行 |
| D6 | 试摆拖动风扇 | 流线与箭头随结果更新；“待更新”原因正确；减少动画开启时静止 | | 未运行 |
| D7 | 自动推荐（完整版） | 3 个候选，应用后与画面一致 | | 未运行 |
| D8 | 住宅天气（WeatherKit） | 显示 Apple 天气归属；离线显示缓存/过期提示并可改手动 | | 未运行 |
| D9 | 飞行模式全流程 | 除天气/购买外全部可用 | | 未运行 |
| D10 | 沙盒购买 / 取消 / 家长批准（Ask to Buy） | 仅成功解锁；待批准提示正确 | | 未运行 |
| D11 | 删除重装 → 恢复购买 | 恢复成功；无购买账号提示“没有可恢复” | | 未运行 |
| D12 | 退款（沙盒 / StoreKit 测试 Refund） | 下次启动撤销完整版 | | 未运行 |
| D13 | 连续试摆 10 分钟（低端机） | `thermalState` 不超过 fair；无明显卡顿 | | 未运行 |
| D14 | VoiceOver、最大动态字体 | 主要操作可完成、文字不截断 | | 未运行 |
| D15 | 流线/推荐可信度主观评估（3 名试用者） | 与真实体感方向一致的比例记录 | | 未运行 |

## 3. TestFlight 发布步骤
1. 更新 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`
2. `xcodegen generate && bundle exec fastlane beta`（或 Xcode Organizer 上传）
3. 填写 TestFlight “测试内容”（见 `fastlane/metadata/zh-Hans/release_notes.txt`）
4. 内部测试组 → 收集 D1–D15 结果 → 记入本文件

## 仓库内已完成的准备
- CI 新增 Release 配置无签名编译检查（确保发布构建可编译）
- `fastlane/Fastfile`（beta/release lane 草稿，未运行）、`fastlane/README.md`
- `.storekit` 本地购买配置（#19）、隐私清单（#20）、测试报告（#21）
