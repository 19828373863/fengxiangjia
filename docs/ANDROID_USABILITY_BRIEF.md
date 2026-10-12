# Android usable product — Issue 70

## Authority and acceptance

用户反馈原版几乎无法使用，明确要求界面美观、由本会话独立完成并在明早交付。用户拒绝采访并授权独立决策。基于原生 Android 已有功能与产品文档推导，不再要求额外选择。完成要求：核心编辑和试摆顺畅、状态可读、完整测试构建可体验、保存恢复可靠，模拟器和 K30 的实际操作有证据。仅启动不构成完整验收。

## Direction contract

Operate 模式，原生 Compose / Material 3。候选世界来自家居阅读和家庭日常：住宅图册、家居选物页、生活方式编辑页、地方植物图鉴、温暖陶器、家具目录、现代家居杂志。本次 concept-seed a3279ed4 分配第 7 项，采用现代家居杂志的平静版面：暖白底、深墨绿正文/操作、柔和鼠尾草辅助色、克制的陶土状态色，清楚的中文系统字体层级。杂志仅贡献颜色、排版和留白；应用始终使用原生导航、按钮、输入和底部面板。

First viewport: 明确「风向家」和「我家」，当前房间的可读平面预览、名称/面积/门窗摘要、主要按钮「开始试摆」，次要创建入口。展示示例的身份，不把样例说成用户真实数据。底部四个目的地「我家 / 试摆 / 方案 / 建议」使用真实矢量图标。

Signature interaction: 房间图是工作的视觉中心；选中实体有足够大的控点和明确高亮，底部原生属性面板响应所选物体。移动时立即预览，松手仅提交一次并计算；同一图在房间页和方案页作为可读预览。

Cross-surface: 我家以房间开始；试摆优先画布和一句指导；方案清楚区分保存/恢复/比较；建议提供可见有效结果并保持旧数据标记，天气与位置作为进阶设置按需展开。Material 3 主题提供明暗模式，系统字体大小变化保持可操作。

## Findings and decisions

- P0: 免费单房间占满且无法购买的侧载构建让「创建」与自动建议全部被阻断。完整测试构建明确提供体验权限，正式商店权益独立。rule/value-before-interruption。
- P0: RoomCanvas.pointerInput(room) 在每次预览修改 room 时重启手势；画布不能稳定拖动。手势生命周期按房间 id/模式，捕获稳定视口并读取最新回调。rule/preserve-user-input。
- P1: 操作表单挤占画布，六种模式隐藏在横向滑动条。编辑工具和属性按需展开，原生滑动底部面板避免嵌套弹层；方向/位置可用按钮输入替代拖动。rule/gesture-has-control-alternative、rule/inline-before-modal。
- P1: 数据保存、恢复、删除的反馈缺失，失败清空方案名称。保存仅成功清空输入，恢复明显反馈，永久删除明确作用对象和范围。rule/preserve-user-input、rule/irreversible-action-safeguard、rule/success-state-specific。
- P1: 旧结果可误用，异步建议没有最终失败状态。运行状态稳定，捕获输入与 hash 后只应用仍当前的候选，错误有重试。rule/cover-reachable-states。
- P1: 系统默认主题、emoji 导航、拥挤同级控件。统一 Material 3 语义主题/字号与矢量图标，核心动作明确。rule/one-primary-action、rule/structure-before-containers。

## Bounded verification

先完整实现，集中一轮真实屏幕与功能检查，按发现集中修复，确认一轮。原生截图来自设备/模拟器；不以网页图代替。完整测试构建和正式结算必须说明实际范围。K30 当前有其他工作占用时先在隔离模拟器验收，终交付仍以 K30 安装与核心操作为准。
