package com.azurpilot.ghio.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证热更后的当前版本和 AP 相同时跳过整包更新的规则。
 *
 * Verifies current versions after hot updates and skips full updates when AP commits match.
 */
class RuntimeUpdateCheckTest {
    private val oldCommit = "65b5c271b01d" + "a".repeat(28)
    private val currentCommit = "93844113db12" + "b".repeat(28)
    private val oldVersion = "${oldCommit.take(12)}-a283a2161a"
    private val latestVersion = "${currentCommit.take(12)}-77bde9b306"

    @Test
    fun `same AP commit ignores changed runtime build inputs from device logs`() {
        val installed = "93844113db12-a283a2161a"
        assertFalse(runtimeUpdateNeeded(installed, latestVersion))
        val result = RuntimeUpdateCheck(
            checked = true, installedVersion = installed, latestVersion = latestVersion,
        )
        assertFalse(result.available)
        assertFalse(result.commitOnly)
    }

    @Test
    fun `hot update uses current manifest commit with original deployed inputs`() {
        val current = runtimeVersionWithSource(oldVersion, oldVersion, currentCommit)
        assertEquals("93844113db12-a283a2161a", current)
        assertFalse(runtimeUpdateNeeded(current, latestVersion))
    }

    @Test
    fun `settled hot update clears cached update without replacing queried release`() {
        val before = RuntimeUpdateCheck(
            checked = true, installedVersion = oldVersion, latestVersion = latestVersion,
        )
        assertTrue(before.available)
        val after = before.copy(
            installedVersion = runtimeVersionWithSource(oldVersion, oldVersion, currentCommit),
        )
        assertEquals(latestVersion, after.latestVersion)
        assertFalse(after.available)
        assertFalse(after.commitOnly)
    }

    @Test
    fun `different AP commit with same inputs can use hot update`() {
        val result = RuntimeUpdateCheck(
            checked = true, installedVersion = oldVersion,
            latestVersion = "93844113db12-a283a2161a",
        )
        assertTrue(result.available)
        assertTrue(result.commitOnly)
    }

    @Test
    fun `a published package already deployed cannot downgrade hot updated source`() {
        val result = RuntimeUpdateCheck(
            checked = true,
            deployedVersion = oldVersion,
            installedVersion = runtimeVersionWithSource(oldVersion, oldVersion, currentCommit),
            latestVersion = oldVersion,
        )
        assertFalse(result.available)
        assertFalse(result.commitOnly)
        assertTrue(result.copy(latestVersion = "0".repeat(12) + "-77bde9b306").available)
    }

    @Test
    fun `different AP commit and inputs keeps full update available`() {
        val result = RuntimeUpdateCheck(
            checked = true, installedVersion = oldVersion, latestVersion = latestVersion,
        )
        assertTrue(result.available)
        assertFalse(result.commitOnly)
    }

    @Test
    fun `rollback restores update availability`() {
        val updated = RuntimeUpdateCheck(
            checked = true, installedVersion = "93844113db12-a283a2161a",
            latestVersion = latestVersion,
        )
        assertFalse(updated.available)
        assertTrue(updated.copy(installedVersion = oldVersion).available)
    }

    @Test
    fun `missing runtime still requires initial deployment`() {
        assertNull(runtimeVersionWithSource(null, oldVersion, currentCommit))
        assertTrue(runtimeUpdateNeeded(null, latestVersion))
    }

    @Test
    fun `invalid source or mismatched deployment retains original version`() {
        listOf(null, "", "null", "not-a-commit", currentCommit.take(12)).forEach { commit ->
            assertEquals(oldVersion, runtimeVersionWithSource(oldVersion, oldVersion, commit))
        }
        assertEquals(oldVersion, runtimeVersionWithSource(oldVersion, latestVersion, currentCommit))
    }

    @Test
    fun `legacy versions keep complete version comparison`() {
        assertEquals("legacy-image", runtimeVersionWithSource("legacy-image", "legacy-image", currentCommit))
        assertFalse(runtimeUpdateNeeded("legacy-image", "legacy-image"))
        assertTrue(runtimeUpdateNeeded("legacy-image", "legacy-image-next"))
        assertTrue(runtimeUpdateNeeded("legacy-image", latestVersion))
    }

    @Test
    fun `unchecked failed or missing release does not show update`() {
        val pending = RuntimeUpdateCheck(installedVersion = oldVersion, latestVersion = latestVersion)
        assertFalse(pending.available)
        assertFalse(pending.copy(checked = true, error = "network failed").available)
        assertFalse(pending.copy(checked = true, latestVersion = null).available)
        assertFalse(runtimeUpdateNeeded(oldVersion, null))
    }
}
