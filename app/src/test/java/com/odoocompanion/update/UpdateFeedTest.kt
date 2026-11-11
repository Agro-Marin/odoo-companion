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
        size: Long = 10
    ) = """{"version_code":7,"version_name":"0.6.0","apk":"$apk","sha256":"$sha256","size":$size}"""

    @Test
    fun `a release's manifest names the build and the asset to fetch`() {
        val read = UpdateFeed.parse(manifest())!!

        assertEquals(7L, read.versionCode)
        assertEquals("0.6.0", read.versionName)
        assertEquals(
            "https://github.com/Agro-Marin/odoo-companion/releases/latest/download/" +
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

    // The asset name is joined onto the release's URL: anything but a bare
    // .apk file name could fetch something the release never published.
    @Test
    fun `an asset that is not a bare apk name is refused`() {
        for (apk in listOf("../evil.apk", "sub/odoo.apk", "odoo.zip", "https://x/odoo.apk", "")) {
            assertNull(apk, UpdateFeed.parse(manifest(apk = apk)))
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
