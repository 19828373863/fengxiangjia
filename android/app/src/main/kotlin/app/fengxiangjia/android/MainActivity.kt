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
import app.fengxiangjia.core.persistence.HomeRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = AppModel(HomeRepository(filesDir))
        setContent { MaterialTheme { RootTabs(model, onShare = ::share, onScan = ::openScan) } }
    }

    private fun share(name: String, json: String) {
        val i = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, json)
        startActivity(Intent.createChooser(i, "导出户型与方案"))
    }

    /** 扫描入口由 #60 提供；此处为占位钩子。 */
    @Suppress("unused") private fun openScan() {}
}

enum class AppTab(val title: String) { HOME("我家"), TRIAL("试摆"), PLANS("方案") }

/** 让 Compose 在 AppModel 变化时重组。 */
@Composable
fun rememberModelRevision(model: AppModel): Int {
    var rev by remember { mutableIntStateOf(model.revision) }
    DisposableEffect(model) { model.observe { rev++ }; onDispose { } }
    return rev
}

@Composable
fun RootTabs(model: AppModel, onShare: (String, String) -> Unit = { _, _ -> }, onScan: () -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val rev = rememberModelRevision(model)
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(model.lastError, rev) { model.lastError?.let { snack.showSnackbar(it); model.clearError() } }
    LaunchedEffect(Unit) { model.loadMessage?.let { snack.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
        NavigationBar {
            AppTab.entries.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("🏠", "🌀", "⚖️")[i]) }, label = { Text(t.title) })
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (AppTab.entries[tab]) {
                AppTab.HOME -> key(model.selectedRoomId) { HomeScreen(model, rev, onShare, onScan, onOpenRoom = { tab = 1 }) }
                AppTab.TRIAL -> key(model.selectedRoomId) { TrialScreen(model, rev) }
                AppTab.PLANS -> PlansScreen(model, rev, onApplied = { tab = 1 })
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
