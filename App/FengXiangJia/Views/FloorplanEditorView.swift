import SwiftUI
import FXJCore

private extension Vec2 {
    init(_ p: CGPoint) { self.init(Double(p.x), Double(p.y)) }
    var cg: CGPoint { CGPoint(x: x, y: y) }
}

enum EditorTool: String, CaseIterable, Identifiable {
    case select, window, door, furniture, fan
    var id: String { rawValue }
    var title: String {
        switch self {
        case .select: return "选择"
        case .window: return "窗"
        case .door: return "门"
        case .furniture: return "家具"
        case .fan: return "风扇"
        }
    }
}

/// 二维户型编辑器。所有编辑经由 FXJCore.RoomEditor（可撤销、可测试）。
struct FloorplanEditorView: View {
    @State private var editor: RoomEditor
    @State private var viewport: Viewport?
    @State private var tool: EditorTool = .select
    @State private var furnitureKind: ObstacleKind = .sofa
    @State private var message: String?
    @State private var panStart: Viewport?
    @State private var zoomStart: Viewport?
    @State private var dragHistoryMark = false
    let onSave: (Room) -> Void
    @SwiftUI.Environment(\.dismiss) private var dismiss

    init(room: Room, onSave: @escaping (Room) -> Void) {
        _editor = State(initialValue: RoomEditor(room: room))
        self.onSave = onSave
    }

