package app.fengxiangjia.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
            runCatching {
                val room = DocumentCodec.json.decodeFromString(Room.serializer(), json)
                val roomsBefore = model.home.rooms.size
                model.importRoom(room)
                if (model.home.rooms.size > roomsBefore && model.selectedRoomId == room.id) {
                    val w = data.getStringArrayExtra(ScanActivity.EXTRA_WARNINGS).orEmpty()
                    if (w.isNotEmpty()) toast("扫描记录有 ${w.size} 条提示，请在「试摆」中核对尺寸")
                }
            }.onFailure { model.reportMessage("扫描房间导入失败，原有房间已保留。请重试或手动创建。") }
        } else data?.getStringExtra(ScanActivity.EXTRA_MESSAGE)?.let(::toast)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = AppModel(HomeRepository(filesDir), fullExperience = BuildConfig.DEBUG)
        services = Services(this, PlayStoreClient(this) { this })
        model.setEntitlement(services.entitlements.state)
        val actions = SuggestActions(requestLocation = ::requestLocation, services = services)
        setContent { FengXiangTheme { RootTabs(model, onShare = ::share, onScan = ::openScan, actions = actions, onShareImage = ::shareImage) } }
    }

    override fun onDestroy() {
        if (::services.isInitialized) services.close()
        super.onDestroy()
    }

    /** 位置按需申请（仅粗略）；已授权则直接读取一次。 */
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) readLocation()
        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    private fun readLocation() {
        val l = CoarseLocation.last(this) ?: return toast("暂时无法获取位置，请手动输入经纬度")
        model.setLocation(l.first, l.second)
    }

    private fun share(name: String, json: String) {
        val i = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, json)
        startActivity(Intent.createChooser(i, "导出户型与方案"))
    }

    private fun shareImage(bitmap: Bitmap) {
        val name = model.room?.name ?: "房间摆法"
        lifecycleScope.launch {
            try { shareRoomImage(this@MainActivity, bitmap, name) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.reportMessage("图片导出未完成，请检查本机存储后重试。当前摆法已保留。") }
        }
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
    var rev by remember(model) { mutableIntStateOf(model.revision) }
    DisposableEffect(model) {
        val unsubscribe = model.observe { rev++ }
        onDispose { unsubscribe() }
    }
    return rev
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootTabs(model: AppModel, onShare: (String, String) -> Unit = { _, _ -> }, onScan: () -> Unit = {}, actions: SuggestActions = SuggestActions(), onShareImage: (Bitmap) -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val rev = rememberModelRevision(model)
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val screenState = rememberSaveableStateHolder()
    BackHandler(enabled = tab != 0) { tab = 0 }
    LaunchedEffect(rev) {
        model.lastError?.let { message ->
            model.clearError()
            scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(message) }
        }
    }
    LaunchedEffect(model) { model.loadMessage?.let { snack.showSnackbar(it) } }
    val icons = listOf(FxjIcons.Home, FxjIcons.Fan, FxjIcons.Plans, FxjIcons.Suggestion)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(FxjIcons.Fan, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                        Text("风向家", style = MaterialTheme.typography.titleLarge)
                    } },
                    actions = {
                        if(model.lastSaveSucceeded) Text("本机保存", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 20.dp))
                        else TextButton(onClick={ model.retrySave() }) {
                            Text("重试保存",color=MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (compact) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                    AppTab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icons[i], contentDescription = null) }, label = { Text(t.title) })
                    }
                }
            },
        ) { pad ->
            Row(Modifier.fillMaxSize().padding(pad)) {
                if (!compact) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Spacer(Modifier.height(20.dp))
                    AppTab.entries.forEachIndexed { i, t ->
                        NavigationRailItem(selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icons[i], null) }, label = { Text(t.title) })
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    screenState.SaveableStateProvider(AppTab.entries[tab].name) {
                        when (AppTab.entries[tab]) {
                            AppTab.HOME -> HomeScreen(model, rev, onShare, onScan, onOpenRoom = { tab = 1 })
                            AppTab.TRIAL -> key(model.selectedRoomId) { TrialScreen(model, rev, onShareImage = onShareImage) }
                            AppTab.PLANS -> PlansScreen(model, rev, onApplied = { tab = 1 })
                            AppTab.SUGGEST -> key(model.selectedRoomId) { SuggestScreen(model, rev, actions, onApplied = { tab = 1 }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
