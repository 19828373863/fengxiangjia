package app.fengxiangjia.core.library

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.geometry.Topology
import app.fengxiangjia.core.persistence.HomeDocument

/** 内置示例房间（ID 固定，与 iOS `SampleData` 完全相同，跨平台黄金样例依赖此数据）。 */
object SampleData {
    const val HOME_ID = "00000000-0000-0000-0000-00000000F001"
    const val ROOM_ID = "00000000-0000-0000-0000-00000000F002"

    fun sampleDocument(): HomeDocument {
        val wallIds = (1..4).map { "00000000-0000-0000-0000-0000000A%04d".format(it) }
        val room = Room(
            id = ROOM_ID, name = "示例客厅",
            polygon = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)),
            wallIds = wallIds, height = 2.7, purpose = RoomPurpose.living,
            openings = listOf(
                Opening(id = "00000000-0000-0000-0000-0000000B0001", wallId = wallIds[0], kind = OpeningKind.window, offset = 1.5, width = 2.0),
                Opening(id = "00000000-0000-0000-0000-0000000B0002", wallId = wallIds[2], kind = OpeningKind.door, offset = 3.5, width = 0.9, openFraction = 0.5),
            ),
            obstacles = listOf(
                Obstacle(id = "00000000-0000-0000-0000-0000000C0001", kind = ObstacleKind.sofa, center = Vec2(2.5, 3.4), size = Vec2(2.2, 0.9), heightClass = HeightClass.low),
                Obstacle(id = "00000000-0000-0000-0000-0000000C0002", kind = ObstacleKind.table, center = Vec2(2.5, 2.2), size = Vec2(1.2, 0.6), heightClass = HeightClass.low),
            ),
            fans = listOf(Fan(id = "00000000-0000-0000-0000-0000000D0001", kind = FanKind.pedestal, position = Vec2(4.3, 0.8), yawDegrees = 135.0, power = 0.6)),
            targets = listOf(Target(id = "00000000-0000-0000-0000-0000000E0001", name = "沙发",
                shape = TargetShape.Circle(Vec2(2.5, 3.4), 0.8), objective = TargetObjective.wantBreeze)),
        )
        val home = Topology.refreshConnections(Home(id = HOME_ID, name = "示例住宅", orientationDegrees = 0.0, rooms = listOf(room)))
        return HomeDocument(home = home)
    }
}
