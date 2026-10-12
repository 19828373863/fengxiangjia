package app.fengxiangjia.android.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** 方位角（0 = 北，顺时针，度）的简单低通滤波。纯函数，便于单测。 */
object CompassMath {
    fun normalize(d: Double) = ((d % 360) + 360) % 360
    /** 沿最短弧平滑，避免 359°→0° 跳变。 */
    fun smooth(prev: Double?, next: Double, alpha: Double = 0.15): Double {
        if (prev == null) return normalize(next)
        var diff = normalize(next) - prev
        if (diff > 180) diff -= 360; if (diff < -180) diff += 360
        return normalize(prev + alpha * diff)
    }
}

/**
 * #65 指南针（TYPE_ROTATION_VECTOR，磁北；未做磁偏角修正，未经真机验证）。
 * 返回 null = 设备无此传感器（界面回退到手动输入朝向）。
 */
@Composable
fun rememberCompassHeading(enabled: Boolean): Double? {
    val ctx = LocalContext.current
    var heading by remember { mutableStateOf<Double?>(null) }
    DisposableEffect(enabled) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || s == null) return@DisposableEffect onDispose { }
        val rot = FloatArray(9); val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values); SensorManager.getOrientation(rot, ori)
                heading = CompassMath.smooth(heading, Math.toDegrees(ori[0].toDouble()))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return heading
}

fun hasCompass(ctx: Context) = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
