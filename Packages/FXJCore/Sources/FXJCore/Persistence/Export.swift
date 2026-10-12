import Foundation

/// 导出（用户主动触发，经系统分享面板）。默认去除位置等敏感信息。
public enum DocumentExport {
    public struct Options: Sendable {
        public var includeLocation = false
        public var includeFeedbackNotes = false
        public init(includeLocation: Bool = false, includeFeedbackNotes: Bool = false) {
            self.includeLocation = includeLocation; self.includeFeedbackNotes = includeFeedbackNotes
        }
    }

    public static func redacted(_ doc: HomeDocument, options: Options = .init()) -> HomeDocument {
        var d = doc
        if !options.includeLocation { d.home.location = nil }
        if !options.includeFeedbackNotes { for i in d.feedback.indices { d.feedback[i].note = nil } }
        return d
    }

    public static func json(_ doc: HomeDocument, options: Options = .init()) throws -> Data {
        try DocumentCodec.encode(redacted(doc, options: options))
    }

    public static func fileName(for doc: HomeDocument, date: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyyMMdd-HHmm"
        return "fengxiangjia-\(f.string(from: date)).json"
    }
}
