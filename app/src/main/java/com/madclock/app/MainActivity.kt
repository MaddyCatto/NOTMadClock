package com.madclock.app

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private lateinit var store: AlarmStore
    private var rendered = false
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        UiSupport.decorate(this)
        store = AlarmStore(this)
        findViewById<ImageButton>(R.id.moreMenu).setOnClickListener { openMenu(it) }
        findViewById<ImageButton>(R.id.addAlarm).setOnClickListener { add() }
        findViewById<Button>(R.id.addEmpty).setOnClickListener { add() }
        findViewById<Button>(R.id.scanActive).setOnClickListener { UiSupport.barcode(this, false) }
        render()
    }
    private fun add() {
        if (store.alarms.size >= AlarmStore.MAX_ALARMS) { UiSupport.toast(this, getString(R.string.limit)); return }
        startActivity(Intent(this, AlarmEditorActivity::class.java))
    }
    private fun render() {
        if (!::store.isInitialized) return
        val scroll = findViewById<ScrollView>(R.id.alarmScroll)
        val position = scroll.scrollY
        val list = findViewById<LinearLayout>(R.id.alarmList)
        list.removeAllViews()
        val records = store.alarms.sortedWith(compareBy<AlarmRecord> { it.hour }.thenBy { it.minute }.thenBy { it.id })
        records.forEach { alarm ->
            val card = layoutInflater.inflate(R.layout.item_alarm, list, false)
            card.tag = alarm.id
            card.contentDescription = "${alarm.timeLabel()}, ${UiSupport.repeat(this, alarm.repeat)}"
            card.findViewById<TextView>(R.id.alarmTime).apply {
                text = alarm.timeLabel(); setTextColor(getColor(if (alarm.enabled) R.color.text_primary else R.color.text_secondary))
            }
            card.findViewById<TextView>(R.id.alarmRepeat).apply {
                text = UiSupport.repeat(this@MainActivity, alarm.repeat); alpha = if (alarm.enabled) 1f else 0.65f
            }
            val toggle = card.findViewById<Switch>(R.id.alarmEnabled)
            toggle.isChecked = alarm.enabled
            toggle.contentDescription = getString(R.string.toggle_alarm, alarm.timeLabel(), UiSupport.repeat(this, alarm.repeat))
            var reverting = false
            toggle.setOnCheckedChangeListener { button, checked ->
                if (!reverting) {
                    val latest = store.find(alarm.id)
                    val ready = latest != null && (!checked || UiSupport.ready(this, latest))
                    if (ready && latest != null) {
                        if (!AlarmActions.save(this, latest.copy(enabled = checked))) {
                            reverting = true; button.isChecked = latest.enabled; reverting = false
                            UiSupport.toast(this, getString(R.string.save_failed))
                        }
                    } else { reverting = true; button.isChecked = latest?.enabled ?: false; reverting = false }
                }
            }
            card.setOnClickListener { startActivity(Intent(this, AlarmEditorActivity::class.java).putExtra("alarm_id", alarm.id)) }
            list.addView(card)
        }
        findViewById<View>(R.id.emptyState).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.addAlarm).apply {
            isEnabled = records.size < AlarmStore.MAX_ALARMS; alpha = if (isEnabled) 1f else 0.45f
            contentDescription = getString(if (isEnabled) R.string.add_alarm else R.string.limit)
        }
        val active = store.activeAlarm
        val pending = store.pendingAudioStop != null
        findViewById<View>(R.id.activeBanner).visibility = if (active != null || pending) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.activeText).setText(if (active != null) R.string.alarm_sounding else R.string.stopping)
        findViewById<Button>(R.id.scanActive).visibility = if (active != null) View.VISIBLE else View.GONE
        if (rendered) scroll.post { scroll.scrollTo(0, position) }
        rendered = true
    }
    private fun openMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.barcode_menu); menu.add(0, 2, 1, R.string.permissions_menu)
            menu.add(0, 3, 2, R.string.sound_test); menu.add(0, 4, 3, R.string.about)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> if (store.activeAlarm != null) UiSupport.toast(this@MainActivity, getString(R.string.active_first))
                        else AlertDialog.Builder(this@MainActivity).setTitle(R.string.barcode_menu)
                            .setMessage(if (store.barcode.isNullOrBlank()) "Штрихкод пока не зарегистрирован" else "Общий штрихкод зарегистрирован")
                            .setPositiveButton(if (store.barcode.isNullOrBlank()) R.string.register_barcode else R.string.replace_barcode)
                                { _, _ -> UiSupport.barcode(this@MainActivity, true) }.setNegativeButton(R.string.cancel, null).show()
                    2 -> startActivity(Intent(this@MainActivity, PermissionsActivity::class.java))
                    3 -> testSound()
                    4 -> AlertDialog.Builder(this@MainActivity).setTitle(R.string.app_name).setMessage(R.string.about_text)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                true
            }
            show()
        }
    }
    private fun testSound() {
        if (store.activeAlarm != null || store.pendingAudioStop != null) { UiSupport.toast(this, getString(R.string.active_first)); return }
        val records = store.alarms.filter { !it.songUri.isNullOrBlank() }
        if (records.isEmpty()) { UiSupport.toast(this, getString(R.string.test_needs_alarm)); return }
        AlertDialog.Builder(this).setTitle(R.string.choose_test)
            .setItems(records.map { "${it.timeLabel()} — ${it.songName ?: getString(R.string.select_song)}" }.toTypedArray()) { _, index ->
                val record = records[index]
                if (UiSupport.ready(this, record, exact = false)) SignalController.test(this, record)
            }.setNegativeButton(R.string.cancel, null).show()
    }
    override fun onStart() {
        super.onStart(); getSharedPreferences(AlarmStore.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes)
    }
    override fun onResume() { super.onResume(); SignalController.onVisibleLaunch(this); render() }
    override fun onStop() {
        getSharedPreferences(AlarmStore.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes); super.onStop()
    }
}
