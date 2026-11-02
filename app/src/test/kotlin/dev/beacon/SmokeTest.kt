package dev.beacon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies the Robolectric pipeline sees app resources and the Hilt application boot. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SmokeTest {
    @Test
    fun appContextProvidesBrandResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Beacon", context.getString(R.string.app_name))
    }
}