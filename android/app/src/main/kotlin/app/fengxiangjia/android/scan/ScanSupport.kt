package app.fengxiangjia.android.scan

import android.content.Context
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 扫描可用性（UI 据此决定显示“扫描”还是直接引导手绘）。 */
enum class ScanAvailability { SUPPORTED, NEEDS_INSTALL, CHECKING, UNSUPPORTED, UNKNOWN }

object ScanSupport {
    fun check(context: Context): ScanAvailability = try {
        val a = ArCoreApk.getInstance().checkAvailability(context)
        when {
            a.isTransient -> ScanAvailability.CHECKING
            a == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ScanAvailability.SUPPORTED
            a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD || a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ScanAvailability.NEEDS_INSTALL
            a == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ScanAvailability.UNSUPPORTED
            else -> ScanAvailability.UNKNOWN
        }
    } catch (_: Exception) { ScanAvailability.UNKNOWN }

    /** Capability resolution may start asynchronously; a first CHECKING result is not a failed scan. */
    suspend fun awaitAvailability(context: Context): ScanAvailability = withTimeoutOrNull(8_000) {
        var result = check(context)
        while (result == ScanAvailability.CHECKING) {
            delay(300)
            result = check(context)
        }
        result
    } ?: ScanAvailability.UNKNOWN

    fun message(a: ScanAvailability) = when (a) {
        ScanAvailability.SUPPORTED -> null
        ScanAvailability.NEEDS_INSTALL -> "需要安装或更新「Google Play 服务（AR）」才能扫描"
        ScanAvailability.CHECKING -> "正在检查设备是否支持 AR，请稍后再试"
        ScanAvailability.UNSUPPORTED -> "此设备不支持 ARCore 扫描，请用模板手绘房间"
        ScanAvailability.UNKNOWN -> "暂时无法确认这台手机的 AR 扫描能力。请检查网络和 Google Play 服务后重试；现有房间不受影响。"
    }
}
