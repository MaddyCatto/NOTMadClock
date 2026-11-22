package com.madclock.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.madclock.ipc.AudioContract as C
import com.madclock.ipc.SleepSettings
import com.madclock.ipc.SleepSound
import com.madclock.ipc.SleepStatus
import com.madclock.ipc.SleepStatus.Phase

internal class SleepPage(private val activity: MainActivity, private val alarmActive: () -> Boolean) {
    private val prefs = activity.getSharedPreferences("sleep_settings", Context.MODE_PRIVATE)
    private var settings = SleepSettings(
        sound = SleepSound.fromId(prefs.getString("sound", null)) ?: SleepSound.WHITE,
        volume = prefs.getInt("volume", SleepSettings().volume).coerceIn(0, 100),
        timerMinutes = prefs.getInt("timer", 0).takeIf { it in SleepSettings.timerOptions } ?: 0
    )
    private val page = activity.findViewById<View>(R.id.sleepPage)
    private val carousel = page.findViewById<HorizontalScrollView>(R.id.sleepSoundScroll)
    private val soundList = page.findViewById<LinearLayout>(R.id.sleepSounds)
    private val volume = page.findViewById<SeekBar>(R.id.sleepVolume)
    private val volumeValue = page.findViewById<TextView>(R.id.sleepVolumeValue)
    private val timer = page.findViewById<Button>(R.id.sleepTimer)
    private val play = page.findViewById<ImageButton>(R.id.sleepPlay)
    private val selectedIcon = page.findViewById<ImageView>(R.id.sleepSelectedIcon)
    private val selectedName = page.findViewById<TextView>(R.id.sleepSelectedName)
    private val playbackLabel = page.findViewById<TextView>(R.id.sleepStatus)
    private val soundButtons = SleepSound.entries.associateWith(::addSound)
    private var listening = false
    private var dragging = false
    private var checking = false
    private var sending = false
    private var status: SleepStatus? = null
    private var request = 0
    private val stateChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == C.SLEEP_CHANGED) refresh()
        }
    }

    init {
        // Persisted choices are the source of truth across Activity recreation.
        volume.isSaveFromParentEnabled = false
        volume.progress = settings.volume
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings = settings.copy(volume = value)
                showVolume()
                if (!dragging) persistAndUpdate()
            }
            override fun onStartTrackingTouch(view: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(view: SeekBar) {
                dragging = false
                persistAndUpdate()
            }
        })
        timer.setOnClickListener { chooseTimer() }
        play.setOnClickListener { toggle() }
        showSettings()
        showPlayback()
    }

    fun show() {
        if (listening) return
        ContextCompat.registerReceiver(activity, stateChanges, IntentFilter(C.SLEEP_CHANGED),
            C.PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        listening = true
        carousel.post {
            soundButtons.getValue(settings.sound).let { button ->
                if (button.left < carousel.scrollX || button.right > carousel.scrollX + carousel.width)
                    carousel.scrollTo(button.left, 0)
            }
        }
        refresh()
    }

    fun hide() {
        if (!listening) return
        if (dragging) {
            dragging = false
            persistAndUpdate()
        }
        activity.unregisterReceiver(stateChanges)
        listening = false
        request++
        status = null
    }

    private fun refresh() {
        val serial = ++request
        checking = true
        showPlayback()
        AudioBridge.sleepStatus(activity) {
            if (listening && serial == request) {
                checking = false
                status = it
                showPlayback()
            }
        }
    }

    private fun toggle() {
        if (sending || blocked() || !UiSupport.audioReady(activity)) return
        val current = status
        if (current == null) { refresh(); return }
        sending = true
        val serial = request
        showPlayback()
        AudioBridge.toggleSleep(activity, current, settings) { ok, result ->
            sending = false
            if (listening) {
                if (result != null && serial == request) status = result
                if (!ok && result?.phase != Phase.ALARM && !blocked())
                    UiSupport.toast(activity, activity.getString(R.string.sleep_command_failed))
                if (result == null || serial != request) refresh() else showPlayback()
            }
        }
    }

    private fun chooseTimer() {
        val options = SleepSettings.timerOptions
        AlertDialog.Builder(activity).setTitle(R.string.sleep_timer)
            .setSingleChoiceItems(options.map(::timerLabel).toTypedArray(), options.indexOf(settings.timerMinutes)) { dialog, index ->
                settings = settings.copy(timerMinutes = options[index])
                showSettings()
                persistAndUpdate()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun addSound(sound: SleepSound): Button =
        (activity.layoutInflater.inflate(R.layout.item_sleep_sound, soundList, false) as Button).apply {
            text = activity.getString(sound.label)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, sound.icon, 0, 0)
            setOnClickListener {
                settings = settings.copy(sound = sound)
                showSettings()
                persistAndUpdate()
            }
            soundList.addView(this)
        }

    private fun persistAndUpdate() {
        prefs.edit().putString("sound", settings.sound.id).putInt("volume", settings.volume)
            .putInt("timer", settings.timerMinutes).apply()
        if (!sending && !blocked()) status?.let { AudioBridge.updateSleep(activity, it, settings) }
    }

    private fun timerLabel(minutes: Int): String = if (minutes == 0) activity.getString(R.string.sleep_no_timer)
        else activity.getString(R.string.sleep_minutes, minutes)

    private fun showSettings() {
        soundButtons.forEach { (sound, button) -> button.isSelected = sound == settings.sound }
        selectedIcon.setImageResource(settings.sound.icon)
        selectedName.setText(settings.sound.label)
        timer.text = activity.getString(R.string.sleep_timer_value, timerLabel(settings.timerMinutes))
        showVolume()
    }

    private fun showVolume() { volumeValue.text = activity.getString(R.string.volume_value, settings.volume) }
    private fun blocked() = alarmActive() || status?.phase == Phase.ALARM

    fun showPlayback() {
        val phase = status?.phase
        val playing = status?.running == true
        play.isEnabled = !sending && !blocked() && !(checking && status == null)
        play.alpha = if (play.isEnabled) 1f else 0.55f
        play.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        play.contentDescription = activity.getString(if (playing) R.string.sleep_stop else R.string.sleep_play)
        soundButtons.values.forEach { it.isEnabled = !sending }
        timer.isEnabled = !sending
        volume.isEnabled = !sending
        playbackLabel.text = when {
            blocked() -> activity.getString(R.string.sleep_alarming)
            phase == Phase.PLAYING -> activity.getString(R.string.sleep_playing, activity.getString(status!!.sound!!.label))
            phase == Phase.PREPARING || sending -> activity.getString(R.string.sleep_preparing)
            phase == Phase.IDLE -> activity.getString(R.string.sleep_idle)
            checking -> activity.getString(R.string.sleep_checking)
            else -> activity.getString(R.string.sleep_unavailable)
        }
    }

    private val SleepSound.label: Int get() = when (this) {
        SleepSound.WHITE -> R.string.sleep_white
        SleepSound.PINK -> R.string.sleep_pink
        SleepSound.BROWN -> R.string.sleep_brown
        SleepSound.BLUE -> R.string.sleep_blue
        SleepSound.VIOLET -> R.string.sleep_violet
        SleepSound.GRAY -> R.string.sleep_gray
        SleepSound.GREEN -> R.string.sleep_green
        SleepSound.SOFT_WHITE -> R.string.sleep_soft_white
        SleepSound.WARM_PINK -> R.string.sleep_warm_pink
        SleepSound.DEEP_BROWN -> R.string.sleep_deep_brown
    }

    private val SleepSound.icon: Int get() = when (this) {
        SleepSound.WHITE -> R.drawable.ic_waveform
        SleepSound.PINK -> R.drawable.ic_noise_pink
        SleepSound.BROWN -> R.drawable.ic_noise_brown
        SleepSound.BLUE -> R.drawable.ic_noise_blue
        SleepSound.VIOLET -> R.drawable.ic_noise_violet
        SleepSound.GRAY -> R.drawable.ic_noise_gray
        SleepSound.GREEN -> R.drawable.ic_noise_green
        SleepSound.SOFT_WHITE -> R.drawable.ic_noise_soft
        SleepSound.WARM_PINK -> R.drawable.ic_noise_warm
        SleepSound.DEEP_BROWN -> R.drawable.ic_noise_deep
    }
}
