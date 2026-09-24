package com.example.dinorush

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build

/**
 * All sound effects and the background music loop are original, procedurally
 * generated WAV files bundled under res/raw (see tools/generate_sounds.py in
 * the repo for how they were created) — no external or copyrighted audio.
 */
class SoundManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var musicPlayer: MediaPlayer? = null

    private var idJump = 0
    private var idLand = 0
    private var idCoin = 0
    private var idPowerUp = 0
    private var idCrash = 0
    private var idClick = 0
    private var idHighScore = 0
    private var idGameOver = 0

    private val prefs = GamePrefs(context)

    fun init() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()

        soundPool?.let { sp ->
            idJump = sp.load(context, R.raw.sfx_jump, 1)
            idLand = sp.load(context, R.raw.sfx_land, 1)
            idCoin = sp.load(context, R.raw.sfx_coin, 1)
            idPowerUp = sp.load(context, R.raw.sfx_powerup, 1)
            idCrash = sp.load(context, R.raw.sfx_crash, 1)
            idClick = sp.load(context, R.raw.sfx_click, 1)
            idHighScore = sp.load(context, R.raw.sfx_highscore, 1)
            idGameOver = sp.load(context, R.raw.sfx_gameover, 1)
        }
    }

    private fun play(id: Int) {
        if (!prefs.soundEnabled) return
        soundPool?.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun playJump() = play(idJump)
    fun playLand() = play(idLand)
    fun playCoin() = play(idCoin)
    fun playPowerUp() = play(idPowerUp)
    fun playCrash() = play(idCrash)
    fun playClick() = play(idClick)
    fun playHighScore() = play(idHighScore)
    fun playGameOver() = play(idGameOver)

    fun startMusic() {
        if (!prefs.musicEnabled) return
        if (musicPlayer == null) {
            musicPlayer = MediaPlayer.create(context, R.raw.bg_music)
            musicPlayer?.isLooping = true
            musicPlayer?.setVolume(0.35f, 0.35f)
        }
        try {
            musicPlayer?.let { if (!it.isPlaying) it.start() }
        } catch (_: IllegalStateException) {
            // Player was released elsewhere; recreate lazily next time.
            musicPlayer = null
        }
    }

    fun stopMusic() {
        try {
            musicPlayer?.pause()
        } catch (_: IllegalStateException) {
        }
    }

    fun refreshMusicState() {
        if (prefs.musicEnabled) startMusic() else stopMusic()
    }

    fun vibrate(durationMs: Long) {
        if (!prefs.vibrationEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        musicPlayer?.release()
        musicPlayer = null
    }
}
