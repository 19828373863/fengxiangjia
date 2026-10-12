package app.fengxiangjia.core.persistence

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 导出（用户主动触发，经系统分享）。默认去除位置与反馈备注。 */
object DocumentExport {
    data class Options(val includeLocation: Boolean = false, val includeFeedbackNotes: Boolean = false)

    fun redacted(doc: HomeDocument, options: Options = Options()): HomeDocument {
        var d = doc
        if (!options.includeLocation) d = d.copy(home = d.home.copy(location = null))
        if (!options.includeFeedbackNotes) d = d.copy(feedback = d.feedback.map { it.copy(note = null) })
        return d
    }

    fun json(doc: HomeDocument, options: Options = Options()): String = DocumentCodec.encode(redacted(doc, options))

    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC)
    fun fileName(@Suppress("UNUSED_PARAMETER") doc: HomeDocument, date: Instant = Instant.now()) = "fengxiangjia-${fmt.format(date)}.json"
}
