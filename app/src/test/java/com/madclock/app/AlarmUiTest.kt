package com.madclock.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmUiTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AlarmStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        store = AlarmStore(context); store.barcode = "common-code"
        AlarmSchedulesTest.installHelper(context)
        if (android.os.Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    private fun record(i: Int = 0, enabled: Boolean = false) = AlarmRecord(hour = 7 + i, minute = 0,
        songUri = "content://songs/$i", songName = "Song $i", enabled = enabled)
    private fun layout(view: View, width: Int = 400, height: Int = 800) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    @Test fun emptyHomeHasAddAndNoTechnicalOrSaveControls() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertNull(activity.findViewById<View>(R.id.saveAlarm))
        assertNull(activity.findViewById<View>(R.id.selectSong))
        assertTrue(activity.findViewById<ImageButton>(R.id.addAlarm).isEnabled)
        activity.findViewById<Button>(R.id.addEmpty).performClick()
        assertEquals(AlarmEditorActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        controller.pause().stop().destroy()
    }
    @Test fun tenCardsHaveIndependentSwitchesAndDisableAddAtLimit() {
        repeat(10) { assertTrue(AlarmActions.save(context, record(it))) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(R.id.appRoot)
        layout(root)
        val list = activity.findViewById<LinearLayout>(R.id.alarmList)
        assertEquals(10, list.childCount)
        assertFalse(activity.findViewById<ImageButton>(R.id.addAlarm).isEnabled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        val first = list.getChildAt(0)
        val sourceId = first.tag.toString()
        first.findViewById<Switch>(R.id.alarmEnabled).isChecked = true
        assertTrue(store.find(sourceId)!!.enabled)
        assertEquals(1, store.alarms.count { it.enabled })
        assertNull(shadowOf(activity).nextStartedActivity)
        val enabledCard = activity.findViewById<LinearLayout>(R.id.alarmList).getChildAt(0)
        enabledCard.performClick()
        assertEquals(sourceId, shadowOf(activity).nextStartedActivity.getStringExtra("alarm_id"))
        assertTrue(enabledCard.findViewById<Switch>(R.id.alarmEnabled).layoutParams.height >= 48)
        controller.pause().stop().destroy()
    }
    @Test fun menuContainsExactlyTheFourRequestedActions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().findViewById<ImageButton>(R.id.moreMenu).performClick()
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        assertEquals(listOf("Штрихкод", "Проверка разрешений", "Тест звука", "О приложении"),
            (0 until menu.size()).map { menu.getItem(it).title.toString() })
        controller.pause().stop().destroy()
    }
    @Test fun editorChangesOnlySelectedAlarmAndUsesExactlyTwoRepeatOptions() {
        val one = record(0); val two = record(1)
        AlarmActions.save(context, one); AlarmActions.save(context, two)
        val beforeTwo = store.find(two.id)!!
        val controller = Robolectric.buildActivity(AlarmEditorActivity::class.java,
            Intent(context, AlarmEditorActivity::class.java).putExtra("alarm_id", one.id)).setup()
        val editor = controller.get()
        val repeats = editor.findViewById<Spinner>(R.id.repeatMode)
        assertEquals(2, repeats.count)
        assertEquals("Ежедневно", repeats.getItemAtPosition(0)); assertEquals("Однократно", repeats.getItemAtPosition(1))
        repeats.setSelection(1)
        editor.findViewById<TimePicker>(R.id.timePicker).apply { hour = 23; minute = 15 }
        editor.findViewById<SeekBar>(R.id.volume).progress = 1
        editor.findViewById<Button>(R.id.saveAlarm).performClick()
        assertTrue(editor.isFinishing)
        val saved = store.find(one.id)!!
        assertEquals(23, saved.hour); assertEquals(15, saved.minute)
        assertEquals(AlarmRepeat.ONCE, saved.repeat); assertEquals(1, saved.volumePercent)
        assertEquals(beforeTwo, store.find(two.id))
        controller.pause().stop().destroy()
    }
    @Test fun newEditorHasNoDeleteAndPreservesUnsavedDraftAfterRecreation() {
        val controller = Robolectric.buildActivity(AlarmEditorActivity::class.java).setup()
        val editor = controller.get()
        assertEquals(View.GONE, editor.findViewById<View>(R.id.deleteAlarm).visibility)
        editor.findViewById<TimePicker>(R.id.timePicker).apply { hour = 9; minute = 30 }
        editor.findViewById<Spinner>(R.id.repeatMode).setSelection(1)
        editor.findViewById<SeekBar>(R.id.volume).progress = 96
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        val restored = Robolectric.buildActivity(AlarmEditorActivity::class.java).create(state).start().resume().visible()
        val newEditor = restored.get()
        assertEquals(9, newEditor.findViewById<TimePicker>(R.id.timePicker).hour)
        assertEquals(30, newEditor.findViewById<TimePicker>(R.id.timePicker).minute)
        assertEquals(1, newEditor.findViewById<Spinner>(R.id.repeatMode).selectedItemPosition)
        assertEquals(96, newEditor.findViewById<SeekBar>(R.id.volume).progress)
        assertTrue(store.alarms.isEmpty())
        restored.pause().stop().destroy()
    }
    @Test fun editorDeletionRemovesOnlyOneRecord() {
        val one = record(0); val two = record(1)
        AlarmActions.save(context, one); AlarmActions.save(context, two)
        val controller = Robolectric.buildActivity(AlarmEditorActivity::class.java,
            Intent(context, AlarmEditorActivity::class.java).putExtra("alarm_id", one.id)).setup()
        controller.get().findViewById<Button>(R.id.deleteAlarm).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
        assertNull(store.find(one.id)); assertNotNull(store.find(two.id))
        controller.pause().stop().destroy()
    }
    @Test fun idleMainDoesNotPollOrStartAudioAndActiveOneShotSwitchGoesOff() {
        val one = record(enabled = true).copy(repeat = AlarmRepeat.ONCE)
        AlarmActions.save(context, one)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        val scheduled = store.find(one.id)!!
        SignalController.onAlarm(context, one.id, scheduled.generation!!, scheduled.nextAtMs!!)
        val list = controller.get().findViewById<LinearLayout>(R.id.alarmList)
        assertFalse(list.getChildAt(0).findViewById<Switch>(R.id.alarmEnabled).isChecked)
        assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.activeBanner).visibility)
        assertNotNull(store.activeAlarm)
        controller.pause().stop().destroy()
    }
    @Test fun portraitAndLandscapeKeepCardsAndButtonsInsideAvailableWidth() {
        repeat(10) { store.put(record(it)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = controller.get().findViewById<View>(R.id.appRoot)
        for ((w, h) in listOf(400 to 800, 800 to 400, 1200 to 800, 800 to 1200)) {
            layout(root, w, h)
            val fab = controller.get().findViewById<ImageButton>(R.id.addAlarm)
            assertTrue(fab.width >= 48 && fab.height >= 48)
            val list = controller.get().findViewById<LinearLayout>(R.id.alarmList)
            for (i in 0 until list.childCount) {
                val card = list.getChildAt(i)
                assertTrue(card.width > 0 && card.width <= w)
                val toggle = card.findViewById<Switch>(R.id.alarmEnabled)
                assertTrue(toggle.right <= card.width)
                assertTrue(toggle.height >= 48)
            }
        }
        controller.pause().stop().destroy()
    }
}
