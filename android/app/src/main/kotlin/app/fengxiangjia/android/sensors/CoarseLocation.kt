package app.fengxiangjia.android.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager

/** #65 粗略位置：只读取系统最近一次网络定位（ACCESS_COARSE_LOCATION），不持续定位。调用前须已授权。 */
object CoarseLocation {
    @SuppressLint("MissingPermission")
    fun last(ctx: Context): Pair<Double, Double>? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return l.latitude to l.longitude
    }
}
