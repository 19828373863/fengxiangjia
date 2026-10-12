import XCTest
@testable import FXJCore

/// 计次的 provider，用于验证缓存命中。
final class CountingProvider: WeatherProvider, @unchecked Sendable {
    var calls = 0
    var fail: WeatherError?
    let inner = MockWeatherProvider()
    func forecast(for location: Location, now: Date) async throws -> WeatherForecast {
        calls += 1
        if let fail { throw fail }
        return try await inner.forecast(for: location, now: now)
    }
}

final class WeatherTests: XCTestCase {
    let home = Location(latitude: 1.3521, longitude: 103.8198)
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testMockIsDeterministicAndLabeled() async throws {
        let a = try await MockWeatherProvider().forecast(for: home, now: t0)
        let b = try await MockWeatherProvider().forecast(for: home, now: t0)
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.hours.count, 24)
        XCTAssertTrue(a.isDemo)
        XCTAssertNotNil(a.attribution)
        XCTAssertNotNil(a.hour(at: t0))
        XCTAssertNil(a.hour(at: t0.addingTimeInterval(-7200)))
    }

    func testCacheHitAndRefresh() async {
        let p = CountingProvider()
        let s = WeatherService(provider: p)
        let first = await s.environment(for: home, now: t0)
        XCTAssertNotNil(first.environment)
        XCTAssertFalse(first.fromCache)
        let second = await s.environment(for: home, now: t0.addingTimeInterval(600))
        XCTAssertTrue(second.fromCache)
        XCTAssertEqual(p.calls, 1)
        _ = await s.environment(for: home, now: t0.addingTimeInterval(4000)) // 超过 1 h → 重新查询
        XCTAssertEqual(p.calls, 2)
    }

    func testOfflineFallsBackToStaleCache() async {
        let p = CountingProvider()
        let s = WeatherService(provider: p)
        _ = await s.environment(for: home, now: t0)
        p.fail = .offline
        let o = await s.environment(for: home, now: t0.addingTimeInterval(2 * 3600))
        XCTAssertTrue(o.fromCache)
        XCTAssertEqual(o.freshness, .stale)
        XCTAssertEqual(o.error, .offline)
        XCTAssertNotNil(o.environment)
        let expired = await s.environment(for: home, now: t0.addingTimeInterval(8 * 3600))
        XCTAssertNil(expired.environment, "过期缓存不得冒充当前天气")
        XCTAssertEqual(expired.error, .offline)
    }

    func testNotAuthorizedAndNoLocation() async {
        let s = WeatherService(provider: MockWeatherProvider(failure: .notAuthorized))
        let o = await s.environment(for: home, now: t0)
        XCTAssertNil(o.environment); XCTAssertEqual(o.error, .notAuthorized)
        let n = await s.environment(for: nil, now: t0)
        XCTAssertEqual(n.error, .noLocation)
    }

    func testEnvironmentCarriesObstructionAndTimestamp() async {
        let id = UUID()
        let o = await WeatherService(provider: MockWeatherProvider()).environment(for: home, now: t0, obstruction: [id: .nearBuilding])
        XCTAssertEqual(o.environment?.obstructionByOpening[id], .nearBuilding)
        XCTAssertEqual(o.environment?.source, .assumed)
        XCTAssertLessThanOrEqual(abs(o.environment!.observedAt.timeIntervalSince(t0)), 3600)
    }

    func testCacheKeyCoarsenedAndCodable() throws {
        var c = WeatherCache()
        XCTAssertEqual(WeatherCache.key(home), WeatherCache.key(Location(latitude: 1.3549, longitude: 103.8151)))
        c.store(WeatherForecast(location: home, fetchedAt: t0, hours: [HourlyWeather(date: t0, windFromDegrees: 10, windSpeed: 2)],
                                source: .weatherKit, isDemo: false, attribution: nil))
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        XCTAssertEqual(try dec.decode(WeatherCache.self, from: enc.encode(c)), c)
    }

    func testManualEnvironment() {
        let e = Environment.manual(windFromDegrees: -90, windSpeed: -3, at: t0)
        XCTAssertEqual(e.windFromDegrees, 270); XCTAssertEqual(e.windSpeed, 0); XCTAssertEqual(e.source, .manual)
    }
}
