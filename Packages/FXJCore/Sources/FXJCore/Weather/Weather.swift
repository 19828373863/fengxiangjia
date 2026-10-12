import Foundation

/// #15 天气：按住宅位置查询逐小时风况。区域天气 ≠ 窗边实测，界面需注明来源与时间。
public struct HourlyWeather: Codable, Hashable, Sendable {
    public var date: Date
    /// 来向，真北顺时针（度）。
    public var windFromDegrees: Double
    /// m/s
    public var windSpeed: Double
    /// ℃
    public var temperature: Double?
    /// mm/h
    public var precipitation: Double?
    public init(date: Date, windFromDegrees: Double, windSpeed: Double, temperature: Double? = nil, precipitation: Double? = nil) {
        self.date = date; self.windFromDegrees = windFromDegrees; self.windSpeed = windSpeed
        self.temperature = temperature; self.precipitation = precipitation
    }
}

public struct WeatherAttribution: Codable, Hashable, Sendable {
    public var serviceName: String
    public var legalPageURL: URL?
    public init(serviceName: String, legalPageURL: URL?) { self.serviceName = serviceName; self.legalPageURL = legalPageURL }
}

public struct WeatherForecast: Codable, Hashable, Sendable {
    public var location: Location
    public var fetchedAt: Date
    public var hours: [HourlyWeather]
    public var source: EnvironmentSource
    /// 是否为演示/模拟数据（非真实天气）。
    public var isDemo: Bool
    public var attribution: WeatherAttribution?

    public init(location: Location, fetchedAt: Date, hours: [HourlyWeather], source: EnvironmentSource, isDemo: Bool, attribution: WeatherAttribution?) {
        self.location = location; self.fetchedAt = fetchedAt; self.hours = hours.sorted { $0.date < $1.date }
        self.source = source; self.isDemo = isDemo; self.attribution = attribution
    }

    /// 最接近 `date` 的小时（超出预报范围 ±1 h 返回 nil）。
    public func hour(at date: Date) -> HourlyWeather? {
        guard let h = hours.min(by: { abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date)) }),
              abs(h.date.timeIntervalSince(date)) <= 3600 else { return nil }
        return h
    }
}

public enum WeatherError: Error, Equatable, Sendable { case noLocation, offline, notAuthorized, unavailable(String) }

public protocol WeatherProvider: Sendable {
    func forecast(for location: Location, now: Date) async throws -> WeatherForecast
}

/// 演示用天气（无 Apple 开发者账号时使用）：按位置与时间确定性生成，明确标注为演示数据。
public struct MockWeatherProvider: WeatherProvider {
    public var failure: WeatherError?
    public init(failure: WeatherError? = nil) { self.failure = failure }

    public func forecast(for location: Location, now: Date) async throws -> WeatherForecast {
        if let failure { throw failure }
        let start = (now.timeIntervalSince1970 / 3600).rounded(.down) * 3600
        let seed = (location.latitude * 7 + location.longitude * 3).truncatingRemainder(dividingBy: 360)
        let hours = (0..<24).map { i -> HourlyWeather in
            let t = start + Double(i) * 3600
            let phase = (t / 3600).truncatingRemainder(dividingBy: 24) / 24 * 2 * .pi
            return HourlyWeather(date: Date(timeIntervalSince1970: t),
                                 windFromDegrees: Bearing.normalize(seed + 40 * sin(phase)),
                                 windSpeed: ((2.5 + 1.5 * sin(phase + 1)) * 10).rounded() / 10,
                                 temperature: ((28 + 4 * sin(phase - 2)) * 10).rounded() / 10,
                                 precipitation: i % 9 == 0 ? 0.5 : 0)
        }
        return WeatherForecast(location: location, fetchedAt: now, hours: hours, source: .assumed, isDemo: true,
                               attribution: WeatherAttribution(serviceName: "演示数据（非真实天气）", legalPageURL: nil))
    }
}

