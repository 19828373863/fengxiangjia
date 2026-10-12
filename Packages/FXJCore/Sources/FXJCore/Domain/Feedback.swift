import Foundation

/// 体感反馈（仅本地保存，用于后续校准与个人记录；不上传）。
public enum ComfortRating: String, Codable, Sendable, CaseIterable { case tooWeak, justRight, tooStrong, stuffy }

public struct ComfortFeedback: Codable, Hashable, Identifiable, Sendable {
    public var id: EntityID
    public var roomId: EntityID
    public var scenarioHash: String?
    public var rating: ComfortRating
    public var note: String?
    public var createdAt: Date
    public init(id: EntityID = UUID(), roomId: EntityID, scenarioHash: String?, rating: ComfortRating,
                note: String? = nil, createdAt: Date = Date()) {
        self.id = id; self.roomId = roomId; self.scenarioHash = scenarioHash; self.rating = rating
        self.note = note; self.createdAt = createdAt
    }
}
