package com.madclock.app

import android.content.Context
import android.view.View
import android.view.KeyEvent
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.madclock.ipc.AudioContract as C
import com.madclock.ipc.SleepSound
import com.madclock.ipc.SleepStatus
import com.madclock.ipc.SleepStatus.Phase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class SleepUiTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        listOf(AlarmStore.PREFS, "sleep_settings", "MainActivity").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AlarmSchedulesTest.installHelper(app)
    }

    private fun replyStatus(status: SleepStatus) {
        val query = shadowOf(app).broadcastIntents.last { it.action == C.SLEEP_STATUS }
        assertEquals(C.PACKAGE, query.component!!.packageName)
        AlarmSchedulesTest.acknowledge(query, status.toBundle())
    }

    @Test fun settingsPersistAndReopenedPageReadsAudioStateWithoutStartingAService() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val main = first.get()
        assertTrue(main.findViewById<Button>(R.id.alarmTab).isSelected)
        main.findViewById<Button>(R.id.sleepTab).performClick()
        assertEquals(View.GONE, main.findViewById<View>(R.id.alarmPage).visibility)
        replyStatus(SleepStatus("before-play", Phase.IDLE, null))

        val sounds = main.findViewById<LinearLayout>(R.id.sleepSounds)
        assertEquals(SleepSound.entries.size, sounds.childCount)
        val names = (0 until sounds.childCount).map { index ->
            (sounds.getChildAt(index) as Button).also { assertNotNull(it.compoundDrawablesRelative[1]) }.text
        }
        assertEquals(names.size, names.toSet().size)
        sounds.getChildAt(SleepSound.WARM_PINK.ordinal).performClick()
        main.findViewById<Button>(R.id.sleepTimer).performClick()
        val options = ShadowAlertDialog.getLatestAlertDialog().listView
        assertEquals(listOf("Без таймера", "15 минут", "30 минут", "60 минут", "120 минут"),
            (0 until options.count).map { options.getItemAtPosition(it).toString() })
        options.performItemClick(null, 2, options.getItemIdAtPosition(2))
        val seek = main.findViewById<SeekBar>(R.id.sleepVolume)
        seek.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        val volume = seek.progress
        assertTrue(volume > 70)
        assertNull(shadowOf(app).nextStartedService)

        main.findViewById<ImageButton>(R.id.sleepPlay).performClick()
        val start = shadowOf(app).nextStartedService
        assertEquals(C.SLEEP_PLAY, start.action)
        assertEquals(C.PACKAGE, start.component!!.packageName)
        assertEquals("before-play", start.getStringExtra(C.SLEEP_TICKET))
        assertEquals(SleepSound.WARM_PINK.id, start.getStringExtra(C.SLEEP_SOUND))
        assertEquals(volume, start.getIntExtra(C.VOLUME, -1))
        assertEquals(30, start.getIntExtra(C.SLEEP_TIMER, -1))
        assertNull(start.data)
        first.pause().stop().destroy()

        val reopened = Robolectric.buildActivity(MainActivity::class.java).setup()
        val page = reopened.get()
        assertTrue(page.findViewById<Button>(R.id.sleepTab).isSelected)
        assertEquals(volume, page.findViewById<SeekBar>(R.id.sleepVolume).progress)
        assertTrue(page.findViewById<Button>(R.id.sleepTimer).text.contains("30 минут"))
        assertTrue(page.findViewById<LinearLayout>(R.id.sleepSounds).getChildAt(SleepSound.WARM_PINK.ordinal).isSelected)
        replyStatus(SleepStatus("playing", Phase.PLAYING, SleepSound.WARM_PINK))
        assertTrue(page.findViewById<TextView>(R.id.sleepStatus).text.contains("Тёплый розовый"))
        assertNull(shadowOf(app).nextStartedService)
        val toggle = page.findViewById<ImageButton>(R.id.sleepPlay)
        assertEquals(page.getString(R.string.sleep_stop), toggle.contentDescription)
        toggle.performClick()
        val stop = shadowOf(app).nextStartedService
        assertEquals(C.SLEEP_STOP, stop.action)
        assertEquals("playing", stop.getStringExtra(C.SLEEP_TICKET))
        AlarmSchedulesTest.acknowledge(stop, SleepStatus("stopped", Phase.IDLE, null).toBundle())
        assertEquals(page.getString(R.string.sleep_idle), page.findViewById<TextView>(R.id.sleepStatus).text)
        reopened.pause().stop().destroy()
    }

    @Test fun helperAlarmStateDisablesWhiteNoiseWithoutAPlaybackCommand() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val main = controller.get()
        main.findViewById<Button>(R.id.sleepTab).performClick()
        replyStatus(SleepStatus("alarm", Phase.ALARM, null))
        assertFalse(main.findViewById<ImageButton>(R.id.sleepPlay).isEnabled)
        assertEquals(main.getString(R.string.sleep_alarming), main.findViewById<TextView>(R.id.sleepStatus).text)
        assertNull(shadowOf(app).nextStartedService)
        controller.pause().stop().destroy()
    }
}
