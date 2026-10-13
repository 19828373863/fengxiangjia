package app.fengxiangjia.android.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.opengl.Matrix
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.fengxiangjia.core.domain.Room
import app.fengxiangjia.core.persistence.DocumentCodec
import app.fengxiangjia.core.scan.CaptureSession
import app.fengxiangjia.core.scan.ScannedOpening
import app.fengxiangjia.core.scan.ScannedWall
import com.google.ar.core.*
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import app.fengxiangjia.android.FengXiangTheme
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * #60 ARCore 房间采集（**未经真机验证**）。
 * 流程：检查 ARCore → 申请 CAMERA → 识别地面 → 用户沿墙角逐个点按（打在地面平面上）→ 可在墙面上点按标记门/窗 →
 * 完成后由 core `ScanImport` 生成 Room，返回给主界面进入编辑器校准。识别到的竖直平面作为墙角不足时的后备；
 * 支持时开启 Depth API 提高命中稳定性。
 */
class ScanActivity : ComponentActivity(), GLSurfaceView.Renderer {
    private var session: Session? = null
    private var installRequested = false
    private var cameraRequestPending = false
    private lateinit var surface: GLSurfaceView
    private val bg = BackgroundRenderer()
    private val taps = ConcurrentLinkedQueue<MotionEvent>()

    private var capture by mutableStateOf(CaptureSession())
    private var status by mutableStateOf("正在启动摄像头…")
    private var tapMode by mutableStateOf(TapMode.CORNER)
    private var floorFound by mutableStateOf(false)
    /** 已标墙角投影到屏幕上的像素坐标（null = 在相机后方），用于叠加角点标记。 */
    private var cornerMarkers by mutableStateOf<List<Offset?>>(emptyList())
    private var viewW = 1; private var viewH = 1

