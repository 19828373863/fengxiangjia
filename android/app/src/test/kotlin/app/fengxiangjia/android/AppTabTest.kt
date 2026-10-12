package app.fengxiangjia.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTabTest {
    @Test fun tabsMatchIos() = assertEquals(listOf("我家", "试摆", "方案"), AppTab.entries.map { it.title })
}
