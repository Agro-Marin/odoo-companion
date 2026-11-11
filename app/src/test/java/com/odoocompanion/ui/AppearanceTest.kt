package com.odoocompanion.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.CompanionApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = Appearance.choose(app, Appearance.SYSTEM)

    @Test
    fun `a phone follows the system until someone picks`() {
        assertEquals(Appearance.SYSTEM, Appearance.current(app))
    }

    @Test
    fun `the choice applies at once and survives a restart`() {
        Appearance.choose(app, Appearance.DARK)

        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
        assertEquals(Appearance.DARK, Appearance.current(app))

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        Appearance.apply(app)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
    }
}
