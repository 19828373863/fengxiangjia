// WeatherKit 适配器（#15）。需要 Apple Developer 账号与 WeatherKit capability，
// 默认不编译：在 project.yml 的 SWIFT_ACTIVE_COMPILATION_CONDITIONS 加入 FXJ_WEATHERKIT 后启用。
#if FXJ_WEATHERKIT && canImport(WeatherKit)
import Foundation
import CoreLocation
import WeatherKit
import FXJCore

struct WeatherKitProvider: WeatherProvider {
    func forecast(for location: Location, now: Date) async throws -> WeatherForecast {
        let service = WeatherKit.WeatherService.shared
        let loc = CLLocation(latitude: location.latitude, longitude: location.longitude)
        let hourly = try await service.weather(for: loc, including: .hourly)
        let attribution = try? await service.attribution
        let hours = hourly.forecast.prefix(24).map {
            HourlyWeather(date: $0.date,
                          windFromDegrees: $0.wind.direction.converted(to: .degrees).value,
                          windSpeed: $0.wind.speed.converted(to: .metersPerSecond).value,
                          temperature: $0.temperature.converted(to: .celsius).value,
                          precipitation: $0.precipitationAmount.converted(to: .millimeters).value)
        }
        return FXJCore.WeatherForecast(location: location, fetchedAt: now, hours: Array(hours), source: .weatherKit, isDemo: false,
                                       attribution: WeatherAttribution(serviceName: attribution?.serviceName ?? "Apple 天气",
                                                                       legalPageURL: attribution?.legalPageURL))
    }
}
#endif
