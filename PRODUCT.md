# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

普通家庭用户，想知道风扇放在哪里、朝哪里吹，以及开窗时通风是否有所改善。主要使用场景是手持手机在家里操作。本次用户明确要求独立完成，界面好看，核心功能可用，交付到 Redmi K30。

## Product Purpose

风向家提供房间平面图编辑、风扇试摆、同源气流示意、摆位建议、方案保存与 A/B 比较。成功意味着用户无需专业知识可以创建房间、调整风扇、看懂变化并恢复保存的摆法。

## Operating Context

原生 Kotlin / Jetpack Compose，基于提交 328f22d。手机离线可以完成核心任务；天气和商店需要外部服务；ARCore 扫描有硬件与服务前提。核心户型与方案存储在本机。

## Capabilities and Constraints

保留房间、门窗、家具、目标、风扇类型、朝向、风力、朝向与天气输入、自动建议、保存/导出/比较。简化生活示意模型不可包装成工程测量。结果改变后必须重新计算，并明确旧结果状态。正式购买与扫描能力只能根据真实平台验证描述。

## Brand Commitments

名称：风向家。中文界面，清楚、平静、方便手持操作。用户要求界面美观，其余设计选择授权本会话独立决定。

## Evidence on Hand

README.md、docs/PRODUCT_AND_DEVELOPMENT.md、docs/ARCHITECTURE_AND_CONTRACTS.md、docs/ANDROID_STATUS.md 与现有 Android 源码。已有 K30 安装启动证据；没有完整真机操作验收或真实风场精度证据。

## Product Principles

- 先让用户完成一次实际试摆，进一步设置按需展开。
- 图形和操作来自同一房间与计算输入。
- 手势提供清晰提示与按钮/数值调整替代。
- 保存与错误要可见，失败保留输入。
- 完整测试构建的功能可体验，正式商店权益保持独立。
