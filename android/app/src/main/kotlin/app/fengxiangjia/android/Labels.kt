package app.fengxiangjia.android

import app.fengxiangjia.core.domain.*
import app.fengxiangjia.core.recommendation.*
import app.fengxiangjia.core.simulation.Explanation
import app.fengxiangjia.core.simulation.Level

/** 中文文案（解释与建议都来自同一次计算结果）。 */
object Labels {
    fun level(l: Level) = when (l) { Level.none -> "无"; Level.low -> "低"; Level.medium -> "中"; Level.high -> "高" }
    fun fanKind(k: FanKind) = when (k) { FanKind.pedestal -> "落地扇"; FanKind.circulator -> "循环扇"; FanKind.desk -> "台扇"; FanKind.tower -> "塔扇"; FanKind.ceiling -> "吊扇" }
    fun opening(k: OpeningKind) = when (k) { OpeningKind.door -> "门"; OpeningKind.window -> "窗"; OpeningKind.passage -> "通道" }
    fun obstruction(l: ObstructionLevel) = when (l) { ObstructionLevel.open -> "开阔"; ObstructionLevel.partial -> "部分遮挡"; ObstructionLevel.nearBuilding -> "紧邻楼房"; ObstructionLevel.lightWell -> "天井"; ObstructionLevel.unknown -> "未知" }
    fun dimension(k: ComparisonDimension.Kind) = when (k) {
        ComparisonDimension.Kind.targetBreeze -> "目标区域有风"; ComparisonDimension.Kind.directDraft -> "直吹"
        ComparisonDimension.Kind.outdoorExchange -> "对外换气"; ComparisonDimension.Kind.circulation -> "室内循环"
        ComparisonDimension.Kind.adjustmentCost -> "调整成本"
    }
    fun pref(p: Preference) = when (p) { Preference.a -> "A 更好"; Preference.b -> "B 更好"; Preference.similar -> "相近" }

    fun explanation(e: Explanation, room: Room?): String {
        fun fan(id: String) = room?.fans?.indexOfFirst { it.id == id }?.let { if (it >= 0) "风扇 ${it + 1}" else "风扇" } ?: "风扇"
        fun target(id: String) = room?.targets?.firstOrNull { it.id == id }?.name ?: "目标区域"
        return when (e) {
            Explanation.ClosedRoomInternalOnly -> "房间门窗全关：风扇只让室内空气流动，不会增加对外换气"
            Explanation.SingleSidedOpening -> "只有一侧开口，换气有限"
            Explanation.CrossVentilation -> "形成穿堂风"
            Explanation.NoEnvironment -> "未设置室外风况，只按风扇计算"
            Explanation.StaleEnvironment -> "天气数据已过期"
            is Explanation.FanBlocked -> "${fan(e.fanId)} 出风口被挡住"
            is Explanation.TargetCovered -> "「${target(e.targetId)}」能吹到微风"
            is Explanation.TargetMissed -> "「${target(e.targetId)}」风不够，可调整风扇朝向"
            is Explanation.TargetDraft -> "「${target(e.targetId)}」被直吹"
            is Explanation.FanAidsExhaust -> "${fan(e.fanId)} 朝向开口，有助排风"
            is Explanation.ObstructionReduced -> "窗外${obstruction(e.level)}，进风减弱"
        }
    }

    fun suggestion(s: Suggestion) = when (s) {
        is Suggestion.PreferForBreeze -> "想让目标区域有风：选 ${s.side.name.uppercase()}"
        is Suggestion.ReduceDraft -> "想减少直吹：选 ${s.side.name.uppercase()}"
        Suggestion.OpenOppositeWindow -> "可尝试打开对侧窗户形成穿堂风"
        is Suggestion.SimilarChooseCheaper -> "两方案效果相近，选调整更少的 ${s.side.name.uppercase()}"
        Suggestion.AimFanAtTarget -> "两方案目标区域风都偏弱，试试让风扇对准目标"
    }

    fun note(n: ComparisonNote) = when (n) {
        ComparisonNote.EnvironmentUnified -> "两方案保存时的天气不同，已统一按当前天气比较"
        is ComparisonNote.GeometryChanged -> "方案 ${n.side.name.uppercase()} 保存后户型已改，按当前户型重算"
        ComparisonNote.ClosedRoomNoExchange -> "封闭房间：对外换气不因风扇改变"
        is ComparisonNote.Invalid -> "方案 ${n.side.name.uppercase()} 无法计算（户型无效）"
    }
}
