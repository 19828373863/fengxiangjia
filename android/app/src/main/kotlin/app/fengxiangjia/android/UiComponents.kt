package app.fengxiangjia.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A small, coherent vector family; no emoji or external font dependency. */
object FxjIcons {
    private fun lineIcon(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    val Home = lineIcon("Home") {
        moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
        moveTo(5f, 9f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 9f)
        moveTo(9f, 21f); lineTo(9f, 14f); lineTo(15f, 14f); lineTo(15f, 21f)
    }
    val Fan = lineIcon("Fan") {
        moveTo(12f, 10f); curveTo(8f, 5f, 13f, 1f, 17f, 5f); curveTo(20f, 8f, 16f, 11f, 14f, 11f)
        moveTo(14f, 12f); curveTo(21f, 12f, 22f, 18f, 17f, 20f); curveTo(13f, 22f, 11f, 17f, 11f, 14f)
        moveTo(10f, 13f); curveTo(7f, 19f, 1f, 17f, 3f, 11f); curveTo(4f, 7f, 9f, 8f, 11f, 10f)
        moveTo(12f, 10f); curveTo(14.7f, 10f, 14.7f, 14f, 12f, 14f); curveTo(9.3f, 14f, 9.3f, 10f, 12f, 10f)
    }
    val Plans = lineIcon("Plans") {
        moveTo(8f, 3f); lineTo(20f, 3f); lineTo(20f, 18f); lineTo(8f, 18f); close()
        moveTo(4f, 6f); lineTo(4f, 21f); lineTo(16f, 21f)
        moveTo(11f, 7f); lineTo(17f, 7f); moveTo(11f, 11f); lineTo(17f, 11f)
        moveTo(11f, 15f); lineTo(14f, 15f)
    }
    val Suggestion = lineIcon("Suggestion") {
        moveTo(8f, 16f); curveTo(8f, 13f, 5f, 12f, 5f, 8f)
        curveTo(5f, 0f, 19f, 0f, 19f, 8f); curveTo(19f, 12f, 16f, 13f, 16f, 16f)
        lineTo(8f, 16f); moveTo(8f, 19f); lineTo(16f, 19f)
        moveTo(10f, 22f); lineTo(14f, 22f); moveTo(12f, 16f); lineTo(12f, 10f)
    }
    val Add = lineIcon("Add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Arrow = lineIcon("Arrow") { moveTo(4f, 12f); lineTo(20f, 12f); moveTo(14f, 6f); lineTo(20f, 12f); lineTo(14f, 18f) }
    val More = lineIcon("More") {
        moveTo(5f, 12f); lineTo(5.1f, 12f); moveTo(12f, 12f); lineTo(12.1f, 12f); moveTo(19f, 12f); lineTo(19.1f, 12f)
    }
    val Close = lineIcon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Delete = lineIcon("Delete") {
        moveTo(4f, 6f); lineTo(20f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
        moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f)
        moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
    }
    val Scan = lineIcon("Scan") {
        moveTo(3f, 8f); lineTo(3f, 3f); lineTo(8f, 3f); moveTo(16f, 3f); lineTo(21f, 3f); lineTo(21f, 8f)
        moveTo(21f, 16f); lineTo(21f, 21f); lineTo(16f, 21f); moveTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f)
        moveTo(6f, 12f); lineTo(18f, 12f)
    }
}

@Composable
fun ScreenHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SupportingText(text: String, modifier: Modifier = Modifier) = Text(
    text, modifier = modifier, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
