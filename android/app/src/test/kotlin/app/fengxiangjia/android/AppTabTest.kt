package app.fengxiangjia.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTabTest {
    // 前三个与 iOS 相同；「建议」为 Android 集中放推荐/天气/朝向/购买的页（#65，与 iOS 布局不同）
    @Test fun tabs() = assertEquals(listOf("我家", "试摆", "方案", "建议"), AppTab.entries.map { it.title })
}
