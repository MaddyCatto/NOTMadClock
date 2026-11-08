package com.madclock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

/** Draw actual Android views with native graphics, rather than a hand-made mockup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DeepPurpleRenderTest {
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
