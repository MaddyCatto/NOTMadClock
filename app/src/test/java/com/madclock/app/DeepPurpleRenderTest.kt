package com.madclock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import org.robolectric.util.ReflectionHelpers

/** Draw actual Android views with native graphics, rather than a hand-made mockup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DeepPurpleRenderTest {
    @Test fun launcherArtworkFitsSafeCircleAndIsNotCroppedByCommonMasks() {
        val context = RuntimeEnvironment.getApplication()
        val template = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val previous = ReflectionHelpers.getStaticField<Path>(AdaptiveIconDrawable::class.java, "sMask")
        val masks = linkedMapOf(
            "circle" to Path().apply { addCircle(50f, 50f, 50f, Path.Direction.CW) },
            "rounded-square" to Path().apply { addRoundRect(RectF(0f, 0f, 100f, 100f), 22f, 22f, Path.Direction.CW) },
            "squircle" to Path().apply {
                moveTo(50f, 0f); cubicTo(92f, 0f, 100f, 8f, 100f, 50f)
                cubicTo(100f, 92f, 92f, 100f, 50f, 100f)
                cubicTo(8f, 100f, 0f, 92f, 0f, 50f)
                cubicTo(0f, 8f, 8f, 0f, 50f, 0f); close()
            })
        val size = 432
        try {
            for ((name, mask) in masks) {
                // Test-only override of the framework mask; production uses the launcher's mask.
                ReflectionHelpers.setStaticField(AdaptiveIconDrawable::class.java, "sMask", mask)
                val icon = AdaptiveIconDrawable(template.background.constantState!!.newDrawable(context.resources),
                    template.foreground.constantState!!.newDrawable(context.resources))
                icon.setBounds(0, 0, size, size)
                val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val reference = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                icon.draw(Canvas(output)); icon.foreground.draw(Canvas(reference))
                var artworkPixels = 0
                for (y in 0 until size) for (x in 0 until size) {
                    val pixel = reference.getPixel(x, y)
                    if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 90) {
                        artworkPixels++
                        val dx = x - size / 2.0; val dy = y - size / 2.0
                        assertTrue("Artwork outside 66/72 safe circle: $name", dx * dx + dy * dy < (size * 33.0 / 72) * (size * 33.0 / 72))
                        assertEquals("Artwork clipped by $name", pixel, output.getPixel(x, y))
                    }
                }
                assertTrue("Missing artwork", artworkPixels > 10_000)
                val file = File("build/reports/screenshots/launcher-$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                reference.recycle(); output.recycle()
            }
        } finally { ReflectionHelpers.setStaticField(AdaptiveIconDrawable::class.java, "sMask", previous) }
    }
    @Test fun renderMainInPortraitAndLandscapeAndEditor() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(AlarmStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val store = AlarmStore(app)
        store.put(AlarmRecord(hour = 7, minute = 0, enabled = true, songUri = "content://song/1"))
        store.put(AlarmRecord(hour = 9, minute = 30, repeat = AlarmRepeat.ONCE, enabled = true, songUri = "content://song/2"))
        store.put(AlarmRecord(hour = 11, minute = 0, enabled = false, songUri = "content://song/3"))
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        render(main.get().findViewById(R.id.appRoot), 400, 800, "main-portrait")
        render(main.get().findViewById(R.id.appRoot), 1000, 600, "main-landscape")
        main.pause().stop().destroy()
        val editor = Robolectric.buildActivity(AlarmEditorActivity::class.java).setup()
        render(editor.get().findViewById(R.id.appRoot), 400, 800, "editor-portrait")
        editor.pause().stop().destroy()
    }
    private fun render(root: View, width: Int, height: Int, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        assertEquals(Color.parseColor("#0D0A14"), bitmap.getPixel(0, height / 2))
        val output = File("build/reports/screenshots/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
