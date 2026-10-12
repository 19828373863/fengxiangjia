import Foundation

/// 持久化根对象。D009（提议）：Codable JSON + schemaVersion + 显式迁移。
public struct HomeDocument: Codable, Hashable, Sendable {
    public static let currentSchemaVersion = 2

    public var schemaVersion: Int
    public var geometryRevision: Int
    public var home: Home
    public var scenarios: [Scenario]
    /// v2 新增：体感反馈。
    public var feedback: [ComfortFeedback]

    public init(home: Home, scenarios: [Scenario] = [], geometryRevision: Int = 0, feedback: [ComfortFeedback] = []) {
        self.schemaVersion = Self.currentSchemaVersion
        self.geometryRevision = geometryRevision
        self.home = home; self.scenarios = scenarios; self.feedback = feedback
    }
}

public enum PersistenceError: Error, Equatable {
    case missingSchemaVersion
    case unsupportedFutureVersion(Int)
    case corrupt(String)
}

/// 编解码与迁移。迁移以 JSON 字典为单位逐版本升级：v0 → v1 → ... → current。
public enum DocumentCodec {
    public static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .prettyPrinted]
        e.dateEncodingStrategy = .iso8601
        return e
    }

    public static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }

    public static func encode(_ doc: HomeDocument) throws -> Data { try encoder().encode(doc) }

    public static func decode(_ data: Data) throws -> HomeDocument {
        guard var obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw PersistenceError.corrupt("root is not an object")
        }
        guard let version = obj["schemaVersion"] as? Int else { throw PersistenceError.missingSchemaVersion }
        if version > HomeDocument.currentSchemaVersion { throw PersistenceError.unsupportedFutureVersion(version) }
        var v = version
        while v < HomeDocument.currentSchemaVersion {
            guard let step = migrations[v] else { throw PersistenceError.corrupt("no migration from v\(v)") }
            obj = try step(obj)
            v += 1
            obj["schemaVersion"] = v
        }
        let migrated = try JSONSerialization.data(withJSONObject: obj)
        do { return try decoder().decode(HomeDocument.self, from: migrated) }
        catch { throw PersistenceError.corrupt(String(describing: error)) }
    }

    /// 迁移表：key = 源版本。
    /// v0：开发期原型格式（房间顶点键名 `points`，无 `wallIds`、无 `geometryRevision`）。仅用于验证迁移机制。
    static let migrations: [Int: ([String: Any]) throws -> [String: Any]] = [
        0: { old in
            var doc = old
            guard var home = doc["home"] as? [String: Any] else { throw PersistenceError.corrupt("missing home") }
            let rooms = (home["rooms"] as? [[String: Any]]) ?? []
            home["rooms"] = rooms.map { r -> [String: Any] in
                var r = r
                if let pts = r.removeValue(forKey: "points") { r["polygon"] = pts }
                let n = (r["polygon"] as? [Any])?.count ?? 0
                if r["wallIds"] == nil { r["wallIds"] = (0..<n).map { _ in UUID().uuidString } }
                for k in ["openings", "obstacles", "fans", "targets"] where r[k] == nil { r[k] = [Any]() }
                if r["purpose"] == nil { r["purpose"] = "other" }
                return r
            }
            if home["constraints"] == nil { home["constraints"] = [Any]() }
            if home["orientationDegrees"] == nil { home["orientationDegrees"] = 0 }
            doc["home"] = home
            if doc["geometryRevision"] == nil { doc["geometryRevision"] = 0 }
            if doc["scenarios"] == nil { doc["scenarios"] = [Any]() }
            return doc
        },
        // v1 → v2：新增 feedback 数组。
        1: { old in
            var doc = old
            if doc["feedback"] == nil { doc["feedback"] = [Any]() }
            return doc
        },
    ]
}
