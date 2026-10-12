import Foundation

/// 首页加载状态（UI 据此显示加载/空/错误/内容）。
public enum HomeLoadState: Equatable, Sendable {
    case loading
    case empty
    case loaded(HomeDocument)
    case failed(String)
}

/// 本地文件存储（默认 App Documents 目录，仅本地，不上传）。
public struct HomeRepository: Sendable {
    public let fileURL: URL
    public init(directory: URL, fileName: String = "home.json") {
        fileURL = directory.appendingPathComponent(fileName)
    }

    public func load() -> HomeLoadState {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return .empty }
        do {
            let data = try Data(contentsOf: fileURL)
            return .loaded(try DocumentCodec.decode(data))
        } catch let e as PersistenceError {
            switch e {
            case .unsupportedFutureVersion(let v): return .failed("文件来自较新版本（schema v\(v)），请更新 App")
            case .missingSchemaVersion, .corrupt: return .failed("保存的数据已损坏，无法读取")
            }
        } catch {
            return .failed("读取失败：\(error.localizedDescription)")
        }
    }

    public func save(_ doc: HomeDocument) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try DocumentCodec.encode(doc).write(to: fileURL, options: .atomic)
    }

    public func delete() throws {
        if FileManager.default.fileExists(atPath: fileURL.path) { try FileManager.default.removeItem(at: fileURL) }
    }
}
