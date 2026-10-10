package com.azurpilot.ghio.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression test for issue #11: clearing app data must not restore an
 * automatically enabled app lock on devices with no usable credentials.
 */
class AppSettingsDefaultsTest {
    @Test
    fun appLockIsOptIn() {
        assertEquals("false", AppSettings().appLockEnabled)
    }
}
