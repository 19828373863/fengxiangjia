package app.fengxiangjia.android

import android.content.Context
import app.fengxiangjia.core.domain.Location
import app.fengxiangjia.core.store.*
import app.fengxiangjia.core.weather.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant

/** 天气来源：演示数据或 Open-Meteo（暂定，见 #51）。 */
enum class WeatherSourceChoice(val title: String) { MOCK("演示风况"), OPEN_METEO("在线天气") }

/**
 * 连接 core 服务与 Android 存储（SharedPreferences）。权益缓存、天气缓存、来源选择都只存本机。
 */
class Services(ctx: Context, val store: StoreClient) {
    private val prefs = ctx.getSharedPreferences("fxj", Context.MODE_PRIVATE)
    var weatherSource: WeatherSourceChoice = runCatching { WeatherSourceChoice.valueOf(prefs.getString("weatherSource", null)!!) }.getOrDefault(WeatherSourceChoice.MOCK)
        set(v) { field = v; prefs.edit().putString("weatherSource", v.name).apply() }
    // Each source owns its cache; switching sources never presents demo data as live weather.
    private val weatherServices = mapOf(
        WeatherSourceChoice.MOCK to WeatherService(MockWeatherProvider()),
        WeatherSourceChoice.OPEN_METEO to WeatherService(OpenMeteoProvider()),
    )
    val entitlements = EntitlementManager(store, loadEntitlement())

    /** Request values are captured on the UI thread before the blocking provider runs. */
    suspend fun weather(location: Location?, source: WeatherSourceChoice, force: Boolean): WeatherOutcome = withContext(Dispatchers.IO) {
        val outcome = weatherServices.getValue(source).environment(location, Instant.now(), emptyMap(), force)
        // WeatherService falls back on provider errors, including cancellation; a cancelled
        // request must still never return to a disposed screen and overwrite newer inputs.
        ensureActive()
        outcome
    }

    suspend fun weather(model: AppModel, force: Boolean): WeatherOutcome {
        return weather(model.home.location, weatherSource, force)
    }

    private fun loadEntitlement() = EntitlementCache(prefs.getBoolean("unlocked", false), prefs.getLong("verifiedAt", -1).takeIf { it >= 0 })
    fun saveEntitlement(s: EntitlementState) {
        prefs.edit().putBoolean("unlocked", s.cache.unlocked).apply {
            s.cache.verifiedAtEpochSeconds?.let { putLong("verifiedAt", it) } ?: remove("verifiedAt")
        }.apply()
    }

    fun close() { (store as? AutoCloseable)?.close() }
}
