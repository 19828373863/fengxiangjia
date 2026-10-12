package app.fengxiangjia.core.weather

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.geometry.swiftRounded
import app.fengxiangjia.core.home.Bearing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** #15 逐小时天气（与 iOS `Weather.swift` 同构）。区域天气 ≠ 窗边实测，界面需注明来源与时间。 */
@Serializable
data class HourlyWeather(@Serializable(Iso8601Serializer::class) val date: Instant, val windFromDegrees: Double, val windSpeed: Double,
                         val temperature: Double? = null, val precipitation: Double? = null)

@Serializable data class WeatherAttribution(val serviceName: String, val legalPageURL: String? = null)

@Serializable
data class WeatherForecast(val location: Location, @Serializable(Iso8601Serializer::class) val fetchedAt: Instant, val hours: List<HourlyWeather>,
                           val source: EnvironmentSource, val isDemo: Boolean, val attribution: WeatherAttribution? = null) {
    /** 最接近 `date` 的小时（±1 h 以外返回 null）。 */
    fun hour(at: Instant): HourlyWeather? {
        val h = hours.minByOrNull { abs(it.date.epochSecond - at.epochSecond) } ?: return null
        return if (abs(h.date.epochSecond - at.epochSecond) <= 3600) h else null
    }
}

sealed class WeatherError(msg: String) : Exception(msg) {
    data object NoLocation : WeatherError("noLocation") { private fun readResolve(): Any = NoLocation }
    data object Offline : WeatherError("offline") { private fun readResolve(): Any = Offline }
    data object NotAuthorized : WeatherError("notAuthorized") { private fun readResolve(): Any = NotAuthorized }
    data class Unavailable(val reason: String) : WeatherError("unavailable($reason)")
    val userMessage get() = when (this) {
        NoLocation -> "未设置住宅位置，请手动设置风况"; Offline -> "网络不可用，已改用缓存或手动风况"
        NotAuthorized -> "天气服务未授权"; is Unavailable -> "天气暂不可用"
    }
}

interface WeatherProvider { suspend fun forecast(location: Location, now: Instant): WeatherForecast }

/** 演示天气：与 iOS `MockWeatherProvider` 逐值一致（确定性），明确标为演示数据。 */
class MockWeatherProvider(private val failure: WeatherError? = null) : WeatherProvider {
    override suspend fun forecast(location: Location, now: Instant): WeatherForecast {
        failure?.let { throw it }
        val start = floor(now.epochSecond / 3600.0) * 3600
        val seed = (location.latitude * 7 + location.longitude * 3).rem(360.0)
        val hours = (0 until 24).map { i ->
            val t = start + i * 3600.0
            val phase = (t / 3600).rem(24.0) / 24 * 2 * Math.PI
            HourlyWeather(Instant.ofEpochSecond(t.toLong()), Bearing.normalize(seed + 40 * sin(phase)),
                swiftRounded((2.5 + 1.5 * sin(phase + 1)) * 10) / 10, swiftRounded((28 + 4 * sin(phase - 2)) * 10) / 10,
                if (i % 9 == 0) 0.5 else 0.0)
        }
        return WeatherForecast(location, now, hours, EnvironmentSource.assumed, true, WeatherAttribution("演示数据（非真实天气）"))
    }
}

/**
 * Open-Meteo 适配（**暂定**，见 #51）：免密钥 HTTP JSON。数据许可 CC BY 4.0，须显示归属；
 * 其免费 API 仅限非商业用途，付费 App 上线前需购买商用订阅或改用其他服务。
 * `fetch` 可注入以便测试；默认使用 HttpURLConnection。仅发送粗化到 0.01° 的位置。
 */
class OpenMeteoProvider(private val fetch: (String) -> String = ::httpGet, private val baseUrl: String = "https://api.open-meteo.com/v1/forecast") : WeatherProvider {
    fun url(l: Location): String {
        val c = l.coarsenedLocation()
        return "$baseUrl?latitude=%.2f&longitude=%.2f&hourly=temperature_2m,precipitation,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms&timeformat=unixtime&forecast_days=2&timezone=GMT"
            .format(java.util.Locale.ROOT, c.latitude, c.longitude)
    }

    override suspend fun forecast(location: Location, now: Instant): WeatherForecast {
        val body = try { fetch(url(location)) } catch (e: java.io.IOException) { throw WeatherError.Offline }
        return parse(body, location.coarsenedLocation(), now)
    }

