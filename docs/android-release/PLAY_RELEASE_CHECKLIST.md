# Android Play 发布准备清单

> 状态说明：✅ 已完成（只指源码或文档） · ⏳ 待办 · ❓ 待 Tom 决定。**目前尚未在任何真机上运行，也未上传到 Play。**

## 1. 待决定（#51）
- ❓ 正式 applicationId（目前占位 `app.fengxiangjia.android`；**上传后不可更改**）
- ❓ 价格，以及 Play Console 商品 ID（代码里占位为 `fengxiangjia_full`）
- ❓ 天气数据源的商用方案（Open-Meteo 商用订阅，或换其他服务）
- ❓ 是否接受 Android 独有的「建议」标签布局
- ❓ 购买是否需要服务器端校验

## 2. 构建与签名
- ⏳ 创建上传密钥（keystore **不入库**；`.gitignore` 已排除 `*.jks` / `*.keystore`），启用 Play App Signing
- ⏳ 配置 release 的 `signingConfig`（从环境变量或 CI secret 读取）
- ⏳ 评估开启 R8 / `isMinifyEnabled`，补 kotlinx.serialization 与 Billing 的 keep 规则，并在 release 包上回归测试
- ⏳ 版本规则：`versionCode` 每次上传递增（目前为 1）；`versionName` 采用语义版本（目前为 0.1.0）
- ⏳ 产出 AAB（`./gradlew bundleRelease`）；targetSdk 35 符合当前 Play 要求，上架前再核对一次
- ⏳ 隐私敏感权限复核：CAMERA、ACCESS_COARSE_LOCATION、INTERNET（目前没有其他权限）

## 3. 测试轨道
- ⏳ 内部测试轨道上传，在 Play Console 加测试者和许可测试账号
- ⏳ 真机矩阵：支持 ARCore 的机型 1–2 台 + 不支持 ARCore 的机型 1 台；Android 8（minSdk 26）和 Android 14/15 各一台
- ⏳ 真机验证：扫描与墙角标记、权限拒绝后的回退、指南针、定位、Open-Meteo 联网和离线、购买成功/取消/Pending/恢复、免费额度、TalkBack 读屏、深色模式、大字体
- ⏳ 用 Pre-launch report 检查崩溃和无障碍问题

## 4. 商店详情
- ⏳ 应用名「风向家」、简短描述（80 字内）、完整描述（写明模型是简化估算，仅供参考）
- ⏳ 图标 512×512、置顶大图 1024×500、手机截图至少 2 张（需在真机上截取）
- ⏳ 分类（工具 / 家居）、联系邮箱、隐私政策 URL
- ⏳ 内容分级问卷、目标受众（非儿童）、广告声明（无广告）
- ⏳ 数据安全表单：见 `PLAY_DATA_SAFETY_DRAFT.md`
- ⏳ 说明中写明：ARCore 是可选的（不支持的设备仍可手绘）、一次性购买内容、天气数据归属

## 5. 上线
- ⏳ 先内部测试，再封闭测试，最后正式发布（分阶段发布从 10% 开始）
- ⏳ 更新 `docs/ANDROID_STATUS.md` 的「商店发布」列
