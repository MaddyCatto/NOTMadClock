package com.madclock.app

import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class LauncherIconTest {
    @Test fun pngMatchesTheReviewedCropOfTheUserArtwork() {
        val bytes = RuntimeEnvironment.getApplication().resources.openRawResource(R.drawable.ic_launcher_artwork).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("c16072358e2732216fcc2e9207f9bb24da62d8ec5158a43b117c66a98e72862a", hash)
    }
    @Test fun mainUsesAdaptiveIconsForNormalAndRoundLaunchers() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        assertTrue(context.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(context.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
    }
}
