import Foundation

public enum RoomIssue: Hashable, Sendable {
    case tooFewVertices
    case wallIdCountMismatch
    case degenerateEdge(index: Int)
    case selfIntersecting(edgeA: Int, edgeB: Int)
    case clockwiseWinding
    case zeroArea
    case openingUnknownWall(opening: EntityID)
    case openingOutsideWall(opening: EntityID)
    case openingTooNarrow(opening: EntityID)
    case openingInvalidFraction(opening: EntityID)
    case openingsOverlap(EntityID, EntityID)
    case itemOutsideRoom(item: EntityID)
}

public enum RoomValidator {
    public static let minEdgeLength = 0.1   // 米
    public static let minOpeningWidth = 0.2 // 米

    public static func validate(_ room: Room) -> [RoomIssue] {
        var issues: [RoomIssue] = []
        let p = room.polygon
        guard p.count >= 3 else { return [.tooFewVertices] }
        guard room.wallIds.count == p.count else { return [.wallIdCountMismatch] }
        for i in p.indices where p[i].distance(to: p[(i + 1) % p.count]) < minEdgeLength {
            issues.append(.degenerateEdge(index: i))
        }
        if let (a, b) = Geometry.selfIntersection(p) { issues.append(.selfIntersecting(edgeA: a, edgeB: b)) }
        let area = Geometry.signedArea(p)
        if abs(area) < 0.01 { issues.append(.zeroArea) } else if area < 0 { issues.append(.clockwiseWinding) }

        let walls = Dictionary(uniqueKeysWithValues: room.walls.map { ($0.id, $0) })
        var spans: [EntityID: [(EntityID, Double, Double)]] = [:]
        for o in room.openings {
            guard let w = walls[o.wallId] else { issues.append(.openingUnknownWall(opening: o.id)); continue }
            if o.width < minOpeningWidth { issues.append(.openingTooNarrow(opening: o.id)) }
            if o.offset < -Geometry.epsilon || o.offset + o.width > w.length + Geometry.epsilon {
                issues.append(.openingOutsideWall(opening: o.id))
            }
            if !(0...1).contains(o.openFraction) { issues.append(.openingInvalidFraction(opening: o.id)) }
            for (other, s, e) in spans[o.wallId, default: []] where o.offset < e - Geometry.epsilon && s < o.offset + o.width - Geometry.epsilon {
                issues.append(.openingsOverlap(other, o.id))
            }
            spans[o.wallId, default: []].append((o.id, o.offset, o.offset + o.width))
        }
        for f in room.fans where !room.contains(f.position) { issues.append(.itemOutsideRoom(item: f.id)) }
        for ob in room.obstacles where !room.contains(ob.center) { issues.append(.itemOutsideRoom(item: ob.id)) }
        return issues
    }
}
