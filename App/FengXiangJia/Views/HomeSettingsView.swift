import SwiftUI
import MapKit
import FXJCore

/// #14 住宅位置与朝向设置。
struct HomeSettingsView: View {
    let model: AppModel
    @State private var home: Home
    @State private var locationText = ""
    @State private var message: String?
    @State private var location = LocationService()
    @State private var calibrating = false
    @State private var calibrationWall = 0
    @State private var camera: MapCameraPosition = .automatic
    @SwiftUI.Environment(\.dismiss) private var dismiss

    init(model: AppModel, home: Home) {
        self.model = model
        _home = State(initialValue: home)
        if let l = home.location {
            _locationText = State(initialValue: String(format: "%.2f, %.2f", l.latitude, l.longitude))
            _camera = State(initialValue: .region(MKCoordinateRegion(center: .init(latitude: l.latitude, longitude: l.longitude),
                                                                     span: .init(latitudeDelta: 0.05, longitudeDelta: 0.05))))
        }
    }

    private var firstRoom: Room? { home.rooms.first }

    var body: some View {
        Form {
            Section {
                TextField("名称", text: $home.name)
            }
            Section {
                MapReader { proxy in
                    Map(position: $camera) {
                        if let l = home.location {
                            Marker(home.name, coordinate: .init(latitude: l.latitude, longitude: l.longitude))
                        }
                    }
                    .onTapGesture { p in
                        if let c = proxy.convert(p, from: .local) {
                            setLocation(Location(latitude: c.latitude, longitude: c.longitude).coarsened())
                        }
                    }
                }
                .frame(height: 200)
                TextField("经纬度，如 1.35, 103.82", text: $locationText)
                    .keyboardType(.numbersAndPunctuation)
                    .onSubmit {
                        do { setLocation(try Location.parse(locationText).coarsened()) }
                        catch { message = "无法识别的经纬度" }
                    }
                Button("使用当前位置（大致）") {
                    Task {
                        do { setLocation(try await location.requestApproximateLocation()) }
                        catch { message = location.authorizationDenied ? "未授权定位，可在地图上点选或手动输入" : "定位失败，请手动输入" }
                    }
                }
                if location.authorizationDenied, let url = URL(string: UIApplication.openSettingsURLString) {
                    Link("定位未授权：打开设置（可选）", destination: url).font(.caption)
                }
                if home.location != nil { Button("清除位置", role: .destructive) { home.location = nil; locationText = "" } }
            } header: { Text("住宅位置") } footer: {
                Text("用于查询住宅所在地天气。只保存约 1 km 精度，与手机当前位置无关，不上传。")
            }
            Section {
                LabeledContent("户型上方朝向") { Text("\(Int(home.orientationDegrees))° \(Bearing.name(home.orientationDegrees))") }
                Slider(value: $home.orientationDegrees, in: 0...359, step: 1)
                if let room = firstRoom {
                    ForEach(Array(room.walls.enumerated()), id: \.offset) { i, w in
                        if room.openings.contains(where: { $0.wallId == w.id && $0.kind == .window }) {
                            Text("\(room.name) 第 \(i + 1) 面墙的窗朝\(Bearing.name(w.facingBearing(orientation: home.orientationDegrees)))")
                                .font(.caption)
                        }
                    }
                    Button(calibrating ? "停止指南针" : "用指南针校正") {
                        calibrating.toggle()
                        calibrating ? location.startHeading() : location.stopHeading()
                    }
                    if calibrating {
                        Picker("对准的墙", selection: $calibrationWall) {
                            ForEach(room.walls.indices, id: \.self) { Text("第 \($0 + 1) 面墙外侧").tag($0) }
                        }
                        Text("站在房间内，手机顶部指向所选墙的外侧（垂直于墙）。")
                            .font(.caption)
                        if let h = location.heading {
                            Text("当前读数 \(Int(h))°\(location.headingIsTrueNorth ? "（真北）" : "（磁北，可能有偏差）")").font(.caption)
                        } else {
                            Text("等待指南针读数…（模拟器无指南针）").font(.caption).foregroundStyle(.secondary)
                        }
                        Button("确定校正") {
                            guard room.walls.indices.contains(calibrationWall),
                                  let mean = Bearing.circularMean(location.headingSamples) else {
                                message = "读数不稳定，请保持手机静止后重试"; return
                            }
                            home.orientationDegrees = Bearing.orientation(heading: mean, planDirection: room.walls[calibrationWall].outwardNormal)
                            calibrating = false; location.stopHeading()
                        }
                    }
                }
            } header: { Text("朝向") } footer: {
                Text("朝向决定室外风从哪扇窗进入。可手动调整，也可用指南针辅助。")
            }
            if let message { Text(message).foregroundStyle(.orange) }
        }
        .navigationTitle("住宅设置")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { model.updateHomeInfo(name: home.name, location: home.location, orientation: home.orientationDegrees); dismiss() }
            }
        }
        .onDisappear { location.stopHeading() }
    }

    private func setLocation(_ l: Location) {
        home.location = l
        locationText = String(format: "%.2f, %.2f", l.latitude, l.longitude)
        message = nil
        camera = .region(MKCoordinateRegion(center: .init(latitude: l.latitude, longitude: l.longitude),
                                            span: .init(latitudeDelta: 0.05, longitudeDelta: 0.05)))
    }
}
