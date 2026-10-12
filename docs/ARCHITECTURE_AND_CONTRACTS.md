# 架构与接口契约 v0.1

## 模块
Domain（房屋/房间/门窗/设备/场景） → Geometry（坐标/拓扑） → Simulation（轻量流场） → Recommendation（候选比较与解释） → UI（SwiftUI）；外部输入通过独立 Adapters（RoomPlan、Location、Weather、StoreKit）。模块间不得反向依赖 UI。

## 坐标与单位
室内二维右手笛卡尔坐标；米为长度单位，度为用户输入方向（内部转换弧度），风向必须记录“来向/去向”的定义及向量转换。RoomPlan 坐标先转换到 Domain 坐标。每个门窗记录宿主墙段、开口起止及连通的内/外区域；不得穿墙或制造隐形通道。

## 实体
Home{id,name,location?,orientation,rooms[],constraints}；Room{id,polygon,height?,purpose}；Opening{id,wallId,connects,kind,openFraction,locked}；Obstacle{id,footprint,heightClass}；Fan{id,position,yaw,power,kind}；Target{id,shape,objective}；Environment{source,observedAt,windFromDegrees,windSpeed?,temperature?,rain?,obstructionByOpening}；Scenario{id,geometryRevision,environmentSnapshot,layoutSnapshot,targets}；SimulationResult{scenarioHash,modelVersion,status,field,metrics,explanations}。

## 不变量
- 场景及环境输入变化时旧结果 invalidated。
- A/B 比较共享同一 Environment 快照与图例范围。
- 建议来自用于绘制的 SimulationResult；来源未知时标注“不确定”。
- 全闭合外围护结构中的风扇不得被解释成室外换气增加。
- RoomPlan 扫描结果可编辑；定位失败不阻断流程；天气过期明确显示；拒绝麦克风也可完成主流程。

## 持久化
保存 Schema 版本、实体 id 和场景条件；支持迁移、删除和导出。私密住宅数据只在本地默认存储；日志脱敏。