    enum class TapMode(val title: String) { CORNER("标墙角"), WINDOW("标窗"), DOOR("标门") }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraRequestPending = false
        if (!ok) finishWithMessage("未授予相机权限，可从扫描入口重试，或手动创建房间")
        else resumeCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true; setEGLContextClientVersion(2); setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ScanActivity); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) { taps.offer(MotionEvent.obtain(e)); v.performClick() }; true }
        }
        val overlay = ComposeView(this).apply { setContent { FengXiangTheme { Overlay() } } }
        setContentView(FrameLayout(this).apply {
            addView(surface, ViewGroup.LayoutParams(-1, -1)); addView(overlay, ViewGroup.LayoutParams(-1, -1))
        })
    }

    override fun onResume() {
        super.onResume()
        resumeCapture()
    }

    /** Install/verify AR first; granting camera permission resumes the same pending capture. */
    private fun resumeCapture() {
        if (isFinishing) return
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> Unit
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    if (!cameraRequestPending) { cameraRequestPending = true; permission.launch(Manifest.permission.CAMERA) }
                    return
                }
                val s = Session(this)
                val cfg = Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                }
                s.configure(cfg)
                capture = capture.copy(usedDepth = cfg.depthMode == Config.DepthMode.AUTOMATIC)
                session = s
            } catch (_: UnavailableUserDeclinedInstallationException) { finishWithMessage("AR 服务安装已取消，可以从扫描入口重试，或手动创建房间"); return }
            catch (_: UnavailableDeviceNotCompatibleException) { finishWithMessage("当前手机系统未通过 AR 扫描兼容检查，请手动创建房间"); return }
            catch (_: UnavailableException) { finishWithMessage("AR 服务暂时不可用，请检查是否安装并更新 Google Play 服务（AR）后重试"); return }
            catch (e: Exception) { finishWithMessage("启动扫描失败：${e.message}"); return }
        }
        try { session?.resume() } catch (e: Exception) { finishWithMessage("相机不可用：${e.message}"); return }
        surface.onResume()
    }

    override fun onPause() { super.onPause(); surface.onPause(); session?.pause() }
    override fun onDestroy() { session?.close(); session = null; super.onDestroy() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { GLES20.glClearColor(0f, 0f, 0f, 1f); bg.create() }
    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h); viewW = w; viewH = h
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        s.setCameraTextureName(bg.textureId)
        val frame = try { s.update() } catch (e: Exception) { return }
        bg.draw(frame)
        if (frame.camera.trackingState != TrackingState.TRACKING) { status = "请缓慢移动手机，让摄像头看清地面和墙"; return }

        val planes = s.getAllTrackables(Plane::class.java).filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
        val floor = planes.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }.minByOrNull { it.centerPose.ty() }
        val walls = planes.filter { it.type == Plane.Type.VERTICAL }
        floorFound = floor != null
        val floorY = floor?.centerPose?.ty()?.toDouble()
        capture = capture.copy(floorY = floorY, wallPlanes = walls.map { p ->
            val c = p.centerPose; val ax = c.getTransformedAxis(0, 1f)
            ScannedWall.fromPlane(c.tx().toDouble(), c.tz().toDouble(), ax[0].toDouble(), ax[2].toDouble(), p.extentX.toDouble(), null)
        })
        status = when {
            floor == null -> "正在识别地面…"
            tapMode == TapMode.CORNER -> "依次点按地面上的墙角（已标 ${capture.cornersXZ.size} 个）"
            else -> "点按墙面上${tapMode.title.drop(1)}的中间位置"
        }

        while (true) {
            val e = taps.poll() ?: break
            val hits = frame.hitTest(e)
            when (tapMode) {
                TapMode.CORNER -> hits.firstOrNull { h -> (h.trackable as? Plane)?.let { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.isPoseInPolygon(h.hitPose) } == true }
                    ?.let { capture = capture.addCorner(it.hitPose.tx().toDouble(), it.hitPose.tz().toDouble()) }
                TapMode.WINDOW, TapMode.DOOR -> hits.firstOrNull { h -> (h.trackable as? Plane)?.type == Plane.Type.VERTICAL || h.trackable is DepthPoint }
                    ?.let { h ->
                        val kind = if (tapMode == TapMode.DOOR) ScannedOpening.Kind.Door(true) else ScannedOpening.Kind.Window
                        val width = if (tapMode == TapMode.DOOR) 0.9 else 1.2
                        capture = capture.copy(openings = capture.openings + ScannedOpening.at(kind, h.hitPose.tx().toDouble(), h.hitPose.tz().toDouble(), width))
                    }
            }
            // 层高估计：墙面上的命中点相对地面的最大高度（粗略，只作参考）
            if (floorY != null) hits.firstOrNull()?.let { h -> val dy = h.hitPose.ty() - floorY; if (dy > 1.8 && dy < 4.0) capture = capture.copy(ceilingHeight = maxOf(capture.ceilingHeight ?: 0.0, dy)) }
            e.recycle()
        }
        cornerMarkers = if (floorY == null) emptyList() else project(frame.camera, capture.cornersXZ.map { floatArrayOf(it.first.toFloat(), floorY.toFloat(), it.second.toFloat(), 1f) })
    }

    /** 世界坐标 → 屏幕像素（GL 视口与 Compose 叠加层同尺寸）。 */
    private fun project(cam: Camera, pts: List<FloatArray>): List<Offset?> {
        val view = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.05f, 50f); Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        return pts.map { p ->
            val o = FloatArray(4); Matrix.multiplyMV(o, 0, vp, 0, p, 0)
            if (o[3] <= 0f) null else Offset((o[0] / o[3] + 1f) / 2f * viewW, (1f - o[1] / o[3]) / 2f * viewH)
        }
    }

    @Composable
    private fun Overlay() {
        Box(Modifier.fillMaxSize()) {
            val markers = cornerMarkers
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "已标墙角 ${markers.size} 个" }) {
                val vis = markers.filterNotNull()
                for (i in 0 until vis.size - 1) drawLine(Color(0xFF4FC3F7), vis[i], vis[i + 1], strokeWidth = 5f)
                if (vis.size >= 3) drawLine(Color(0x804FC3F7), vis.last(), vis.first(), strokeWidth = 3f)
                vis.forEach { drawCircle(Color.White, 16f, it); drawCircle(Color(0xFF0288D1), 16f, it, style = Stroke(5f)) }
            }
            Text("+", color = Color.White, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Center))
            Surface(color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(status, color = Color.White)
                    Text("墙角 ${capture.cornersXZ.size} · 墙面 ${capture.wallPlanes.size} · 门窗 ${capture.openings.size}" + if (capture.usedDepth) " · 深度已启用" else "",
                        color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in TapMode.entries) FilterChip(tapMode == m, { tapMode = m }, label = { Text(m.title) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.semantics { contentDescription = "撤销最后一个墙角" }, onClick = { capture = capture.undoCorner() }, enabled = capture.cornersXZ.isNotEmpty()) { Text("撤销墙角") }
                    OutlinedButton(onClick = { finishWithMessage(null) }) { Text("取消") }
                    Button(onClick = ::finishScan, enabled = capture.cornersXZ.size >= 3 || capture.wallPlanes.size >= 3) { Text("完成") }
                }
                Text("扫描结果只是初稿，完成后请在编辑器里核对尺寸和门窗", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    private fun finishScan() {
        val r = capture.convert() ?: return finishWithMessage("墙体数据不足，未能生成房间，请重试或手绘")
        val json = DocumentCodec.json.encodeToString(Room.serializer(), r.room)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ROOM_JSON, json).putExtra(EXTRA_WARNINGS, r.warnings.map { it.token }.toTypedArray()))
        finish()
    }

    private fun finishWithMessage(msg: String?) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_MESSAGE, msg)); finish()
    }

    companion object {
        const val EXTRA_ROOM_JSON = "room_json"
        const val EXTRA_WARNINGS = "warnings"
        const val EXTRA_MESSAGE = "message"
    }
}

