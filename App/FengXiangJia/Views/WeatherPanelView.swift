import SwiftUI
import FXJCore

/// #15 外部风况面板：自动（住宅位置天气）/ 手动 / 不考虑室外风。
struct WeatherPanelView: View {
    let model: AppModel

    var body: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 6) {
                Picker("风况来源", selection: Binding(get: { model.environmentMode }, set: { model.setEnvironmentMode($0) })) {
                    Text("住宅天气").tag(EnvironmentMode.auto)
                    Text("手动").tag(EnvironmentMode.manual)
                    Text("不考虑").tag(EnvironmentMode.none)
                }
                .pickerStyle(.segmented)
                switch model.environmentMode {
                case .auto:
                    if model.entitlement.allows(.liveWeather) { autoSection } else {
                        Text("住宅天气属于完整版（我家 › 完整版）。可改用“手动”输入风向，功能不受影响。").font(.caption)
                    }
                case .manual: manualSection
                case .none: Text("只模拟风扇带来的室内循环。").font(.caption)
                }
            }
        } label: {
            Text(summary).font(.footnote)
        }
    }

    private var summary: String {
        guard let e = model.currentEnvironment else { return "室外风况：未使用" }
        return "室外风况：\(Bearing.name(e.windFromDegrees))风 \(String(format: "%.1f", e.windSpeed ?? 0)) m/s"
    }

    @ViewBuilder private var autoSection: some View {
        if model.document?.home.location == nil {
            Text("尚未设置住宅位置。请在“我家 → 位置与朝向”中设置，或改用手动风况。").font(.caption).foregroundStyle(.orange)
        } else if model.weatherLoading {
            ProgressView().controlSize(.small)
        } else if let o = model.weatherOutcome {
            if let e = o.environment {
                Text("\(Bearing.name(e.windFromDegrees))风 \(String(format: "%.1f", e.windSpeed ?? 0)) m/s · \(e.temperature.map { String(format: "%.0f℃", $0) } ?? "—") · 降雨 \(e.rain.map { String(format: "%.1f mm/h", $0) } ?? "—")")
                    .font(.caption)
                Text("时间 \(e.observedAt.formatted(date: .omitted, time: .shortened))\(o.fromCache ? " · 缓存" : "")\(o.freshness == .stale ? " · 已过期，仅供参考" : "")")
                    .font(.caption2).foregroundStyle(o.freshness == .stale ? .orange : .secondary)
            }
            if let err = o.error {
                Text(Self.describe(err)).font(.caption).foregroundStyle(.orange)
            }
            if let a = o.forecast?.attribution {
                // Apple 天气署名占位：启用 WeatherKit 后显示官方标识与法律链接
                if let url = a.legalPageURL { Link("数据来源：\(a.serviceName)", destination: url).font(.caption2) }
                else { Text("数据来源：\(a.serviceName)").font(.caption2).foregroundStyle(.secondary) }
            }
            Text("区域天气并非窗边实测风速。").font(.caption2).foregroundStyle(.secondary)
            Button("刷新") { Task { await model.refreshWeather(force: true) } }.font(.caption)
        } else {
            Button("获取住宅天气") { Task { await model.refreshWeather(force: false) } }.font(.caption)
        }
    }

    private var manualSection: some View {
        VStack(alignment: .leading) {
            LabeledContent("来向 \(Bearing.name(model.manualWindFrom))（\(Int(model.manualWindFrom))°）") {
                Slider(value: Binding(get: { model.manualWindFrom }, set: { model.manualWindFrom = $0 }), in: 0...345, step: 15)
            }
            LabeledContent("风速 \(String(format: "%.1f", model.manualWindSpeed)) m/s") {
                Slider(value: Binding(get: { model.manualWindSpeed }, set: { model.manualWindSpeed = $0 }), in: 0...8, step: 0.5)
            }
        }
        .font(.caption)
    }

    static func describe(_ e: WeatherError) -> String {
        switch e {
        case .noLocation: return "未设置住宅位置"
        case .offline: return "网络不可用，请稍后重试或改用手动风况"
        case .notAuthorized: return "天气服务未授权，请改用手动风况"
        case .unavailable(let m): return "天气暂不可用（\(m)），可改用手动风况"
        }
    }
}
