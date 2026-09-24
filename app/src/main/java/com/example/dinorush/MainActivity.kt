package com.example.dinorush

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity(), GameView.Listener {

    private enum class Screen { SPLASH, MENU, PLAYING, PAUSED, GAME_OVER, SETTINGS, HOW_TO_PLAY, HIGH_SCORES }

    private lateinit var gameView: GameView
    private lateinit var prefs: GamePrefs
    private lateinit var soundManager: SoundManager

    private lateinit var hudOverlay: View
    private lateinit var splashOverlay: View
    private lateinit var menuOverlay: View
    private lateinit var pauseOverlay: View
    private lateinit var gameOverOverlay: View
    private lateinit var settingsOverlay: View
    private lateinit var howToOverlay: View
    private lateinit var highScoresOverlay: View

    private lateinit var hudScore: TextView
    private lateinit var hudBest: TextView
    private lateinit var hudDistance: TextView
    private lateinit var hudCoins: TextView
    private lateinit var hudPowerUp: TextView
    private lateinit var onScreenControls: View

    private var currentScreen = Screen.SPLASH
    private var backPressedOnceAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = GamePrefs(this)
        soundManager = SoundManager(this)
        soundManager.init()

        gameView = findViewById(R.id.gameView)
        gameView.listener = this
        gameView.soundManager = soundManager

        hudOverlay = findViewById(R.id.hudOverlay)
        splashOverlay = findViewById(R.id.splashOverlay)
        menuOverlay = findViewById(R.id.menuOverlay)
        pauseOverlay = findViewById(R.id.pauseOverlay)
        gameOverOverlay = findViewById(R.id.gameOverOverlay)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        howToOverlay = findViewById(R.id.howToOverlay)
        highScoresOverlay = findViewById(R.id.highScoresOverlay)

        hudScore = findViewById(R.id.hudScore)
        hudBest = findViewById(R.id.hudBest)
        hudDistance = findViewById(R.id.hudDistance)
        hudCoins = findViewById(R.id.hudCoins)
        hudPowerUp = findViewById(R.id.hudPowerUp)
        onScreenControls = findViewById(R.id.onScreenControls)

        wireMenu()
        wirePause()
        wireGameOver()
        wireSettings()
        wireHowToPlay()
        wireHighScores()
        wireHud()

        showScreen(Screen.SPLASH)
        mainHandler.postDelayed({
            if (currentScreen == Screen.SPLASH) showScreen(Screen.MENU)
        }, 1800)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    // ---------------------------------------------------------------------
    // Screen management
    // ---------------------------------------------------------------------

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        splashOverlay.visibility = if (screen == Screen.SPLASH) View.VISIBLE else View.GONE
        menuOverlay.visibility = if (screen == Screen.MENU) View.VISIBLE else View.GONE
        hudOverlay.visibility = if (screen == Screen.PLAYING) View.VISIBLE else View.GONE
        pauseOverlay.visibility = if (screen == Screen.PAUSED) View.VISIBLE else View.GONE
        gameOverOverlay.visibility = if (screen == Screen.GAME_OVER) View.VISIBLE else View.GONE
        settingsOverlay.visibility = if (screen == Screen.SETTINGS) View.VISIBLE else View.GONE
        howToOverlay.visibility = if (screen == Screen.HOW_TO_PLAY) View.VISIBLE else View.GONE
        highScoresOverlay.visibility = if (screen == Screen.HIGH_SCORES) View.VISIBLE else View.GONE

        if (screen == Screen.PLAYING) {
            onScreenControls.visibility = if (prefs.onScreenControls) View.VISIBLE else View.GONE
        }

        if (screen == Screen.HIGH_SCORES) refreshHighScoresView()
    }

    private fun handleBack() {
        when (currentScreen) {
            Screen.SPLASH -> { /* ignore back on splash */ }
            Screen.PLAYING -> {
                gameView.pauseGame()
                showScreen(Screen.PAUSED)
            }
            Screen.PAUSED -> {
                gameView.stopToMenu()
                showScreen(Screen.MENU)
            }
            Screen.GAME_OVER -> {
                gameView.stopToMenu()
                showScreen(Screen.MENU)
            }
            Screen.SETTINGS, Screen.HOW_TO_PLAY, Screen.HIGH_SCORES -> {
                showScreen(Screen.MENU)
            }
            Screen.MENU -> {
                val now = System.currentTimeMillis()
                if (now - backPressedOnceAt < 2000) {
                    finish()
                } else {
                    backPressedOnceAt = now
                    Toast.makeText(this, R.string.exit_confirm, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Menu
    // ---------------------------------------------------------------------

    private fun wireMenu() {
        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            soundManager.playClick()
            gameView.startNewGame()
            showScreen(Screen.PLAYING)
        }
        findViewById<Button>(R.id.btnHighScores).setOnClickListener {
            soundManager.playClick()
            showScreen(Screen.HIGH_SCORES)
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            soundManager.playClick()
            loadSettingsIntoUi()
            showScreen(Screen.SETTINGS)
        }
        findViewById<Button>(R.id.btnHowToPlay).setOnClickListener {
            soundManager.playClick()
            showScreen(Screen.HOW_TO_PLAY)
        }
        findViewById<Button>(R.id.btnExit).setOnClickListener {
            soundManager.playClick()
            finish()
        }
    }

    // ---------------------------------------------------------------------
    // Pause
    // ---------------------------------------------------------------------

    private fun wirePause() {
        findViewById<ImageButton>(R.id.btnPause).setOnClickListener {
            soundManager.playClick()
            gameView.pauseGame()
            showScreen(Screen.PAUSED)
        }
        findViewById<Button>(R.id.btnResume).setOnClickListener {
            soundManager.playClick()
            gameView.resumeGame()
            showScreen(Screen.PLAYING)
        }
        findViewById<Button>(R.id.btnPauseRestart).setOnClickListener {
            soundManager.playClick()
            gameView.startNewGame()
            showScreen(Screen.PLAYING)
        }
        findViewById<Button>(R.id.btnPauseMainMenu).setOnClickListener {
            soundManager.playClick()
            gameView.stopToMenu()
            showScreen(Screen.MENU)
        }
    }

    // ---------------------------------------------------------------------
    // Game over
    // ---------------------------------------------------------------------

    private fun wireGameOver() {
        findViewById<Button>(R.id.btnGoRestart).setOnClickListener {
            soundManager.playClick()
            gameView.startNewGame()
            showScreen(Screen.PLAYING)
        }
        findViewById<Button>(R.id.btnGoMainMenu).setOnClickListener {
            soundManager.playClick()
            gameView.stopToMenu()
            showScreen(Screen.MENU)
        }
    }

    override fun onGameOver(score: Int, best: Int, distanceMeters: Int, coins: Int, isNewBest: Boolean) {
        runOnUiThread {
            findViewById<TextView>(R.id.goScore).text = getString(R.string.label_score) + ": " + score
            findViewById<TextView>(R.id.goBest).text = getString(R.string.label_best) + ": " + best + if (isNewBest) "  \u2605 NEW BEST!" else ""
            findViewById<TextView>(R.id.goDistance).text = getString(R.string.label_distance) + ": " + distanceMeters + "m"
            findViewById<TextView>(R.id.goCoins).text = getString(R.string.label_coins) + ": " + coins
            showScreen(Screen.GAME_OVER)
        }
    }

    // ---------------------------------------------------------------------
    // HUD
    // ---------------------------------------------------------------------

    private fun wireHud() {
        findViewById<Button>(R.id.btnOnScreenJump).setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                gameView.performJump()
            }
            true
        }
        findViewById<Button>(R.id.btnOnScreenDuck).setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> gameView.setDuckingExternal(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> gameView.setDuckingExternal(false)
            }
            true
        }
    }

    override fun onHudUpdate(score: Int, best: Int, distanceMeters: Int, coins: Int, powerUpLabel: String?, powerUpFraction: Float) {
        runOnUiThread {
            hudScore.text = getString(R.string.label_score) + ": " + score
            hudBest.text = getString(R.string.label_best) + ": " + best
            hudDistance.text = getString(R.string.label_distance) + ": " + distanceMeters + "m"
            hudCoins.text = getString(R.string.label_coins) + ": " + coins
            if (powerUpLabel != null) {
                hudPowerUp.visibility = View.VISIBLE
                hudPowerUp.text = powerUpLabel
            } else {
                hudPowerUp.visibility = View.GONE
            }
        }
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    private fun wireSettings() {
        val switchSound = findViewById<Switch>(R.id.switchSound)
        val switchMusic = findViewById<Switch>(R.id.switchMusic)
        val switchVibration = findViewById<Switch>(R.id.switchVibration)
        val switchControls = findViewById<Switch>(R.id.switchOnScreenControls)
        val radioGroup = findViewById<RadioGroup>(R.id.radioGraphics)

        switchSound.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> prefs.soundEnabled = checked }
        switchMusic.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            prefs.musicEnabled = checked
            soundManager.refreshMusicState()
        }
        switchVibration.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> prefs.vibrationEnabled = checked }
        switchControls.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            prefs.onScreenControls = checked
            onScreenControls.visibility = if (checked && currentScreen == Screen.PLAYING) View.VISIBLE else View.GONE
        }
        radioGroup.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            prefs.graphicsQuality = when (checkedId) {
                R.id.radioLow -> 0
                R.id.radioHigh -> 2
                else -> 1
            }
        }

        findViewById<Button>(R.id.btnResetScores).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_body)
                .setPositiveButton("Reset") { _, _ ->
                    prefs.resetHighScores()
                    Toast.makeText(this, "High scores reset", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.btnAbout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_about)
                .setMessage(R.string.about_body)
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener {
            soundManager.playClick()
            showScreen(Screen.MENU)
        }
    }

    private fun loadSettingsIntoUi() {
        findViewById<Switch>(R.id.switchSound).isChecked = prefs.soundEnabled
        findViewById<Switch>(R.id.switchMusic).isChecked = prefs.musicEnabled
        findViewById<Switch>(R.id.switchVibration).isChecked = prefs.vibrationEnabled
        findViewById<Switch>(R.id.switchOnScreenControls).isChecked = prefs.onScreenControls
        val radioGroup = findViewById<RadioGroup>(R.id.radioGraphics)
        radioGroup.check(
            when (prefs.graphicsQuality) {
                0 -> R.id.radioLow
                2 -> R.id.radioHigh
                else -> R.id.radioNormal
            }
        )
    }

    // ---------------------------------------------------------------------
    // How to play
    // ---------------------------------------------------------------------

    private fun wireHowToPlay() {
        findViewById<Button>(R.id.btnHowToBack).setOnClickListener {
            soundManager.playClick()
            showScreen(Screen.MENU)
        }
    }

    // ---------------------------------------------------------------------
    // High scores
    // ---------------------------------------------------------------------

    private fun wireHighScores() {
        findViewById<Button>(R.id.btnHighScoresBack).setOnClickListener {
            soundManager.playClick()
            showScreen(Screen.MENU)
        }
    }

    private fun refreshHighScoresView() {
        findViewById<TextView>(R.id.statBest).text = "Best Score: " + prefs.bestScore
        findViewById<TextView>(R.id.statLongestDistance).text = "Longest Distance: " + prefs.longestDistance + "m"
        findViewById<TextView>(R.id.statTotalCoins).text = "Total Coins: " + prefs.totalCoins
        findViewById<TextView>(R.id.statTotalJumps).text = "Total Jumps: " + prefs.totalJumps
        findViewById<TextView>(R.id.statObstaclesPassed).text = "Obstacles Passed: " + prefs.obstaclesPassed
        findViewById<TextView>(R.id.statRunsPlayed).text = "Runs Played: " + prefs.runsPlayed
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        soundManager.refreshMusicState()
    }

    override fun onPause() {
        super.onPause()
        if (currentScreen == Screen.PLAYING) {
            gameView.pauseGame()
            showScreen(Screen.PAUSED)
        }
        soundManager.stopMusic()
    }

    override fun onDestroy() {
        super.onDestroy()
        soundManager.release()
    }
}
