# App Store 资料与发布合规清单（#23）

> **状态：资料草稿已入库；提交审核阻塞（需开发者账号、正式主体信息、备案号）。** 文案位于 `fastlane/metadata/`，`scripts/check_metadata.py` 校验长度（CI 运行），`--strict` 用于提交前检查占位。

## 1. 名称检查
- [x] 网页初查（2026-10-12）：未发现名为“风向家”的 App Store 应用（仅为初步检索，**不等于可用**）
- [ ] App Store Connect 中创建 App 时实际占用名称（最终依据）
- [ ] 商标检索（中国商标网，第 9 类软件）——建议 Tom 确认
- 副标题：“风扇摆哪儿更凉快”（≤30 字符）

## 2. 截图（未生成最终版）
- 必需：6.9 英寸（1320×2868 或 1290×2796）；6.5 英寸可选。仅 iPhone（`TARGETED_DEVICE_FAMILY = 1`）
- 建议 5 张：示例房间试摆 / 目标区域与直吹 / A/B 比较 / 自动推荐 / 隐私与离线
- 来源：CI `workflow_dispatch screenshots=true` 可在模拟器截取（**当前产物配额已满，上传失败**）；最终截图需人工挑选并加说明文字
- 不得出现未实现功能；“扫描”截图需真机

## 3. App 隐私（营养标签）
- 拟申报：**未收集数据（Data Not Collected）**；无追踪（与 `PrivacyInfo.xcprivacy` 一致）
- 需 Tom 确认：住宅天气将约 1 km 精度位置发给 Apple WeatherKit——Apple 服务处理、开发者不接收，通常无需申报为“收集”，提交前复核 Apple 最新说明
- 隐私政策 URL：`docs/PRIVACY_POLICY.md` 需发布到公开 https 网址并替换 `privacy_url.txt` 占位

## 4. 出口合规
- 仅使用系统提供的 HTTPS 与 SHA-256 摘要（非加密用途）→ `ITSAppUsesNonExemptEncryption = NO`（已写入 `project.yml`）

## 5. 中国大陆上架：备案适用性
- 依据工信部《关于开展移动互联网应用程序备案工作的通知》（工信部信管〔2023〕105号）及 App Store Connect 要求：**在中国大陆 App Store 提供的 App 须填写 App 备案号（ICP 备案）**，且备案信息需与 App 元数据（名称等）一致
- 风向家虽无自有服务器，但面向大陆用户分发仍需备案；需由主体（个人或企业）通过接入服务商办理——**阻塞：需 Tom 提供主体信息并办理**
- 替代：首版先不选中国大陆店面（仅其他地区），备案完成后再开放
- 若在大陆提供，`WeatherKit` 在中国大陆的可用性与数据来源需复核

## 6. 审核信息
- 审核备注：`fastlane/metadata/review_information/notes.txt`（无需登录、示例房间入口、内购位置、LiDAR 限制、天气说明）
- 联系人姓名/电话/邮箱：**待填写**
- 内购商品需随首个版本一并提交审核，附审核截图

## 7. 分级与类别
- 年龄分级：4+（无用户生成内容共享、无网页浏览、无社交）
- 主类别：工具；次类别：生活

## 8. 版本发布清单
- [ ] 正式 Bundle ID、Team、版本号；替换全部 `com.example` 占位
- [ ] 开启 WeatherKit（`FXJ_WEATHERKIT`），展示 Apple 天气归属标识与法律链接（替换 #15 占位）
- [ ] `python3 scripts/check_compliance.py` 与 `python3 scripts/check_metadata.py --strict` 通过
- [ ] CI 绿（含 Release 编译）；`docs/release/DEVICE_ACCEPTANCE.md` D1–D15 有真机证据
- [ ] TestFlight 内测 ≥ 1 周，无崩溃阻塞
- [ ] 内购商品、价格、免费范围确认（当前 ¥12 / 1 房间 / 3 方案为占位）
- [ ] 隐私政策、支持页上线
- [ ] 提交审核（人工），选择“手动发布”
- [ ] 契约 #26 定稿并合并（目前所有实现标注“契约暂定”）