    fun parse(body: String, location: Location, now: Instant): WeatherForecast {
        val root = try { Json.parseToJsonElement(body).jsonObject } catch (e: Exception) { throw WeatherError.Unavailable("bad json") }
        root["error"]?.let { if (it.jsonPrimitive.booleanOrNull == true) throw WeatherError.Unavailable(root["reason"]?.jsonPrimitive?.content ?: "error") }
        val h = root["hourly"]?.jsonObject ?: throw WeatherError.Unavailable("no hourly")
        fun col(k: String) = h[k]?.jsonArray?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: throw WeatherError.Unavailable("missing $k")
        val t = col("time"); val sp = col("wind_speed_10m"); val dir = col("wind_direction_10m")
        val temp = h["temperature_2m"]?.jsonArray?.map { (it as? JsonPrimitive)?.doubleOrNull }
        val rain = h["precipitation"]?.jsonArray?.map { (it as? JsonPrimitive)?.doubleOrNull }
        val hours = t.indices.mapNotNull { i ->
            val ts = t[i] ?: return@mapNotNull null; val s = sp.getOrNull(i) ?: return@mapNotNull null; val d = dir.getOrNull(i) ?: return@mapNotNull null
            HourlyWeather(Instant.ofEpochSecond(ts.toLong()), Bearing.normalize(d), s, temp?.getOrNull(i), rain?.getOrNull(i))
        }.sortedBy { it.date }
        if (hours.isEmpty()) throw WeatherError.Unavailable("empty")
        return WeatherForecast(location, now, hours, EnvironmentSource.assumed, false,
            WeatherAttribution("Open-Meteo.com（CC BY 4.0）", "https://open-meteo.com/en/terms"))
    }

    companion object {
        fun httpGet(url: String): String {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            try {
                if (c.responseCode !in 200..299) throw WeatherError.Unavailable("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().readText()
            } finally { c.disconnect() }
        }
    }
}

/** 隐私：仅保留约 1 km 精度（0.01°）。 */
fun Location.coarsenedLocation(step: Double = 0.01) = Location(swiftRounded(latitude / step) * step, swiftRounded(longitude / step) * step, label)

enum class WeatherFreshness { fresh, stale, expired }

@Serializable
data class WeatherCache(val entries: Map<String, WeatherForecast> = emptyMap()) {
    fun store(f: WeatherForecast) = copy(entries = entries + (key(f.location) to f))
    fun forecast(l: Location) = entries[key(l)]
    companion object {
        const val FRESH_SECONDS = 3600L
        const val STALE_SECONDS = 6 * 3600L
        fun key(l: Location): String { val c = l.coarsenedLocation(); return "%.2f,%.2f".format(java.util.Locale.ROOT, c.latitude, c.longitude) }
        fun freshness(f: WeatherForecast, now: Instant): WeatherFreshness {
            val age = now.epochSecond - f.fetchedAt.epochSecond
            return if (age < FRESH_SECONDS) WeatherFreshness.fresh else if (age < STALE_SECONDS) WeatherFreshness.stale else WeatherFreshness.expired
        }
    }
}

data class WeatherOutcome(val environment: Environment?, val forecast: WeatherForecast?, val freshness: WeatherFreshness?, val fromCache: Boolean, val error: WeatherError?)

/** 优先新鲜缓存 → 在线查询 → 回退未过期缓存 → 返回错误（界面改用手动风况）。 */
class WeatherService(private val provider: WeatherProvider, cache: WeatherCache = WeatherCache()) {
    private val mutex = Mutex()
    var cache: WeatherCache = cache; private set

    suspend fun environment(location: Location?, now: Instant, obstruction: Map<EntityID, ObstructionLevel> = emptyMap(), forceRefresh: Boolean = false): WeatherOutcome = mutex.withLock {
        if (location == null) return@withLock WeatherOutcome(null, null, null, false, WeatherError.NoLocation)
        val cached = cache.forecast(location)
        if (!forceRefresh && cached != null && WeatherCache.freshness(cached, now) == WeatherFreshness.fresh) return@withLock outcome(cached, now, true, obstruction, null)
        try {
            val f = provider.forecast(location.coarsenedLocation(), now)
            cache = cache.store(f)
            outcome(f, now, false, obstruction, null)
        } catch (e: Exception) {
            val err = e as? WeatherError ?: WeatherError.Unavailable(e.toString())
            if (cached != null && WeatherCache.freshness(cached, now) != WeatherFreshness.expired) outcome(cached, now, true, obstruction, err)
            else WeatherOutcome(null, null, null, false, err)
        }
    }

    private fun outcome(f: WeatherForecast, now: Instant, fromCache: Boolean, obstruction: Map<EntityID, ObstructionLevel>, error: WeatherError?): WeatherOutcome {
        val env = f.hour(now)?.let { Environment(f.source, it.date, it.windFromDegrees, it.windSpeed, it.temperature, it.precipitation, obstruction) }
        return WeatherOutcome(env, f, WeatherCache.freshness(f, now), fromCache, if (env == null) error ?: WeatherError.Unavailable("超出预报范围") else error)
    }
}
