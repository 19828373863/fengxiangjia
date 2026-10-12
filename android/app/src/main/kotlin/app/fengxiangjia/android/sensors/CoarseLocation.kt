package app.fengxiangjia.android.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager

/** #65 粗略位置：只读取系统最近一次网络定位（ACCESS_COARSE_LOCATION），不持续定位。调用前须已授权。 */
object CoarseLocation {
    @SuppressLint("MissingPermission")
    fun last(ctx: Context): Pair<Double, Double>? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        val l = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter {
                it.latitude.isFinite() && it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
                    now - it.time in 0..MAX_LAST_LOCATION_AGE_MS
            }.maxByOrNull { it.time } ?: return null
        return l.latitude to l.longitude
    }

    private const val MAX_LAST_LOCATION_AGE_MS = 24 * 60 * 60 * 1000L
}
