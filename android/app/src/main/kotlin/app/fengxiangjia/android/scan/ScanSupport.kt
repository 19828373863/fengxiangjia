package app.fengxiangjia.android.scan

import android.content.Context
import com.google.ar.core.ArCoreApk

/** 扫描可用性（UI 据此决定显示“扫描”还是直接引导手绘）。 */
enum class ScanAvailability { SUPPORTED, NEEDS_INSTALL, CHECKING, UNSUPPORTED }

object ScanSupport {
    fun check(context: Context): ScanAvailability = try {
        val a = ArCoreApk.getInstance().checkAvailability(context)
        when {
            a.isTransient -> ScanAvailability.CHECKING
            a == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ScanAvailability.SUPPORTED
            a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD || a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ScanAvailability.NEEDS_INSTALL
            else -> ScanAvailability.UNSUPPORTED
        }
    } catch (t: Throwable) { ScanAvailability.UNSUPPORTED }

    fun message(a: ScanAvailability) = when (a) {
        ScanAvailability.SUPPORTED -> null
        ScanAvailability.NEEDS_INSTALL -> "需要安装或更新「Google Play 服务（AR）」才能扫描"
        ScanAvailability.CHECKING -> "正在检查设备是否支持 AR，请稍后再试"
        ScanAvailability.UNSUPPORTED -> "此设备不支持 ARCore 扫描，请用模板手绘房间"
    }
}
