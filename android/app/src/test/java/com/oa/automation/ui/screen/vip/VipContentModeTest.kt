package com.oa.automation.ui.screen.vip

import com.oa.automation.domain.model.AccountProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class VipContentModeTest {
    @Test
    fun separatesOrdinaryVipAndAdminContent() {
        assertEquals(VipContentMode.NON_VIP, resolveVipContentMode(null))
        assertEquals(VipContentMode.NON_VIP, resolveVipContentMode(profile()))
        assertEquals(
            VipContentMode.VIP,
            resolveVipContentMode(profile(vipEnabled = true, constructionLogsUnlocked = true))
        )
        assertEquals(
            VipContentMode.ADMIN,
            resolveVipContentMode(profile(isAdmin = true))
        )
    }

    private fun profile(
        isAdmin: Boolean = false,
        vipEnabled: Boolean = false,
        constructionLogsUnlocked: Boolean = false
    ) = AccountProfile(
        id = "user-id",
        username = "tester",
        role = if (isAdmin) "admin" else "user",
        isAdmin = isAdmin,
        enabled = true,
        vipEnabled = vipEnabled,
        constructionLogsUnlocked = constructionLogsUnlocked,
        createdAt = 0L
    )
}
