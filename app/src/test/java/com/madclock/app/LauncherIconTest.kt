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
    @Test fun pngIsTheUnmodifiedUserAsset() {
        val bytes = RuntimeEnvironment.getApplication().resources.openRawResource(R.drawable.ic_launcher_artwork).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("2fec6ea5832eb171d2ab87df9c3b21a0b5fc4bdcc1944c9a1aa9fc14a00879e7", hash)
    }
    @Test fun mainUsesAdaptiveIconsForNormalAndRoundLaunchers() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        assertTrue(context.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(context.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
    }
}
