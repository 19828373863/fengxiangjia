# AI Agent 协作规范

本文件是所有开发智能体进入仓库后必须阅读的指令。

1. **先读上下文**：README.md、docs/PRODUCT_AND_DEVELOPMENT.md、docs/ARCHITECTURE_AND_CONTRACTS.md、TASK_STATUS.md、HANDOFF.md，以及对应 GitHub Issue 和关联 PR。
2. **一项任务一名负责人**：从 Issue 认领任务，评论目标、实施范围、预计改动文件及测试方案；没有认领不要与其他 Agent 并行修改同一区域。
3. **所有变更使用独立分支**：feat/<issue>-description、fix/<issue>-description、docs/<issue>-description。禁止直接向 main 推送开发变更；初始化阶段由项目管理员提交基础文件。
4. **必须 PR 合并**：PR 关联 Issue，列出变更、测试命令和实际结果、截图、风险、未完成项；审阅通过后合并。绝不把“写了测试”当作“测试通过”。
5. **接口先定后改**：涉及坐标系、房间拓扑、天气字段、流场计算输入输出、持久化 Schema、付费权限等公共契约，先在 Issue 讨论，记录到 docs/ARCHITECTURE_AND_CONTRACTS.md 与 DECISIONS.md，再跨模块实现。
6. **禁止假进度**：明确区分方案完成、源码实现、CI 编译、模拟器通过、真机验证、TestFlight、App Store 审核。不能编造构建结果或声称未经测试的模型准确。
7. **建议与可视化同源**：风线和推荐必须来自同一次计算结果；过期或失败结果标记待更新，不能继续展示成当前结果。
8. **范围控制**：面向生活用途，不擅自升级成专业 CFD 产品。原始户型、准确地址和麦克风录音不默认上传；无需登录可用核心功能。
9. **交接格式**：在 HANDOFF.md 与 Issue 留下：任务编号、分支、提交 SHA、修改文件、已执行测试、未测试内容、阻塞、下一个具体动作。
10. **复用优先**：采用 Apple 平台能力和审查过许可证的开源库；记录第三方依赖与许可证，不照搬其他商业 App 的素材与源码。

遇到不确定之处，优先建立可运行的最小闭环和明确 TODO，而不是擅自扩大范围或搁置全部工作。