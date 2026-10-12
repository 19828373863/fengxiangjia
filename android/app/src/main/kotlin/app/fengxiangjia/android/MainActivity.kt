package app.fengxiangjia.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import app.fengxiangjia.android.scan.ScanActivity
import app.fengxiangjia.android.scan.ScanAvailability
import app.fengxiangjia.android.scan.ScanSupport
import app.fengxiangjia.core.domain.Room
import app.fengxiangjia.core.persistence.DocumentCodec
import app.fengxiangjia.core.persistence.HomeRepository
import app.fengxiangjia.core.store.PaidFeature
import app.fengxiangjia.android.store.PlayStoreClient
import app.fengxiangjia.android.sensors.CoarseLocation
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private lateinit var services: Services
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) readLocation() else toast("未授予位置权限，可手动输入经纬度")
    }
    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val json = data?.getStringExtra(ScanActivity.EXTRA_ROOM_JSON)
        if (res.resultCode == RESULT_OK && json != null) {
            val room = DocumentCodec.json.decodeFromString(Room.serializer(), json)
            model.importRoom(room)
            val w = data.getStringArrayExtra(ScanActivity.EXTRA_WARNINGS).orEmpty()
            toast(if (w.isEmpty()) "已导入扫描房间，请在「试摆 › 户型」中核对" else "已导入（有 ${w.size} 条提示），请在编辑器中校准")
        } else data?.getStringExtra(ScanActivity.EXTRA_MESSAGE)?.let(::toast)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel(HomeRepository(filesDir))
        services = Services(this, PlayStoreClient(this) { this })
        model.setEntitlement(services.entitlements.state)
        val actions = SuggestActions(requestLocation = ::requestLocation, services = services)
        setContent { MaterialTheme { RootTabs(model, onShare = ::share, onScan = ::openScan, actions = actions) } }
    }

    /** 位置按需申请（仅粗略）；已授权则直接读取一次。 */
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) readLocation()
        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    private fun readLocation() {
        val l = CoarseLocation.last(this) ?: return toast("暂时无法获取位置，请手动输入经纬度")
        model.setLocation(l.first, l.second); toast("已保存大致位置（约 1 km 精度）")
    }

    private fun share(name: String, json: String) {
        val i = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, json)
        startActivity(Intent.createChooser(i, "导出户型与方案"))
    }

    /** #60：先检查 ARCore 可用性，不支持则提示改用手绘。 */
    private fun openScan() {
        model.paywall(PaidFeature.roomScan)?.let { model.reportMessage(it); return }
        model.paywall(PaidFeature.multiRoom)?.let { model.reportMessage(it); return }
        val a = ScanSupport.check(this)
        if (a == ScanAvailability.SUPPORTED || a == ScanAvailability.NEEDS_INSTALL) scan.launch(Intent(this, ScanActivity::class.java))
        else ScanSupport.message(a)?.let(::toast)
    }
}

enum class AppTab(val title: String) { HOME("我家"), TRIAL("试摆"), PLANS("方案"), SUGGEST("建议") }

/** 让 Compose 在 AppModel 变化时重组。 */
@Composable
fun rememberModelRevision(model: AppModel): Int {
    var rev by remember { mutableIntStateOf(model.revision) }
    DisposableEffect(model) { model.observe { rev++ }; onDispose { } }
    return rev
}

@Composable
fun RootTabs(model: AppModel, onShare: (String, String) -> Unit = { _, _ -> }, onScan: () -> Unit = {}, actions: SuggestActions = SuggestActions()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val rev = rememberModelRevision(model)
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(model.lastError, rev) { model.lastError?.let { snack.showSnackbar(it); model.clearError() } }
    LaunchedEffect(Unit) { model.loadMessage?.let { snack.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
        NavigationBar {
            AppTab.entries.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("🏠", "🌀", "⚖️", "💡")[i], modifier = Modifier.semantics { contentDescription = t.title }) }, label = { Text(t.title) })
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (AppTab.entries[tab]) {
                AppTab.HOME -> key(model.selectedRoomId) { HomeScreen(model, rev, onShare, onScan, onOpenRoom = { tab = 1 }) }
                AppTab.TRIAL -> key(model.selectedRoomId) { TrialScreen(model, rev) }
                AppTab.PLANS -> PlansScreen(model, rev, onApplied = { tab = 1 })
                AppTab.SUGGEST -> key(model.selectedRoomId) { SuggestScreen(model, rev, actions, onApplied = { tab = 1 }) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
