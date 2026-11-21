package com.madclock.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.madclock.ipc.InputChecks
import java.util.UUID

class AlarmEditorActivity : ComponentActivity() {
    private lateinit var store: AlarmStore
    private lateinit var draft: AlarmRecord
    private var creating = true
    private lateinit var time: TimePicker
    private lateinit var repeat: Spinner
    private lateinit var enabled: Switch
    private lateinit var volume: SeekBar
    private var updatingViews = false
    private var draggingVolume = false

    private val chooseSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val granted = runCatching {
            require(InputChecks.song(uri.toString()))
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (granted.isFailure) {
            UiSupport.toast(this, getString(R.string.song_access_failed))
            return@registerForActivityResult
        }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: getString(R.string.select_song)
        change { copy(songUri = uri.toString(), songName = name) }
        showSong()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AlarmStore(this)
        val id = intent.getStringExtra("alarm_id")
        creating = id == null
        draft = if (id == null) restoreDraft(savedInstanceState) else store.find(id) ?: run { finish(); return }
        setContentView(R.layout.activity_editor)
        UiSupport.decorate(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishEditing()
        })
        findViewById<ImageButton>(R.id.back).setOnClickListener { finishEditing() }
        findViewById<TextView>(R.id.editorTitle).setText(if (creating) R.string.new_alarm else R.string.edit_alarm)
        time = findViewById<TimePicker>(R.id.timePicker).apply { setIs24HourView(true) }
        repeat = findViewById<Spinner>(R.id.repeatMode).apply {
            adapter = ArrayAdapter(this@AlarmEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf(getString(R.string.daily), getString(R.string.once)))
        }
        enabled = findViewById(R.id.editorEnabled)
        volume = findViewById(R.id.volume)
        // Read the persisted record or the unsaved draft, even when Android restores the view tree.
        listOf(time, repeat, enabled, volume).forEach { it.isSaveFromParentEnabled = false }
        showFields()
        time.setOnTimeChangedListener { _, hour, minute -> change { copy(hour = hour, minute = minute) } }
        repeat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                change { copy(repeat = AlarmRepeat.entries[position]) }
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        enabled.setOnCheckedChangeListener { _, checked ->
            if (!updatingViews) {
                if (checked && !UiSupport.ready(this)) showFields()
                else change { copy(enabled = checked) }
            }
        }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                showVolume()
                if (!draggingVolume) change { copy(volumePercent = value) }
            }
            override fun onStartTrackingTouch(view: SeekBar) { draggingVolume = true }
            override fun onStopTrackingTouch(view: SeekBar) {
                draggingVolume = false
                change { copy(volumePercent = volume.progress) }
            }
        })
        findViewById<Button>(R.id.selectSong).setOnClickListener { chooseSong.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.defaultSong).setOnClickListener {
            change { copy(songUri = null, songName = null) }
            showSong()
        }
        findViewById<Button>(R.id.deleteAlarm).apply {
            visibility = if (creating) View.GONE else View.VISIBLE
            setOnClickListener {
                AlertDialog.Builder(this@AlarmEditorActivity).setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.delete_alarm) { _, _ ->
                        if (AlarmActions.delete(this@AlarmEditorActivity, draft.id)) finish()
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }
    }

    private fun change(edit: AlarmRecord.() -> AlarmRecord) {
        if (updatingViews) return
        // A one-shot may have fired while this editor was open. Apply only the field being edited.
        val latest = if (creating) draft else store.find(draft.id) ?: return
        val value = latest.edit()
        if (creating || value == latest || AlarmActions.save(this, value)) {
            draft = if (creating) value else store.find(value.id) ?: return
            if (enabled.isChecked != draft.enabled) showEnabled()
        } else {
            draft = latest
            showFields()
            UiSupport.toast(this, getString(R.string.save_failed))
        }
    }

    private fun flushControls() {
        time.clearFocus() // Commit time typed into the spinner's keyboard fields.
        change {
            copy(hour = time.hour, minute = time.minute,
                repeat = AlarmRepeat.entries.getOrNull(this@AlarmEditorActivity.repeat.selectedItemPosition) ?: repeat,
                volumePercent = volume.progress)
        }
    }

    private fun finishEditing() {
        flushControls()
        // An untouched editor is only a draft. Explicit Back commits a new, edited draft.
        if (creating && draft != newDraft(draft.id)) {
            if (draft.enabled && !UiSupport.ready(this)) return
            if (!AlarmActions.save(this, draft)) {
                UiSupport.toast(this, getString(if (store.alarms.size >= AlarmStore.MAX_ALARMS) R.string.limit else R.string.save_failed))
                return
            }
        }
        finish()
    }

    private fun showFields() {
        updatingViews = true
        time.hour = draft.hour
        time.minute = draft.minute
        repeat.setSelection(draft.repeat.ordinal)
        enabled.isChecked = draft.enabled
        volume.progress = draft.volumePercent
        updatingViews = false
        showSong()
        showVolume()
    }

    private fun showEnabled() {
        updatingViews = true
        enabled.isChecked = draft.enabled
        updatingViews = false
    }

    private fun showSong() {
        findViewById<TextView>(R.id.songValue).text = if (draft.songUri == null) getString(R.string.default_song)
            else draft.songName ?: getString(R.string.select_song)
        findViewById<Button>(R.id.defaultSong).visibility = if (draft.songUri == null) View.GONE else View.VISIBLE
    }

    private fun showVolume() {
        findViewById<TextView>(R.id.volumeValue).text = getString(R.string.volume_value, volume.progress)
    }

    override fun onPause() {
        if (!isFinishing && ::time.isInitialized) flushControls()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (creating && ::draft.isInitialized) with(outState) {
            putString("draft_id", draft.id)
            putInt("hour", draft.hour)
            putInt("minute", draft.minute)
            putInt("repeat", draft.repeat.ordinal)
            putBoolean("enabled", draft.enabled)
            putInt("volume", draft.volumePercent)
            putString("song", draft.songUri)
            putString("songName", draft.songName)
        }
        super.onSaveInstanceState(outState)
    }

    private fun newDraft(id: String) = AlarmRecord(id = id, enabled = false)

    private fun restoreDraft(state: Bundle?): AlarmRecord {
        val value = newDraft(state?.getString("draft_id") ?: UUID.randomUUID().toString())
        return if (state == null) value else value.copy(
            hour = state.getInt("hour", value.hour), minute = state.getInt("minute", value.minute),
            repeat = AlarmRepeat.entries.getOrElse(state.getInt("repeat")) { value.repeat },
            enabled = state.getBoolean("enabled", value.enabled),
            volumePercent = state.getInt("volume", value.volumePercent),
            songUri = state.getString("song"), songName = state.getString("songName"))
    }
}
