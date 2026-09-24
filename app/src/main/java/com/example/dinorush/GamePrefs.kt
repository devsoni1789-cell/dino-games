package com.example.dinorush

import android.content.Context
import android.content.SharedPreferences

/**
 * Small wrapper around SharedPreferences used for:
 *  - persistent high score / lifetime stats (survives app restarts and reboots)
 *  - user settings (sound, music, vibration, graphics quality, controls)
 */
class GamePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("dino_rush_prefs", Context.MODE_PRIVATE)

    // ---------- High score & stats ----------

    var bestScore: Int
        get() = prefs.getInt(KEY_BEST_SCORE, 0)
        set(value) = prefs.edit().putInt(KEY_BEST_SCORE, value).apply()

    var longestDistance: Int
        get() = prefs.getInt(KEY_LONGEST_DISTANCE, 0)
        set(value) = prefs.edit().putInt(KEY_LONGEST_DISTANCE, value).apply()

    var totalCoins: Int
        get() = prefs.getInt(KEY_TOTAL_COINS, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_COINS, value).apply()

    var totalJumps: Int
        get() = prefs.getInt(KEY_TOTAL_JUMPS, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_JUMPS, value).apply()

    var obstaclesPassed: Int
        get() = prefs.getInt(KEY_OBSTACLES_PASSED, 0)
        set(value) = prefs.edit().putInt(KEY_OBSTACLES_PASSED, value).apply()

    var runsPlayed: Int
        get() = prefs.getInt(KEY_RUNS_PLAYED, 0)
        set(value) = prefs.edit().putInt(KEY_RUNS_PLAYED, value).apply()

    fun addJumps(count: Int) {
        totalJumps += count
    }

    fun addObstaclesPassed(count: Int) {
        obstaclesPassed += count
    }

    fun addCoins(count: Int) {
        totalCoins += count
    }

    fun incrementRunsPlayed() {
        runsPlayed += 1
    }

    /** Returns true if this was a new best. */
    fun reportRunFinished(score: Int, distanceMeters: Int): Boolean {
        var newBest = false
        if (score > bestScore) {
            bestScore = score
            newBest = true
        }
        if (distanceMeters > longestDistance) {
            longestDistance = distanceMeters
        }
        incrementRunsPlayed()
        return newBest
    }

    fun resetHighScores() {
        prefs.edit()
            .putInt(KEY_BEST_SCORE, 0)
            .putInt(KEY_LONGEST_DISTANCE, 0)
            .putInt(KEY_TOTAL_COINS, 0)
            .putInt(KEY_TOTAL_JUMPS, 0)
            .putInt(KEY_OBSTACLES_PASSED, 0)
            .putInt(KEY_RUNS_PLAYED, 0)
            .apply()
    }

    // ---------- Settings ----------

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var musicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC, true)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC, value).apply()

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION, value).apply()

    /** 0 = low, 1 = normal, 2 = high */
    var graphicsQuality: Int
        get() = prefs.getInt(KEY_GRAPHICS, 1)
        set(value) = prefs.edit().putInt(KEY_GRAPHICS, value).apply()

    var onScreenControls: Boolean
        get() = prefs.getBoolean(KEY_ONSCREEN_CONTROLS, false)
        set(value) = prefs.edit().putBoolean(KEY_ONSCREEN_CONTROLS, value).apply()

    companion object {
        private const val KEY_BEST_SCORE = "best_score"
        private const val KEY_LONGEST_DISTANCE = "longest_distance"
        private const val KEY_TOTAL_COINS = "total_coins"
        private const val KEY_TOTAL_JUMPS = "total_jumps"
        private const val KEY_OBSTACLES_PASSED = "obstacles_passed"
        private const val KEY_RUNS_PLAYED = "runs_played"

        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_MUSIC = "music_enabled"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_GRAPHICS = "graphics_quality"
        private const val KEY_ONSCREEN_CONTROLS = "onscreen_controls"
    }
}