public enum WeatherFreshness: String, Codable, Sendable { case fresh, stale, expired }

/// 按粗化位置缓存预报；可编码以便本地持久化。
public struct WeatherCache: Codable, Hashable, Sendable {
    public static let freshInterval: TimeInterval = 3600
    public static let staleInterval: TimeInterval = 6 * 3600
    public var entries: [String: WeatherForecast] = [:]
    public init() {}

    public static func key(_ l: Location) -> String {
        let c = l.coarsened()
        return String(format: "%.2f,%.2f", c.latitude, c.longitude)
    }

    public mutating func store(_ f: WeatherForecast) { entries[Self.key(f.location)] = f }
    public func forecast(for l: Location) -> WeatherForecast? { entries[Self.key(l)] }

    public static func freshness(of f: WeatherForecast, now: Date) -> WeatherFreshness {
        let age = now.timeIntervalSince(f.fetchedAt)
        return age < freshInterval ? .fresh : (age < staleInterval ? .stale : .expired)
    }
}

public struct WeatherOutcome: Sendable, Equatable {
    public var environment: Environment?
    public var forecast: WeatherForecast?
    public var freshness: WeatherFreshness?
    public var fromCache: Bool
    public var error: WeatherError?
}

/// 获取住宅当前小时的外部环境：优先新鲜缓存，其次在线查询，失败则回退到未过期缓存，再失败返回错误（界面改用手动风况）。
public actor WeatherService {
    private let provider: WeatherProvider
    public private(set) var cache: WeatherCache

    public init(provider: WeatherProvider, cache: WeatherCache = .init()) {
        self.provider = provider; self.cache = cache
    }

    public func environment(for location: Location?, now: Date, obstruction: [EntityID: ObstructionLevel] = [:],
                            forceRefresh: Bool = false) async -> WeatherOutcome {
        guard let location else { return WeatherOutcome(environment: nil, forecast: nil, freshness: nil, fromCache: false, error: .noLocation) }
        if !forceRefresh, let c = cache.forecast(for: location), WeatherCache.freshness(of: c, now: now) == .fresh {
            return outcome(c, now: now, fromCache: true, obstruction: obstruction, error: nil)
        }
        do {
            let f = try await provider.forecast(for: location.coarsened(), now: now)
            cache.store(f)
            return outcome(f, now: now, fromCache: false, obstruction: obstruction, error: nil)
        } catch {
            let e = (error as? WeatherError) ?? .unavailable(String(describing: error))
            if let c = cache.forecast(for: location), WeatherCache.freshness(of: c, now: now) != .expired {
                return outcome(c, now: now, fromCache: true, obstruction: obstruction, error: e)
            }
            return WeatherOutcome(environment: nil, forecast: nil, freshness: nil, fromCache: false, error: e)
        }
    }

    private func outcome(_ f: WeatherForecast, now: Date, fromCache: Bool, obstruction: [EntityID: ObstructionLevel], error: WeatherError?) -> WeatherOutcome {
        let env = f.hour(at: now).map {
            Environment(source: f.source, observedAt: $0.date, windFromDegrees: $0.windFromDegrees, windSpeed: $0.windSpeed,
                        temperature: $0.temperature, rain: $0.precipitation, obstructionByOpening: obstruction)
        }
        return WeatherOutcome(environment: env, forecast: f, freshness: WeatherCache.freshness(of: f, now: now),
                              fromCache: fromCache, error: env == nil ? (error ?? .unavailable("超出预报范围")) : error)
    }
}

public extension Environment {
    /// 手动/假设风况（无网、未授权或用户主动选择）。
    static func manual(windFromDegrees: Double, windSpeed: Double, at date: Date,
                       obstruction: [EntityID: ObstructionLevel] = [:]) -> Environment {
        Environment(source: .manual, observedAt: date, windFromDegrees: Bearing.normalize(windFromDegrees),
                    windSpeed: max(0, windSpeed), obstructionByOpening: obstruction)
    }
}
