package com.odoocompanion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {
    private val digest = "a".repeat(64)

    private fun manifest(
        apk: String = "odoo-companion-7.apk",
        sha256: String = digest,
        size: Long = 10,
        versionName: String = "0.6.0",
    ) = """{"version_code":7,"version_name":"$versionName","apk":"$apk",""" +
        """"sha256":"$sha256","size":$size}"""

    @Test
    fun `the apk is fetched from its own release, which outlives a newer one`() {
        val read = UpdateFeed.parse(manifest())!!

        assertEquals(7L, read.versionCode)
        assertEquals("0.6.0", read.versionName)
        assertEquals(
            "https://github.com/Agro-Marin/odoo-companion/releases/download/v0.6.0/" +
                "odoo-companion-7.apk",
            UpdateFeed.apkUrl(read),
        )
    }

    @Test
    fun `only a higher versionCode is offered`() {
        val read = UpdateFeed.parse(manifest())!!

        assertTrue(UpdateFeed.isNewer(read, installedVersionCode = 6))
        assertFalse(UpdateFeed.isNewer(read, installedVersionCode = 7))
        assertFalse(UpdateFeed.isNewer(read, installedVersionCode = 8))
    }

    @Test
    fun `an asset that is not a bare apk name is refused`() {
        for (apk in listOf("../evil.apk", "sub/odoo.apk", "odoo.zip", "https://x/odoo.apk", "")) {
            assertNull(apk, UpdateFeed.parse(manifest(apk = apk)))
        }
    }

    @Test
    fun `a version name that is not a bare tag segment is refused`() {
        for (name in listOf("../0.6.0", "0.6/0", "..", "0.6.0?x=1", "")) {
            assertNull(name, UpdateFeed.parse(manifest(versionName = name)))
        }
    }

    @Test
    fun `a manifest without a real digest or size is refused`() {
        assertNull(UpdateFeed.parse(manifest(sha256 = "abc")))
        assertNull(UpdateFeed.parse(manifest(sha256 = "A".repeat(64))))
        assertNull(UpdateFeed.parse(manifest(size = 0)))
    }

    @Test
    fun `a page that is not a manifest is no update`() {
        assertNull(UpdateFeed.parse("<html>Not Found</html>"))
        assertNull(UpdateFeed.parse("""{"version_name":"0.6.0"}"""))
    }
}
