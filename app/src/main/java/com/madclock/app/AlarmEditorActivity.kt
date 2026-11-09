package com.madclock.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.madclock.ipc.InputChecks

class AlarmEditorActivity : ComponentActivity() {
    private lateinit var draft: AlarmRecord
    private var creating = true
    private lateinit var time: TimePicker
    private lateinit var repeat: Spinner
    private lateinit var enabled: Switch
    private lateinit var volume: SeekBar
    private val chooseSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            require(InputChecks.song(uri.toString()))
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: getString(R.string.select_song)
            draft = draft.copy(songUri = uri.toString(), songName = name); showSong()
        }.onFailure { UiSupport.toast(this, "Не удалось сохранить доступ к песне") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AlarmStore(this)
        val id = intent.getStringExtra("alarm_id")
        creating = id == null
        draft = if (creating) AlarmRecord(id = savedInstanceState?.getString("draft_id") ?: java.util.UUID.randomUUID().toString())
            else store.find(id!!) ?: run { finish(); return }
        if (savedInstanceState != null) draft = draft.copy(
            hour = savedInstanceState.getInt("hour", draft.hour), minute = savedInstanceState.getInt("minute", draft.minute),
            repeat = AlarmRepeat.valueOf(savedInstanceState.getString("repeat", draft.repeat.name)),
            enabled = savedInstanceState.getBoolean("enabled", draft.enabled), volumePercent = savedInstanceState.getInt("volume", draft.volumePercent),
            songUri = savedInstanceState.getString("song", draft.songUri), songName = savedInstanceState.getString("songName", draft.songName))
        setContentView(R.layout.activity_editor); UiSupport.decorate(this)
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.editorTitle).setText(if (creating) R.string.new_alarm else R.string.edit_alarm)
        time = findViewById<TimePicker>(R.id.timePicker).apply { setIs24HourView(true); hour = draft.hour; minute = draft.minute }
        repeat = findViewById<Spinner>(R.id.repeatMode).apply {
            adapter = ArrayAdapter(this@AlarmEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf(getString(R.string.daily), getString(R.string.once)))
            setSelection(if (draft.repeat == AlarmRepeat.DAILY) 0 else 1)
        }
        enabled = findViewById<Switch>(R.id.editorEnabled).apply { isChecked = draft.enabled }
        volume = findViewById<SeekBar>(R.id.volume).apply { progress = draft.volumePercent }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) { showVolume() }
            override fun onStartTrackingTouch(view: SeekBar) = Unit
            override fun onStopTrackingTouch(view: SeekBar) = Unit
        })
        findViewById<Button>(R.id.selectSong).setOnClickListener { chooseSong.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.saveAlarm).setOnClickListener { save() }
        findViewById<Button>(R.id.deleteAlarm).apply {
            visibility = if (creating) View.GONE else View.VISIBLE
            setOnClickListener {
                AlertDialog.Builder(this@AlarmEditorActivity).setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.delete_alarm) { _, _ -> if (AlarmActions.delete(this@AlarmEditorActivity, draft.id)) finish() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }
        showSong(); showVolume()
    }
    private fun edited() = draft.copy(hour = time.hour, minute = time.minute,
        repeat = if (repeat.selectedItemPosition == 0) AlarmRepeat.DAILY else AlarmRepeat.ONCE,
        enabled = enabled.isChecked, volumePercent = volume.progress.coerceIn(1, 100))
    private fun save() {
        val store = AlarmStore(this)
        if (!creating && store.find(draft.id) == null) { finish(); return }
        val value = edited()
        if (value.songUri.isNullOrBlank()) { UiSupport.toast(this, "Выберите локальную песню"); return }
        if (value.enabled && !UiSupport.ready(this, value)) return
        if (AlarmActions.save(this, value)) finish()
        else UiSupport.toast(this, getString(if (creating && store.alarms.size >= AlarmStore.MAX_ALARMS) R.string.limit else R.string.save_failed))
    }
    private fun showSong() { findViewById<TextView>(R.id.songValue).text = draft.songName ?: getString(R.string.no_song) }
    private fun showVolume() { findViewById<TextView>(R.id.volumeValue).text = getString(R.string.volume_value, volume.progress.coerceIn(1, 100)) }
    override fun onSaveInstanceState(outState: Bundle) {
        val current = edited()
        outState.putString("draft_id", current.id); outState.putInt("hour", current.hour); outState.putInt("minute", current.minute)
        outState.putString("repeat", current.repeat.name); outState.putBoolean("enabled", current.enabled)
        outState.putInt("volume", current.volumePercent); outState.putString("song", current.songUri); outState.putString("songName", current.songName)
        super.onSaveInstanceState(outState)
    }
}
