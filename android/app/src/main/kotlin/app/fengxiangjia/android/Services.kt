package app.fengxiangjia.android

import android.content.Context
import app.fengxiangjia.core.store.*
import app.fengxiangjia.core.weather.*
import java.time.Instant

/** 天气来源：演示数据或 Open-Meteo（暂定，见 #51）。 */
enum class WeatherSourceChoice(val title: String) { MOCK("演示数据"), OPEN_METEO("Open-Meteo（联网）") }

/**
 * 连接 core 服务与 Android 存储（SharedPreferences）。权益缓存、天气缓存、来源选择都只存本机。
 */
class Services(ctx: Context, val store: StoreClient) {
    private val prefs = ctx.getSharedPreferences("fxj", Context.MODE_PRIVATE)
    var weatherSource: WeatherSourceChoice = runCatching { WeatherSourceChoice.valueOf(prefs.getString("weatherSource", null)!!) }.getOrDefault(WeatherSourceChoice.MOCK)
        set(v) { field = v; prefs.edit().putString("weatherSource", v.name).apply(); service = makeService() }
    private var service = makeService()
    val entitlements = EntitlementManager(store, loadEntitlement())

    private fun makeService(): WeatherService {
        val p: WeatherProvider = if (weatherSource == WeatherSourceChoice.OPEN_METEO) OpenMeteoProvider() else MockWeatherProvider()
        return WeatherService(p) // 缓存只在内存中（重启后重新获取）
    }

    suspend fun weather(model: AppModel, force: Boolean): WeatherOutcome {
        // 遮挡由 AppModel.simulationInput() 统一叠加，这里不重复
        return service.environment(model.home.location, Instant.now(), emptyMap(), force)
    }

    private fun loadEntitlement() = EntitlementCache(prefs.getBoolean("unlocked", false), prefs.getLong("verifiedAt", -1).takeIf { it >= 0 })
    fun saveEntitlement(s: EntitlementState) {
        prefs.edit().putBoolean("unlocked", s.cache.unlocked).apply { s.cache.verifiedAtEpochSeconds?.let { putLong("verifiedAt", it) } }.apply()
    }
}
