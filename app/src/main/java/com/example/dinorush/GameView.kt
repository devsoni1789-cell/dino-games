package com.example.dinorush

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dino Rush game canvas.
 *
 * Everything is drawn procedurally with Canvas primitives (rects, circles,
 * paths) — there is no bitmap/PNG art in this project, so there are no
 * copyright concerns around the original Chrome Dino artwork.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---------------------------------------------------------------------
    // Public API / listener
    // ---------------------------------------------------------------------

    interface Listener {
        fun onHudUpdate(score: Int, best: Int, distanceMeters: Int, coins: Int, powerUpLabel: String?, powerUpFraction: Float)
        fun onGameOver(score: Int, best: Int, distanceMeters: Int, coins: Int, isNewBest: Boolean)
    }

    var listener: Listener? = null
    var soundManager: SoundManager? = null

    enum class State { IDLE, RUNNING, PAUSED, GAME_OVER }
    var state = State.IDLE
        private set

    fun isPlaying() = state == State.RUNNING

    private val prefs = GamePrefs(context)

    // ---------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------

    private enum class ObstacleType(val isAir: Boolean) {
        SMALL_ROCK(false), LARGE_ROCK(false), CACTUS(false), LOG(false), BARRIER(false),
        FLYER(true), DRONE(true)
    }

    private enum class PowerUpType(val label: String) {
        SHIELD("SHIELD"), SLOW_MO("SLOW-MO"), DOUBLE_SCORE("2x SCORE"),
        INVINCIBLE("INVINCIBLE"), DOUBLE_JUMP("DOUBLE JUMP")
    }

    private data class Obstacle(
        val type: ObstacleType,
        var x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val baseY: Float = y,
        var wobble: Float = 0f,
        var passed: Boolean = false
    )

    private data class Coin(var x: Float, var y: Float, var collected: Boolean = false)

    private data class PowerUpEntity(var x: Float, var y: Float, val type: PowerUpType, var collected: Boolean = false)

    private data class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val radius: Float
    )

    // ---------------------------------------------------------------------
    // World / view metrics
    // ---------------------------------------------------------------------

    private var viewW = 0f
    private var viewH = 0f
    private var groundY = 0f
    private var scale = 1f // relative to a 1280x720-ish reference

    // ---------------------------------------------------------------------
    // Dino state
    // ---------------------------------------------------------------------

    private var dinoX = 0f
    private var dinoY = 0f
    private var dinoW = 0f
    private var dinoStandH = 0f
    private var dinoDuckH = 0f
    private var dinoH = 0f
    private var velocityY = 0f
    private var onGround = true
    private var isDucking = false
    private var isHoldingJump = false
    private var jumpHoldElapsed = 0f
    private var doubleJumpCharges = 0
    private var doubleJumpUsedThisAirtime = false
    private var runPhase = 0f
    private var wasAirborne = false

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var swipeConsumedAsDuck = false

    // ---------------------------------------------------------------------
    // Run state
    // ---------------------------------------------------------------------

    private var distanceTraveled = 0f // px
    private var speed = 0f            // px/sec
    private var baseSpeed = 0f
    private var scoreF = 0f
    private var score = 0
    private var coinsThisRun = 0
    private var jumpsThisRun = 0
    private var obstaclesPassedThisRun = 0

    private var distanceSinceLastObstacle = 0f
    private var nextObstacleGap = 0f
    private var distanceSinceLastCoinRow = 0f
    private var nextCoinRowGap = 0f
    private var distanceSinceLastPowerUp = 0f
    private var nextPowerUpGap = 0f

    private val obstacles = ArrayList<Obstacle>()
    private val coins = ArrayList<Coin>()
    private val powerUps = ArrayList<PowerUpEntity>()
    private val particles = ArrayList<Particle>()

    // Active power-up effects
    private var shieldActive = false
    private var invincibleTimer = 0f
    private var slowMoTimer = 0f
    private var doubleScoreTimer = 0f
    private var activePowerUpLabel: String? = null
    private var activePowerUpMaxDuration = 1f
    private var activePowerUpTimeLeft = 0f

    // Day/night + environment
    private var dayNightPhase = 0f // 0..1 looping
    private var environmentIndex = 0
    private val environmentDistancePeriod = 2600f

    private var hudUpdateTimer = 0f

    // ---------------------------------------------------------------------
    // Constants (scaled by `scale` at runtime)
    // ---------------------------------------------------------------------

    private val gravityRef = 3600f
    private val jumpVelocityRef = -1380f
    private val doubleJumpVelocityRef = -1180f
    private val holdGravityScale = 0.42f
    private val maxHoldSeconds = 0.22f
    private val pixelsPerMeter = 40f
    private val dayNightPeriodSeconds = 90f

    // ---------------------------------------------------------------------
    // Paints (reused, not allocated per-frame)
    // ---------------------------------------------------------------------

    private val skyPaint = Paint()
    private val groundPaint = Paint().apply { color = Color.parseColor("#3B2A2A") }
    private val groundLinePaint = Paint().apply { color = Color.parseColor("#5A4038"); strokeWidth = 4f }
    private val hillPaint = Paint().apply { alpha = 120 }
    private val obstaclePaint = Paint().apply { style = Paint.Style.FILL }
    private val obstacleOutline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#22000000") }
    private val dinoBodyPaint = Paint().apply { color = Color.parseColor("#3FA687") }
    private val dinoBellyPaint = Paint().apply { color = Color.parseColor("#E8F5EF") }
    private val dinoEyePaint = Paint().apply { color = Color.parseColor("#12241E") }
    private val coinPaint = Paint().apply { color = Color.parseColor("#F4C430") }
    private val coinShinePaint = Paint().apply { color = Color.parseColor("#FFF6D5") }
    private val powerUpTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 20f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val shieldRingPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.parseColor("#6FD8FF")
    }
    private val particlePaint = Paint()

    private val rectF = RectF()
    private val dinoHitbox = RectF()
    private val otherHitbox = RectF()

    init {
        isClickable = false
        isFocusable = false
    }

    // ---------------------------------------------------------------------
    // Lifecycle / sizing
    // ---------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w.toFloat()
        viewH = h.toFloat()
        groundY = viewH * 0.80f
        scale = viewH / 720f

        dinoW = viewH * 0.16f
        dinoStandH = viewH * 0.20f
        dinoDuckH = dinoStandH * 0.55f
        dinoH = dinoStandH
        dinoX = viewW * 0.14f
        dinoY = groundY - dinoH
        onGround = true
    }

    private val choreographer = Choreographer.getInstance()
    private var lastFrameNanos = 0L
    private var loopPosted = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos != 0L) {
                var dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                if (dt > 0.05f) dt = 0.05f
                if (state == State.RUNNING) update(dt)
            }
            lastFrameNanos = frameTimeNanos
            invalidate()
            if (isAttachedToWindow) {
                choreographer.postFrameCallback(this)
            } else {
                loopPosted = false
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!loopPosted) {
            loopPosted = true
            lastFrameNanos = 0L
            choreographer.postFrameCallback(frameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loopPosted = false
    }

    // ---------------------------------------------------------------------
    // Public controls
    // ---------------------------------------------------------------------

    fun startNewGame() {
        obstacles.clear(); coins.clear(); powerUps.clear(); particles.clear()
        distanceTraveled = 0f
        baseSpeed = 340f * scale
        speed = baseSpeed
        scoreF = 0f; score = 0
        coinsThisRun = 0; jumpsThisRun = 0; obstaclesPassedThisRun = 0
        dinoY = groundY - dinoStandH
        dinoH = dinoStandH
        velocityY = 0f
        onGround = true
        isDucking = false
        isHoldingJump = false
        doubleJumpCharges = 0
        doubleJumpUsedThisAirtime = false
        shieldActive = false
        invincibleTimer = 0f
        slowMoTimer = 0f
        doubleScoreTimer = 0f
        activePowerUpLabel = null
        dayNightPhase = 0f
        environmentIndex = 0

        // Generous grace period so the first 10-20s are easy, per spec.
        distanceSinceLastObstacle = 0f
        nextObstacleGap = baseSpeed * 12f
        distanceSinceLastCoinRow = 0f
        nextCoinRowGap = baseSpeed * 6f
        distanceSinceLastPowerUp = 0f
        nextPowerUpGap = baseSpeed * 16f

        state = State.RUNNING
        lastFrameNanos = 0L
    }

    fun pauseGame() {
        if (state == State.RUNNING) state = State.PAUSED
    }

    fun resumeGame() {
        if (state == State.PAUSED) {
            state = State.RUNNING
            lastFrameNanos = 0L
        }
    }

    fun stopToMenu() {
        state = State.IDLE
    }

    // ---------------------------------------------------------------------
    // Touch input
    // ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state != State.RUNNING) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                swipeConsumedAsDuck = false
                tryJump()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchDownY
                val dx = event.x - touchDownX
                if (!swipeConsumedAsDuck && dy > 60f * scale && dy > kotlin.math.abs(dx)) {
                    swipeConsumedAsDuck = true
                    // A deliberate downward swipe cancels any jump-in-progress and ducks.
                    if (onGround || dinoY > groundY - dinoStandH * 0.4f) {
                        velocityY = 0f
                        dinoY = groundY - dinoDuckH
                        onGround = true
                    }
                    isDucking = true
                    isHoldingJump = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDucking = false
                isHoldingJump = false
            }
        }
        return true
    }

    /** Public entry point for the optional on-screen JUMP button. */
    fun performJump() {
        if (state != State.RUNNING) return
        tryJump()
    }

    /** Public entry point for the optional on-screen DUCK button (held while pressed). */
    fun setDuckingExternal(active: Boolean) {
        if (state != State.RUNNING) return
        if (active) {
            if (onGround || dinoY > groundY - dinoStandH * 0.4f) {
                velocityY = 0f
                dinoY = groundY - dinoDuckH
                onGround = true
            }
            isDucking = true
            isHoldingJump = false
        } else {
            isDucking = false
        }
    }

    private fun tryJump() {
        if (onGround && !isDucking) {
            velocityY = jumpVelocityRef * scale
            onGround = false
            isHoldingJump = true
            jumpHoldElapsed = 0f
            doubleJumpUsedThisAirtime = false
            jumpsThisRun++
            soundManager?.playJump()
        } else if (!onGround && doubleJumpCharges > 0 && !doubleJumpUsedThisAirtime) {
            velocityY = doubleJumpVelocityRef * scale
            doubleJumpCharges--
            doubleJumpUsedThisAirtime = true
            isHoldingJump = true
            jumpHoldElapsed = 0f
            jumpsThisRun++
            spawnBurst(dinoX + dinoW / 2f, dinoY + dinoH / 2f, Color.parseColor("#8FE3FF"), 10)
            soundManager?.playJump()
        }
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    private fun update(dt: Float) {
        val slowFactor = if (slowMoTimer > 0f) 0.55f else 1f

        // difficulty ramp (gentle, based on distance)
        val rampedSpeed = baseSpeed + min(baseSpeed * 1.6f, distanceTraveled * 0.02f)
        speed = rampedSpeed * slowFactor

        distanceTraveled += speed * dt
        val multiplier = if (doubleScoreTimer > 0f) 2f else 1f
        scoreF += speed * dt * 0.05f * multiplier
        score = scoreF.toInt()

        updateDinoPhysics(dt)
        updateEnvironment(dt)
        updateSpawning(dt)
        updateObstacles(dt)
        updateCoins(dt)
        updatePowerUps(dt)
        updateEffects(dt)
        updateParticles(dt)

        hudUpdateTimer += dt
        if (hudUpdateTimer >= 0.1f) {
            hudUpdateTimer = 0f
            listener?.onHudUpdate(
                score, max(prefs.bestScore, score), (distanceTraveled / pixelsPerMeter).toInt(),
                coinsThisRun, activePowerUpLabel, if (activePowerUpMaxDuration > 0f) activePowerUpTimeLeft / activePowerUpMaxDuration else 0f
            )
        }
    }

    private fun updateDinoPhysics(dt: Float) {
        if (isHoldingJump) jumpHoldElapsed += dt
        val holding = isHoldingJump && velocityY < 0f && jumpHoldElapsed < maxHoldSeconds
        val gravity = gravityRef * scale * (if (holding) holdGravityScale else 1f)
        velocityY += gravity * dt
        dinoY += velocityY * dt

        dinoH = if (isDucking && onGround) dinoDuckH else dinoStandH
        val floor = groundY - dinoH

        if (dinoY >= floor) {
            dinoY = floor
            if (!onGround && wasAirborne) {
                soundManager?.playLand()
            }
            onGround = true
            velocityY = 0f
            isHoldingJump = false
            doubleJumpUsedThisAirtime = false
            wasAirborne = false
        } else {
            onGround = false
            wasAirborne = true
        }

        runPhase += dt * (if (onGround) 10f else 4f)
    }

    private fun updateEnvironment(dt: Float) {
        dayNightPhase = (dayNightPhase + dt / dayNightPeriodSeconds) % 1f
        environmentIndex = ((distanceTraveled / environmentDistancePeriod).toInt()) % ENV_COUNT
    }

    private fun currentDifficultyTier(): Int = when {
        score < 120 -> 0
        score < 300 -> 1
        score < 600 -> 2
        else -> 3
    }

    private fun updateSpawning(dt: Float) {
        val moveDelta = speed * dt
        distanceSinceLastObstacle += moveDelta
        distanceSinceLastCoinRow += moveDelta
        distanceSinceLastPowerUp += moveDelta

        if (distanceSinceLastObstacle >= nextObstacleGap) {
            spawnObstacle()
            distanceSinceLastObstacle = 0f
            // Keep a generous, speed-proportional minimum time-gap so every
            // sequence stays jumpable/duckable -- difficulty comes from
            // speed and variety, not from unfair spacing.
            val minGap = speed * 1.0f + 260f * scale
            val maxGap = speed * 1.9f + 420f * scale
            nextObstacleGap = minGap + Random.nextFloat() * (maxGap - minGap)
        }

        if (distanceSinceLastCoinRow >= nextCoinRowGap) {
            spawnCoinRow()
            distanceSinceLastCoinRow = 0f
            nextCoinRowGap = speed * 3.2f + Random.nextFloat() * speed * 2f
        }

        if (distanceSinceLastPowerUp >= nextPowerUpGap) {
            spawnPowerUp()
            distanceSinceLastPowerUp = 0f
            nextPowerUpGap = speed * 9f + Random.nextFloat() * speed * 6f
        }
    }

    private fun spawnObstacle() {
        val tier = currentDifficultyTier()
        val groundTypes = arrayOf(ObstacleType.SMALL_ROCK, ObstacleType.LARGE_ROCK, ObstacleType.CACTUS, ObstacleType.LOG, ObstacleType.BARRIER)
        val airTypes = arrayOf(ObstacleType.FLYER, ObstacleType.DRONE)

        val type = if (tier >= 1 && Random.nextFloat() < 0.30f + tier * 0.05f) {
            airTypes[Random.nextInt(airTypes.size)]
        } else {
            groundTypes[Random.nextInt(groundTypes.size)]
        }

        val h: Float
        val w: Float
        val y: Float
        when (type) {
            ObstacleType.SMALL_ROCK -> { w = 34f * scale; h = 34f * scale; y = groundY - h }
            ObstacleType.LARGE_ROCK -> { w = 52f * scale; h = 58f * scale; y = groundY - h }
            ObstacleType.CACTUS -> { w = 30f * scale; h = 70f * scale; y = groundY - h }
            ObstacleType.LOG -> { w = 90f * scale; h = 30f * scale; y = groundY - h }
            ObstacleType.BARRIER -> { w = 26f * scale; h = 80f * scale; y = groundY - h }
            ObstacleType.FLYER -> { w = 54f * scale; h = 30f * scale; y = groundY - dinoStandH * 0.95f }
            ObstacleType.DRONE -> { w = 44f * scale; h = 28f * scale; y = groundY - dinoStandH * 1.05f }
        }

        obstacles.add(Obstacle(type, viewW + w, y, w, h, baseY = y))

        // Occasionally spawn a tight-but-fair combo: a second, different
        // obstacle after a safe extra gap (only from tier 2 onward).
        if (tier >= 2 && Random.nextFloat() < 0.22f) {
            val comboGap = speed * 1.3f + 300f * scale
            val comboType = if (type.isAir) groundTypes[Random.nextInt(groundTypes.size)] else airTypes[Random.nextInt(airTypes.size)]
            val cw: Float; val ch: Float; val cy: Float
            when (comboType) {
                ObstacleType.SMALL_ROCK -> { cw = 34f * scale; ch = 34f * scale; cy = groundY - ch }
                ObstacleType.LARGE_ROCK -> { cw = 52f * scale; ch = 58f * scale; cy = groundY - ch }
                ObstacleType.CACTUS -> { cw = 30f * scale; ch = 70f * scale; cy = groundY - ch }
                ObstacleType.LOG -> { cw = 90f * scale; ch = 30f * scale; cy = groundY - ch }
                ObstacleType.BARRIER -> { cw = 26f * scale; ch = 80f * scale; cy = groundY - ch }
                ObstacleType.FLYER -> { cw = 54f * scale; ch = 30f * scale; cy = groundY - dinoStandH * 0.95f }
                ObstacleType.DRONE -> { cw = 44f * scale; ch = 28f * scale; cy = groundY - dinoStandH * 1.05f }
            }
            obstacles.add(Obstacle(comboType, viewW + w + comboGap, cy, cw, ch, baseY = cy))
        }
    }

    private fun spawnCoinRow() {
        val count = 3 + Random.nextInt(3)
        val arched = Random.nextBoolean()
        val spacing = 46f * scale
        val startX = viewW + 40f * scale
        for (i in 0 until count) {
            val x = startX + i * spacing
            val y = if (arched) {
                val t = i / (count - 1f).coerceAtLeast(1f)
                val arc = sin(t * Math.PI).toFloat()
                groundY - dinoStandH * 0.5f - arc * dinoStandH * 0.9f
            } else {
                groundY - 26f * scale
            }
            coins.add(Coin(x, y))
        }
    }

    private fun spawnPowerUp() {
        val types = PowerUpType.values()
        val type = types[Random.nextInt(types.size)]
        val y = groundY - dinoStandH * 0.75f
        powerUps.add(PowerUpEntity(viewW + 40f * scale, y, type))
    }

    private fun updateObstacles(dt: Float) {
        val it = obstacles.iterator()
        val advance = speed * dt
        while (it.hasNext()) {
            val o = it.next()
            o.x -= advance
            if (o.type == ObstacleType.DRONE) {
                o.wobble += dt * 3f
            }
            if (o.x + o.w < 0f) {
                it.remove()
                continue
            }
            if (!o.passed && o.x + o.w < dinoX) {
                o.passed = true
                obstaclesPassedThisRun++
            }
            val drawY = if (o.type == ObstacleType.DRONE) o.baseY + sin(o.wobble) * 14f * scale else o.y
            otherHitbox.set(o.x, drawY, o.x + o.w, drawY + o.h)
            dinoHitboxNow()
            if (rectOverlapInset(dinoHitbox, otherHitbox)) {
                handleHit()
            }
        }
    }

    private fun updateCoins(dt: Float) {
        val it = coins.iterator()
        dinoHitboxNow()
        while (it.hasNext()) {
            val c = it.next()
            c.x -= speed * dt
            if (c.x < -30f) { it.remove(); continue }
            if (!c.collected) {
                val dx = (c.x) - (dinoX + dinoW / 2f)
                val dy = (c.y) - (dinoY + dinoH / 2f)
                val dist2 = dx * dx + dy * dy
                val r = 30f * scale
                if (dist2 < r * r) {
                    c.collected = true
                    coinsThisRun++
                    soundManager?.playCoin()
                    spawnBurst(c.x, c.y, Color.parseColor("#F4C430"), 6)
                    it.remove()
                }
            }
        }
    }

    private fun updatePowerUps(dt: Float) {
        val it = powerUps.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x -= speed * dt
            if (p.x < -40f) { it.remove(); continue }
            if (!p.collected) {
                val dx = p.x - (dinoX + dinoW / 2f)
                val dy = p.y - (dinoY + dinoH / 2f)
                val dist2 = dx * dx + dy * dy
                val r = 34f * scale
                if (dist2 < r * r) {
                    p.collected = true
                    applyPowerUp(p.type)
                    soundManager?.playPowerUp()
                    spawnBurst(p.x, p.y, Color.parseColor("#8FE3FF"), 12)
                    it.remove()
                }
            }
        }
    }

    private fun applyPowerUp(type: PowerUpType) {
        when (type) {
            PowerUpType.SHIELD -> { shieldActive = true; setActiveLabel("SHIELD", 0f) }
            PowerUpType.SLOW_MO -> { slowMoTimer = 6f; setActiveLabel("SLOW-MO", 6f) }
            PowerUpType.DOUBLE_SCORE -> { doubleScoreTimer = 8f; setActiveLabel("2x SCORE", 8f) }
            PowerUpType.INVINCIBLE -> { invincibleTimer = 5f; setActiveLabel("INVINCIBLE", 5f) }
            PowerUpType.DOUBLE_JUMP -> { doubleJumpCharges = min(3, doubleJumpCharges + 1); setActiveLabel("DOUBLE JUMP READY", 0f) }
        }
    }

    private fun setActiveLabel(label: String, duration: Float) {
        activePowerUpLabel = label
        activePowerUpMaxDuration = if (duration > 0f) duration else 1f
        activePowerUpTimeLeft = duration
    }

    private fun updateEffects(dt: Float) {
        if (slowMoTimer > 0f) { slowMoTimer -= dt; if (slowMoTimer <= 0f) clearLabelIfMatches("SLOW-MO") }
        if (doubleScoreTimer > 0f) { doubleScoreTimer -= dt; if (doubleScoreTimer <= 0f) clearLabelIfMatches("2x SCORE") }
        if (invincibleTimer > 0f) { invincibleTimer -= dt; if (invincibleTimer <= 0f) clearLabelIfMatches("INVINCIBLE") }
        if (activePowerUpTimeLeft > 0f) activePowerUpTimeLeft = max(0f, activePowerUpTimeLeft - dt)
    }

    private fun clearLabelIfMatches(label: String) {
        if (activePowerUpLabel == label) activePowerUpLabel = if (shieldActive) "SHIELD" else null
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 500f * dt
            p.life -= dt
            if (p.life <= 0f) it.remove()
        }
    }

    private fun spawnBurst(x: Float, y: Float, color: Int, count: Int) {
        repeat(count) {
            val angle = Random.nextFloat() * (Math.PI * 2).toFloat()
            val speedP = 80f + Random.nextFloat() * 160f
            particles.add(
                Particle(
                    x, y,
                    kotlin.math.cos(angle) * speedP, kotlin.math.sin(angle) * speedP,
                    0.5f, 0.5f, color, 4f * scale
                )
            )
        }
    }

    private fun dinoHitboxNow() {
        // Inset the hitbox slightly for forgiving, fair collisions.
        val insetX = dinoW * 0.18f
        val insetY = dinoH * 0.14f
        dinoHitbox.set(dinoX + insetX, dinoY + insetY, dinoX + dinoW - insetX, dinoY + dinoH - insetY)
    }

    private fun rectOverlap(a: RectF, b: RectF): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun rectOverlapInset(a: RectF, b: RectF): Boolean {
        val insetB = RectF(b.left + b.width() * 0.12f, b.top + b.height() * 0.12f, b.right - b.width() * 0.12f, b.bottom - b.height() * 0.12f)
        return rectOverlap(a, insetB)
    }

    private fun handleHit() {
        if (invincibleTimer > 0f) return
        if (shieldActive) {
            shieldActive = false
            if (activePowerUpLabel == "SHIELD") activePowerUpLabel = null
            spawnBurst(dinoX + dinoW / 2f, dinoY + dinoH / 2f, Color.parseColor("#6FD8FF"), 16)
            soundManager?.vibrate(40)
            return
        }
        gameOver()
    }

    private fun gameOver() {
        if (state == State.GAME_OVER) return
        state = State.GAME_OVER
        soundManager?.playCrash()
        soundManager?.vibrate(150)
        spawnBurst(dinoX + dinoW / 2f, dinoY + dinoH / 2f, Color.parseColor("#C0392B"), 20)

        val distMeters = (distanceTraveled / pixelsPerMeter).toInt()
        prefs.addCoins(coinsThisRun)
        prefs.addJumps(jumpsThisRun)
        prefs.addObstaclesPassed(obstaclesPassedThisRun)
        val isNewBest = prefs.reportRunFinished(score, distMeters)
        if (isNewBest) soundManager?.playHighScore() else soundManager?.playGameOver()

        listener?.onGameOver(score, prefs.bestScore, distMeters, coinsThisRun, isNewBest)
    }

    companion object {
        private const val ENV_COUNT = 5
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    // Sky palettes per environment: [dayTop, dayBottom, sunsetTop, sunsetBottom, nightTop, nightBottom]
    private val envPalettes = arrayOf(
        intArrayOf(0xFF8FD3F4.toInt(), 0xFFE8F8FF.toInt(), 0xFFFF9E6D.toInt(), 0xFFFFD3A0.toInt(), 0xFF0B1730.toInt(), 0xFF1C2C4A.toInt()), // desert
        intArrayOf(0xFF7FC9A0.toInt(), 0xFFDFF3E4.toInt(), 0xFFE68A6C.toInt(), 0xFFF7C99B.toInt(), 0xFF0C1F1B.toInt(), 0xFF16332B.toInt()), // forest
        intArrayOf(0xFF9FC9E8.toInt(), 0xFFEFF6FB.toInt(), 0xFFEB8FA0.toInt(), 0xFFF7D0C4.toInt(), 0xFF10192E.toInt(), 0xFF223050.toInt()), // mountains
        intArrayOf(0xFF7C8FB8.toInt(), 0xFFD9E1EE.toInt(), 0xFFB07AC4.toInt(), 0xFFF0B0C6.toInt(), 0xFF07081A.toInt(), 0xFF1A1830.toInt()), // night city
        intArrayOf(0xFFE7C989.toInt(), 0xFFF7E9C7.toInt(), 0xFFD98850.toInt(), 0xFFF0B37A.toInt(), 0xFF201308.toInt(), 0xFF3A2413.toInt())  // ruins
    )

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        return Color.rgb(
            (ar + (br - ar) * tt).toInt(),
            (ag + (bg - ag) * tt).toInt(),
            (ab + (bb - ab) * tt).toInt()
        )
    }

    /** Returns (top, bottom) sky colors for the current phase, smoothly blended day->sunset->night->sunrise->day. */
    private fun skyColorsForPhase(): Pair<Int, Int> {
        val p = envPalettes[environmentIndex]
        val day = Pair(p[0], p[1]); val sunset = Pair(p[2], p[3]); val night = Pair(p[4], p[5])
        // phase: 0-0.25 day->sunset, 0.25-0.5 sunset->night, 0.5-0.75 night->sunrise(=sunset colors), 0.75-1 sunrise->day
        return when {
            dayNightPhase < 0.25f -> {
                val t = dayNightPhase / 0.25f
                Pair(lerpColor(day.first, sunset.first, t), lerpColor(day.second, sunset.second, t))
            }
            dayNightPhase < 0.5f -> {
                val t = (dayNightPhase - 0.25f) / 0.25f
                Pair(lerpColor(sunset.first, night.first, t), lerpColor(sunset.second, night.second, t))
            }
            dayNightPhase < 0.75f -> {
                val t = (dayNightPhase - 0.5f) / 0.25f
                Pair(lerpColor(night.first, sunset.first, t), lerpColor(night.second, sunset.second, t))
            }
            else -> {
                val t = (dayNightPhase - 0.75f) / 0.25f
                Pair(lerpColor(sunset.first, day.first, t), lerpColor(sunset.second, day.second, t))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (viewW <= 0f || viewH <= 0f) return

        val (skyTop, skyBottom) = skyColorsForPhase()
        skyPaint.shader = LinearGradient(0f, 0f, 0f, groundY, skyTop, skyBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, viewW, groundY, skyPaint)

        drawParallaxHills(canvas, skyBottom)
        drawGround(canvas)

        dinoHitboxNow()
        for (o in obstacles) drawObstacle(canvas, o)
        for (c in coins) if (!c.collected) drawCoin(canvas, c)
        for (p in powerUps) if (!p.collected) drawPowerUp(canvas, p)
        drawDino(canvas)
        drawParticles(canvas)
    }

    private fun drawParallaxHills(canvas: Canvas, tint: Int) {
        hillPaint.color = tint
        hillPaint.alpha = 140
        val offset = (distanceTraveled * 0.12f) % (viewW + 260f * scale)
        var x = -offset
        while (x < viewW) {
            canvas.drawOval(x, groundY - 90f * scale, x + 220f * scale, groundY + 40f * scale, hillPaint)
            x += 260f * scale
        }
    }

    private fun drawGround(canvas: Canvas) {
        canvas.drawRect(0f, groundY, viewW, viewH, groundPaint)
        val segment = 46f * scale
        val offset = (distanceTraveled) % segment
        var x = -offset
        while (x < viewW) {
            canvas.drawLine(x, groundY + 6f * scale, x + segment * 0.5f, groundY + 6f * scale, groundLinePaint)
            x += segment
        }
    }

    private fun drawObstacle(canvas: Canvas, o: Obstacle) {
        val drawY = if (o.type == ObstacleType.DRONE) o.baseY + sin(o.wobble) * 14f * scale else o.y
        obstaclePaint.color = when (o.type) {
            ObstacleType.SMALL_ROCK, ObstacleType.LARGE_ROCK -> Color.parseColor("#7C7368")
            ObstacleType.CACTUS -> Color.parseColor("#3E7D4C")
            ObstacleType.LOG -> Color.parseColor("#6B4A2F")
            ObstacleType.BARRIER -> Color.parseColor("#B04A3A")
            ObstacleType.FLYER -> Color.parseColor("#8E4FBF")
            ObstacleType.DRONE -> Color.parseColor("#3B3F45")
        }
        when (o.type) {
            ObstacleType.SMALL_ROCK, ObstacleType.LARGE_ROCK -> {
                canvas.drawRoundRect(o.x, drawY, o.x + o.w, drawY + o.h, 8f * scale, 8f * scale, obstaclePaint)
            }
            ObstacleType.CACTUS -> {
                canvas.drawRoundRect(o.x, drawY, o.x + o.w, drawY + o.h, 6f * scale, 6f * scale, obstaclePaint)
                canvas.drawRoundRect(o.x - o.w * 0.5f, drawY + o.h * 0.25f, o.x, drawY + o.h * 0.55f, 5f * scale, 5f * scale, obstaclePaint)
            }
            ObstacleType.LOG -> canvas.drawRoundRect(o.x, drawY, o.x + o.w, drawY + o.h, 14f * scale, 14f * scale, obstaclePaint)
            ObstacleType.BARRIER -> canvas.drawRoundRect(o.x, drawY, o.x + o.w, drawY + o.h, 4f * scale, 4f * scale, obstaclePaint)
            ObstacleType.FLYER -> {
                val cx = o.x + o.w / 2f; val cy = drawY + o.h / 2f
                canvas.drawOval(o.x, drawY, o.x + o.w, drawY + o.h, obstaclePaint)
                val wingFlap = sin(runPhase * 1.6f) * o.h * 0.5f
                canvas.drawOval(cx - o.w * 0.3f, cy - o.h * 0.5f - wingFlap, cx, cy, obstaclePaint)
                canvas.drawOval(cx, cy - o.h * 0.5f - wingFlap, cx + o.w * 0.3f, cy, obstaclePaint)
            }
            ObstacleType.DRONE -> {
                canvas.drawRoundRect(o.x, drawY, o.x + o.w, drawY + o.h, 8f * scale, 8f * scale, obstaclePaint)
                val blinkColor = if ((runPhase.toInt() % 2) == 0) Color.RED else Color.parseColor("#552222")
                obstaclePaint.color = blinkColor
                canvas.drawCircle(o.x + o.w / 2f, drawY + o.h / 2f, 4f * scale, obstaclePaint)
            }
        }
        canvas.drawRoundRect(o.x, drawY, o.x + o.w, drawY + o.h, 6f * scale, 6f * scale, obstacleOutline)
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val r = 14f * scale
        canvas.drawCircle(c.x, c.y, r, coinPaint)
        canvas.drawCircle(c.x - r * 0.3f, c.y - r * 0.3f, r * 0.35f, coinShinePaint)
    }

    private fun drawPowerUp(canvas: Canvas, p: PowerUpEntity) {
        val r = 20f * scale
        val bgColor = when (p.type) {
            PowerUpType.SHIELD -> Color.parseColor("#3E7BD9")
            PowerUpType.SLOW_MO -> Color.parseColor("#7C58C9")
            PowerUpType.DOUBLE_SCORE -> Color.parseColor("#D9A23E")
            PowerUpType.INVINCIBLE -> Color.parseColor("#D93E6B")
            PowerUpType.DOUBLE_JUMP -> Color.parseColor("#3ED98C")
        }
        obstaclePaint.color = bgColor
        canvas.drawCircle(p.x, p.y, r, obstaclePaint)
        val glyph = when (p.type) {
            PowerUpType.SHIELD -> "S"
            PowerUpType.SLOW_MO -> "Z"
            PowerUpType.DOUBLE_SCORE -> "2x"
            PowerUpType.INVINCIBLE -> "\u2605"
            PowerUpType.DOUBLE_JUMP -> "\u2191\u2191"
        }
        powerUpTextPaint.textSize = 16f * scale
        canvas.drawText(glyph, p.x, p.y + 6f * scale, powerUpTextPaint)
    }

    private fun drawDino(canvas: Canvas) {
        if (state == State.GAME_OVER) {
            dinoBodyPaint.color = Color.parseColor("#B04A3A")
        } else if (invincibleTimer > 0f && (runPhase.toInt() % 2 == 0)) {
            dinoBodyPaint.color = Color.parseColor("#8FE3FF")
        } else {
            dinoBodyPaint.color = Color.parseColor("#3FA687")
        }

        val bx = dinoX; val by = dinoY; val bw = dinoW; val bh = dinoH
        // body
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, 14f * scale, 14f * scale, dinoBodyPaint)
        // belly
        canvas.drawRoundRect(bx + bw * 0.15f, by + bh * 0.45f, bx + bw * 0.85f, by + bh * 0.95f, 10f * scale, 10f * scale, dinoBellyPaint)
        // eye
        canvas.drawCircle(bx + bw * 0.72f, by + bh * 0.28f, 4f * scale, dinoEyePaint)
        // legs (simple alternating rectangles while running)
        val legOffset = if (onGround) sin(runPhase) * bh * 0.12f else 0f
        val legW = bw * 0.18f
        val legH = bh * 0.22f
        canvas.drawRoundRect(bx + bw * 0.2f, by + bh - legH * 0.5f + legOffset, bx + bw * 0.2f + legW, by + bh + legH * 0.5f + legOffset, 4f * scale, 4f * scale, dinoBodyPaint)
        canvas.drawRoundRect(bx + bw * 0.55f, by + bh - legH * 0.5f - legOffset, bx + bw * 0.55f + legW, by + bh + legH * 0.5f - legOffset, 4f * scale, 4f * scale, dinoBodyPaint)
        // tail
        canvas.drawRoundRect(bx - bw * 0.18f, by + bh * 0.25f, bx, by + bh * 0.55f, 8f * scale, 8f * scale, dinoBodyPaint)

        if (shieldActive) {
            canvas.drawCircle(bx + bw / 2f, by + bh / 2f, max(bw, bh) * 0.75f, shieldRingPaint)
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            particlePaint.color = p.color
            particlePaint.alpha = (255 * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.radius, particlePaint)
        }
    }
}
