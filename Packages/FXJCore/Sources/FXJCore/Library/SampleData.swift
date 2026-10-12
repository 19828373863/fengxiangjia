import Foundation

/// 内置示例房间：首次打开无需登录即可交互。ID 固定，便于测试与重置。
public enum SampleData {
    public static let homeId = UUID(uuidString: "00000000-0000-0000-0000-00000000F001")!
    public static let roomId = UUID(uuidString: "00000000-0000-0000-0000-00000000F002")!

    public static func sampleDocument() -> HomeDocument {
        let wallIds = (1...4).map { UUID(uuidString: String(format: "00000000-0000-0000-0000-0000000A%04d", $0))! }
        var room = Room(id: roomId, name: "示例客厅",
                        polygon: [Vec2(0, 0), Vec2(5, 0), Vec2(5, 4), Vec2(0, 4)],
                        wallIds: wallIds, height: 2.7, purpose: .living)
        room.openings = [
            Opening(id: UUID(uuidString: "00000000-0000-0000-0000-0000000B0001")!, wallId: wallIds[0], kind: .window, offset: 1.5, width: 2.0),
            Opening(id: UUID(uuidString: "00000000-0000-0000-0000-0000000B0002")!, wallId: wallIds[2], kind: .door, offset: 3.5, width: 0.9, openFraction: 0.5),
        ]
        room.obstacles = [
            Obstacle(id: UUID(uuidString: "00000000-0000-0000-0000-0000000C0001")!, kind: .sofa, center: Vec2(2.5, 3.4), size: Vec2(2.2, 0.9), heightClass: .low),
            Obstacle(id: UUID(uuidString: "00000000-0000-0000-0000-0000000C0002")!, kind: .table, center: Vec2(2.5, 2.2), size: Vec2(1.2, 0.6), heightClass: .low),
        ]
        room.fans = [Fan(id: UUID(uuidString: "00000000-0000-0000-0000-0000000D0001")!, kind: .pedestal, position: Vec2(4.3, 0.8), yawDegrees: 135, power: 0.6)]
        room.targets = [Target(id: UUID(uuidString: "00000000-0000-0000-0000-0000000E0001")!, name: "沙发",
                               shape: .circle(center: Vec2(2.5, 3.4), radius: 0.8), objective: .wantBreeze)]
        var home = Home(id: homeId, name: "示例住宅", orientationDegrees: 0, rooms: [room])
        Topology.refreshConnections(&home)
        return HomeDocument(home: home)
    }
}
