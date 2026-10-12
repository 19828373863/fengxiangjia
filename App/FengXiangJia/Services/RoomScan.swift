import SwiftUI
import FXJCore
import AVFoundation
#if canImport(RoomPlan)
import RoomPlan
#endif

/// #17 RoomPlan 扫描适配层。仅兼容 LiDAR 设备（RoomCaptureSession.isSupported）；其他设备与模拟器降级为手绘。
enum RoomScanSupport {
    static var isSupported: Bool {
        #if canImport(RoomPlan) && !targetEnvironment(simulator)
        return RoomCaptureSession.isSupported
        #else
        return false
        #endif
    }
}

#if canImport(RoomPlan)
extension ScanImport {
    /// CapturedRoom → 纯逻辑中间结构 → Domain Room。
    static func convert(_ captured: CapturedRoom, name: String = "扫描的房间") -> ScanImportResult? {
        func wall(_ s: CapturedRoom.Surface) -> ScannedWall {
            let t = s.transform
            return ScannedWall(centerX: Double(t.columns.3.x), centerZ: Double(t.columns.3.z),
                               axisX: Double(t.columns.0.x), axisZ: Double(t.columns.0.z),
                               width: Double(s.dimensions.x), height: Double(s.dimensions.y))
        }
        func opening(_ s: CapturedRoom.Surface) -> ScannedOpening {
            let kind: ScannedOpening.Kind
            switch s.category {
            case .door(let isOpen): kind = .door(isOpen: isOpen)
            case .window: kind = .window
            default: kind = .opening
            }
            return ScannedOpening(kind: kind, centerX: Double(s.transform.columns.3.x), centerZ: Double(s.transform.columns.3.z),
                                  width: Double(s.dimensions.x))
        }
        return convert(walls: captured.walls.map(wall),
                       openings: (captured.doors + captured.windows + captured.openings).map(opening), name: name)
    }
}

final class ScanCoordinator: NSObject, RoomCaptureViewDelegate {
    var onResult: ((CapturedRoom?, Error?) -> Void)?
    weak var captureView: RoomCaptureView?

    override init() { super.init() }
    required init?(coder: NSCoder) { super.init() }
    func encode(with coder: NSCoder) {}

    func captureView(shouldPresent roomDataForProcessing: CapturedRoomData, error: Error?) -> Bool { true }
    func captureView(didPresent processedResult: CapturedRoom, error: Error?) { onResult?(processedResult, error) }
}

struct RoomCaptureRepresentable: UIViewRepresentable {
    let coordinator: ScanCoordinator

    func makeUIView(context: Context) -> RoomCaptureView {
        let v = RoomCaptureView(frame: .zero)
        v.delegate = coordinator
        coordinator.captureView = v
        v.captureSession.run(configuration: RoomCaptureSession.Configuration())
        return v
    }

    func updateUIView(_ uiView: RoomCaptureView, context: Context) {}

    static func dismantleUIView(_ uiView: RoomCaptureView, coordinator: ()) {
        uiView.captureSession.stop()
    }
}
#endif

/// 扫描入口：不支持时说明原因并引导手绘；扫描完成后进入编辑器校准修正。
struct RoomScanView: View {
    let onImported: (Room) -> Void
    @State private var result: ScanImportResult?
    @State private var errorText: String?
    @State private var finished = false
    #if canImport(RoomPlan)
    @State private var coordinator = ScanCoordinator()
    #endif
    @SwiftUI.Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if !RoomScanSupport.isSupported {
                ContentUnavailableView {
                    Label("此设备不支持扫描", systemImage: "camera.metering.unknown")
                } description: {
                    Text("房间扫描需要配备 LiDAR 的 iPhone / iPad Pro（iOS 16+）。可改用手绘户型，功能完全相同。")
                } actions: {
                    Button("改用手绘") { dismiss() }.buttonStyle(.borderedProminent)
                }
            } else if let result {
                FloorplanEditorView(room: result.room) { edited in onImported(edited); dismiss() }
                    .safeAreaInset(edge: .top) {
                        if !result.warnings.isEmpty {
                            Text(Self.describe(result.warnings)).font(.caption).padding(8)
                                .frame(maxWidth: .infinity).background(.yellow.opacity(0.2))
                        }
                    }
            } else {
                scanner
            }
        }
        .navigationTitle("扫描房间")
    }

    @ViewBuilder private var scanner: some View {
        #if canImport(RoomPlan)
        ZStack(alignment: .bottom) {
            RoomCaptureRepresentable(coordinator: coordinator).ignoresSafeArea()
            VStack {
                if let errorText {
                    Text(errorText).foregroundStyle(.red)
                    if let url = URL(string: UIApplication.openSettingsURLString) { Link("打开设置", destination: url) }
                }
                Button(finished ? "处理中…" : "完成扫描") {
                    finished = true
                    coordinator.captureView?.captureSession.stop()
                }
                .buttonStyle(.borderedProminent).disabled(finished)
            }
            .padding()
        }
        .onAppear {
            coordinator.onResult = { room, error in
                Task { @MainActor in
                    guard let room, error == nil, let r = ScanImport.convert(room) else {
                        // #20：相机被拒绝时 RoomPlan 会报错；给出去设置或手绘的路径
                        errorText = AVCaptureDevice.authorizationStatus(for: .video) == .denied
                            ? "未授权相机。可在“设置”中允许，或改用手绘户型。"
                            : "扫描结果无法识别墙体，请重试或改用手绘"
                        finished = false; return
                    }
                    result = r
                }
            }
        }
        #else
        EmptyView()
        #endif
    }

    static func describe(_ w: [ScanWarning]) -> String {
        w.map {
            switch $0 {
            case .notClosedApproximated: return "墙体未闭合，已按外轮廓近似"
            case .tooFewWalls: return "识别到的墙太少"
            case .openingUnmatched(let n): return "\(n) 处门窗未能对应到墙"
            case .validation: return "形状需手工修正"
            }
        }.joined(separator: "；") + "。请在下方校准后保存。窗户开关状态无法识别，默认关闭。"
    }
}
