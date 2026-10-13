package app.fengxiangjia.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
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
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private lateinit var services: Services
    private var scanStatus by mutableStateOf<ScanAvailability?>(null)
    private var scanFailure by mutableStateOf<String?>(null)
    private var scanCheckJob: Job? = null
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
        } else data?.getStringExtra(ScanActivity.EXTRA_MESSAGE)?.let { scanFailure = it; scanStatus = ScanAvailability.UNKNOWN }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = AppModel(HomeRepository(filesDir), fullExperience = BuildConfig.DEBUG)
        services = Services(this, PlayStoreClient(this) { this })
        model.setEntitlement(services.entitlements.state)
        val actions = SuggestActions(requestLocation = ::requestLocation, services = services)
        setContent { FengXiangTheme {
            val motionEnabled = rememberSystemMotionEnabled()
            CompositionLocalProvider(LocalFxjMotionEnabled provides motionEnabled) {
                RootTabs(model, onShare = ::share, onScan = ::openScan, actions = actions, onShareImage = ::shareImage)
                scanStatus?.let { status ->
                    AlertDialog(onDismissRequest = ::dismissScanPreparation,
                        title = { Text(if(status == ScanAvailability.CHECKING) "正在检查扫描能力" else "摄像头扫描") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if(status == ScanAvailability.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(scanFailure ?: ScanSupport.message(status).orEmpty())
                            Text("实时扫描会连续读取画面，请缓慢走动并标记地面墙角，完成后核对尺寸。普通照片或视频暂不能直接生成房间。", style = MaterialTheme.typography.bodySmall)
                        } },
                        confirmButton = {
                            if(status == ScanAvailability.NEEDS_INSTALL) TextButton(onClick = { dismissScanPreparation(); scan.launch(Intent(this@MainActivity, ScanActivity::class.java)) }) { Text("安装服务并继续") }
                            else if(status != ScanAvailability.CHECKING) TextButton(onClick = { dismissScanPreparation(); openScan() }) { Text("重新检查") }
                        },
                        dismissButton = { TextButton(onClick = ::dismissScanPreparation) { Text(if(status == ScanAvailability.CHECKING) "取消" else "返回手动创建") } })
                }
            }
        } }
    }

    override fun onDestroy() {
        if (::services.isInitialized) services.close()
        super.onDestroy()
    }

    override fun onStop() {
        if(scanStatus == ScanAvailability.CHECKING) dismissScanPreparation()
        super.onStop()
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
    private fun dismissScanPreparation() { scanCheckJob?.cancel(); scanCheckJob = null; scanStatus = null }

    private fun openScan() {
        model.paywall(PaidFeature.roomScan)?.let { model.reportMessage(it); return }
        model.paywall(PaidFeature.multiRoom)?.let { model.reportMessage(it); return }
        if(scanStatus == ScanAvailability.CHECKING) return
        scanFailure = null
        scanStatus = ScanAvailability.CHECKING
        scanCheckJob = lifecycleScope.launch {
            val availability = ScanSupport.awaitAvailability(this@MainActivity)
            // Closing the preparation dialog cancels this launch, even if the platform returns later.
            if(scanStatus != ScanAvailability.CHECKING) return@launch
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { scanStatus = null; return@launch }
            if(availability == ScanAvailability.SUPPORTED) {
                scanStatus = null
                scan.launch(Intent(this@MainActivity, ScanActivity::class.java))
            } else scanStatus = availability
        }
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
    val motionEnabled = LocalFxjMotionEnabled.current
    val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
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
                AnimatedContent(targetState = tab, modifier = Modifier.weight(1f).fillMaxHeight(),
                    transitionSpec = {
                        if(!motionEnabled) EnterTransition.None togetherWith ExitTransition.None
                        else {
                            val direction = if(targetState > initialState) 1 else -1
                            (fadeIn(tween(FxjMotion.Standard)) + slideInHorizontally(tween(FxjMotion.Standard, easing = FxjMotion.Easing)) { direction * distance }) togetherWith
                                (fadeOut(tween(FxjMotion.Quick)) + slideOutHorizontally(tween(FxjMotion.Exit, easing = FxjMotion.Easing)) { -direction * distance })
                        }
                    }, label = "destination transition") { screenTab ->
                    val active = screenTab == tab
                    CompositionLocalProvider(LocalFxjMotionEnabled provides (motionEnabled && active)) {
                    Box(Modifier.fillMaxSize().inactiveMotionContent(!active)) {
                    screenState.SaveableStateProvider(AppTab.entries[screenTab].name) {
                        when (AppTab.entries[screenTab]) {
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
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