    var body: some View {
        GeometryReader { geo in
            let vp = viewport ?? Viewport.fitting(editor.room.polygon, width: geo.size.width, height: geo.size.height)
            ZStack {
                Color(uiColor: .secondarySystemBackground)
                Canvas { ctx, _ in draw(ctx, vp) }
                    .contentShape(Rectangle())
                    .gesture(SpatialTapGesture().onEnded { tap(Vec2($0.location), vp) })
                    .simultaneousGesture(panGesture(vp))
                    .simultaneousGesture(zoomGesture(vp, size: geo.size))
                vertexHandles(vp)
                obstacleHandles(vp)
            }
            .clipped()
            .onAppear { if viewport == nil { viewport = vp } }
        }
        .overlay(alignment: .bottom) {
            if let m = message {
                Text(m).font(.footnote).padding(8).background(.thinMaterial, in: Capsule()).padding(.bottom, 8)
            }
        }
        .safeAreaInset(edge: .bottom) { toolbar }
        .navigationTitle("编辑户型")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarLeading) {
                Button { editor.undo() } label: { Image(systemName: "arrow.uturn.backward") }
                    .disabled(!editor.canUndo).accessibilityLabel("撤销")
                Button { editor.redo() } label: { Image(systemName: "arrow.uturn.forward") }
                    .disabled(!editor.canRedo).accessibilityLabel("重做")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu("模板") {
                    Button("矩形 4×3 m") { editor.apply(.defaultRectangle); viewport = nil }
                    Button("L 形 5×4 m") { editor.apply(.defaultLShape); viewport = nil }
                }
                Button("保存") { onSave(editor.room); dismiss() }
            }
        }
    }

    private var toolbar: some View {
        VStack(spacing: 6) {
            Picker("工具", selection: $tool) {
                ForEach(EditorTool.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            if tool == .furniture {
                Picker("家具类型", selection: $furnitureKind) {
                    ForEach(ObstacleKind.allCases, id: \.self) { Text(furnitureName($0)).tag($0) }
                }
                .pickerStyle(.menu)
            }
            Text(hint).font(.caption).foregroundStyle(.secondary)
        }
        .padding()
        .background(.bar)
    }

    private var hint: String {
        switch tool {
        case .select: return "拖动顶点修改轮廓，拖动家具移动；单指拖动空白处平移，双指缩放"
        case .window, .door: return "点击墙体附近放置，自动吸附到最近墙段"
        case .furniture: return "点击房间内放置家具"
        case .fan: return "点击房间内放置风扇"
        }
    }

    private func furnitureName(_ k: ObstacleKind) -> String {
        switch k {
        case .bed: return "床"
        case .sofa: return "沙发"
        case .table: return "桌子"
        case .wardrobe: return "衣柜"
        case .desk: return "书桌"
        case .other: return "其他"
        }
    }

    private func run(_ action: () throws -> Void) {
        do { try action(); message = nil }
        catch let e as EditError { message = describe(e) }
        catch { message = error.localizedDescription }
    }

    private func describe(_ e: EditError) -> String {
        switch e {
        case .invalidGeometry: return "该修改会导致墙体交叉或门窗越出墙面，已撤回"
        case .outsideRoom: return "请放在房间内部"
        case .noWallNearby: return "附近没有墙，请点击靠近墙的位置"
        case .wallTooShort: return "这面墙太短，放不下"
        case .overlapsOpening: return "与已有门窗重叠"
        case .notFound: return "对象不存在"
        }
    }

    private func tap(_ screen: Vec2, _ vp: Viewport) {
        let w = vp.toWorld(screen)
        switch tool {
        case .select: break
        case .window: run { try editor.addOpening(.window, near: w) }
        case .door: run { try editor.addOpening(.door, near: w) }
        case .furniture: run { try editor.addFurniture(furnitureKind, at: w) }
        case .fan: run { try editor.addFan(at: w) }
        }
    }

    private func panGesture(_ vp: Viewport) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { v in
                guard tool == .select else { return }
                let start = panStart ?? vp
                if panStart == nil { panStart = vp }
                var n = start
                n.pan(by: Vec2(v.translation.width, v.translation.height))
                viewport = n
            }
            .onEnded { _ in panStart = nil }
    }

    private func zoomGesture(_ vp: Viewport, size: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { v in
                let start = zoomStart ?? vp
                if zoomStart == nil { zoomStart = vp }
                var n = start
                n.zoom(by: v.magnification, anchor: Vec2(v.startLocation))
                viewport = n
            }
            .onEnded { _ in zoomStart = nil }
    }

    private func vertexHandles(_ vp: Viewport) -> some View {
        ForEach(Array(editor.room.polygon.enumerated()), id: \.offset) { i, p in
            Circle()
                .fill(.white).overlay(Circle().stroke(.blue, lineWidth: 2))
                .frame(width: 22, height: 22)
                .frame(width: 44, height: 44).contentShape(Rectangle())
                .position(vp.toScreen(p).cg)
                .accessibilityLabel("顶点 \(i + 1)")
                .gesture(DragGesture().onEnded { v in
                    guard tool == .select else { return }
                    run { try editor.moveVertex(i, to: vp.toWorld(Vec2(v.location))) }
                })
        }
    }

    private func obstacleHandles(_ vp: Viewport) -> some View {
        ForEach(editor.room.obstacles) { ob in
            RoundedRectangle(cornerRadius: 4)
                .fill(.gray.opacity(0.35))
                .overlay(Text(furnitureName(ob.kind)).font(.caption2))
                .frame(width: max(ob.size.x * vp.scale, 24), height: max(ob.size.y * vp.scale, 24))
                .position(vp.toScreen(ob.center).cg)
                .gesture(DragGesture().onEnded { v in
                    guard tool == .select else { return }
                    run { try editor.moveFurniture(ob.id, to: vp.toWorld(Vec2(v.location))) }
                })
                .contextMenu { Button("删除", role: .destructive) { run { try editor.removeItem(ob.id) } } }
        }
    }

    private func draw(_ ctx: GraphicsContext, _ vp: Viewport) {
        // 1 m 网格
        var grid = Path()
        for i in -10...20 {
            let d = Double(i)
            grid.move(to: vp.toScreen(Vec2(d, -10)).cg); grid.addLine(to: vp.toScreen(Vec2(d, 20)).cg)
            grid.move(to: vp.toScreen(Vec2(-10, d)).cg); grid.addLine(to: vp.toScreen(Vec2(20, d)).cg)
        }
        ctx.stroke(grid, with: .color(.gray.opacity(0.15)), lineWidth: 0.5)

        let poly = editor.room.polygon
        var path = Path()
        if let f = poly.first {
            path.move(to: vp.toScreen(f).cg)
            poly.dropFirst().forEach { path.addLine(to: vp.toScreen($0).cg) }
            path.closeSubpath()
        }
        ctx.fill(path, with: .color(.blue.opacity(0.06)))
        ctx.stroke(path, with: .color(.primary), lineWidth: 4)

        for w in editor.room.walls {
            let mid = vp.toScreen(w.point(atOffset: w.length / 2)).cg
            ctx.draw(Text(String(format: "%.2f m", w.length)).font(.caption2).foregroundColor(.secondary), at: mid)
        }
        for o in editor.room.openings {
            guard let (a, b) = editor.room.segment(of: o) else { continue }
            var p = Path(); p.move(to: vp.toScreen(a).cg); p.addLine(to: vp.toScreen(b).cg)
            ctx.stroke(p, with: .color(o.kind == .window ? .cyan : .orange), lineWidth: 8)
        }
        for f in editor.room.fans {
            let c = vp.toScreen(f.position).cg
            ctx.draw(Image(systemName: "fan.fill"), at: c)
        }
    }
}
