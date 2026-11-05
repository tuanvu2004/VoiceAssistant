package com.tuanvu.voiceassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sân khấu nhận diện AI 3D trung tâm (AiStageView).
 *
 * Kết hợp 3 phong cách nhận diện AI 3D đa chiều đúng bảng màu Đen - Đỏ - Cam - Vàng:
 *  1. [Style.SILK_ORB]   : Quả cầu sóng lụa 3D đa chiều (lõi đen tuyền obsidian, 44 vòng sợi lụa 3D
 *                          uốn theo nếp gấp không gian, tán sắc đỏ-cam-vàng ở rìa và sóng âm nền).
 *  2. [Style.SILK_WAVE]  : Dải sóng lụa 3D xoắn đa chiều trải ngang màn hình (44 sợi lưới sóng âm 3D
 *                          biến hình mượt trực tiếp từ quả cầu 3D).
 *  3. [Style.NEKO_SPIRIT]: Linh vật AI đen tuyền viền sáng neon 3D ngồi trên đường chân trời phát sáng
 *                          (có phản chiếu gương dưới sàn, mắt & tai biểu cảm theo trạng thái nghe/nói).
 *
 * Người dùng có thể vuốt để xoay góc nhìn 3D hoặc chạm để chuyển đổi phong cách hiển thị.
 */
class AiStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Style(val tenHienThi: String) {
        SILK_ORB("Lõi Lụa 3D"),
        SILK_WAVE("Sóng Đa Chiều 3D"),
        NEKO_SPIRIT("Linh Vật AI 3D")
    }

    var state: VoiceState = VoiceState.IDLE
        set(value) {
            field = value
            contentDescription = value.moTa
            invalidate()
        }

    var style: Style = Style.SILK_ORB
        set(value) {
            field = value
            onStyleChanged?.invoke(value)
            invalidate()
        }

    var onStyleChanged: ((Style) -> Unit)? = null

    fun nextStyle(): Style {
        style = when (style) {
            Style.SILK_ORB -> Style.SILK_WAVE
            Style.SILK_WAVE -> Style.NEKO_SPIRIT
            Style.NEKO_SPIRIT -> Style.SILK_ORB
        }
        return style
    }

    private val dp = resources.displayMetrics.density

    // ---- Cấu hình lưới 3D (Pre-allocated, không cấp phát trong onDraw) ----
    private val numRings = 42
    private val numPts = 72
    private val ringPaths = Array(numRings) { Path() }
    private val dispPaths = Array(numRings / 2) { Path() } // Lớp tán sắc đỏ-hồng phía sau
    private val ringZAvg = FloatArray(numRings)
    private val ringOrder = IntArray(numRings) { it }

    private val coreSilhouettePath = Path()
    private val bgWavePath = Path()
    private val catBodyPath = Path()
    private val catEarLeftInner = Path()
    private val catEarRightInner = Path()
    private val mouthPath = Path()
    private val whiskerPath = Path()

    // ---- Hạt năng lượng 3D bay quanh tâm ----
    private val numSparks = 28
    private val sparkTheta = FloatArray(numSparks)
    private val sparkPhi = FloatArray(numSparks)
    private val sparkRadius = FloatArray(numSparks)
    private val sparkSpeed = FloatArray(numSparks)
    private val sparkSize = FloatArray(numSparks)

    // ---- Biến trạng thái thời gian & vật lý mượt ----
    private var lastMs = SystemClock.uptimeMillis()
    private var time = 0f
    private var energy = 0.18f
    private var speed = 0.75f
    private var pulse = 0f
    private var waveMorph = 0f      // 0 = SILK_ORB, 1 = SILK_WAVE
    private var spiritMorph = 0f    // 0 = Mesh (Orb/Wave), 1 = NEKO_SPIRIT

    // Tương tác chạm & xoay 3D
    private var touchX = 0f
    private var touchY = 0f
    private var targetTouchX = 0f
    private var targetTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    // ---- Bảng màu chủ đạo (Đen -> Đỏ núi lửa -> Cam rực -> Vàng hoàng kim) ----
    private val colGoldBright = Color.parseColor("#FFF4C2")
    private val colGold = Color.parseColor("#FFE27A")
    private val colAmber = Color.parseColor("#FFB62E")
    private val colOrange = Color.parseColor("#FF881B")
    private val colRedOrange = Color.parseColor("#FF4518")
    private val colCrimson = Color.parseColor("#C81912")
    private val colRubyMagenta = Color.parseColor("#B81448")
    private val colObsidian = Color.parseColor("#050304")

    // ---- Paints ----
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coreFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val coreRimBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * dp
        maskFilter = BlurMaskFilter(12f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val dispersionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.6f * dp
    }
    private val silkFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.25f * dp
    }
    private val silkBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 0.9f * dp
    }
    private val silkHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f * dp
        maskFilter = BlurMaskFilter(3.5f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val bgWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * dp
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shockwavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    // Paints cho Linh Vật AI 3D (Neko Spirit)
    private val catOuterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f * dp
        maskFilter = BlurMaskFilter(16f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val catMidGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * dp
        maskFilter = BlurMaskFilter(5f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val catRimSharpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val catBodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val catBounceLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val catEarInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val catFeaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colGoldBright
    }
    private val catEyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colAmber
        maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val catMouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colGoldBright
    }
    private val catWhiskerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val floorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(12f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val floorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND
    }

    private var sweepAccentShader: Shader? = null
    private var orbCombinedShader: Shader? = null
    private var waveRibbonShader: Shader? = null
    private var cx = 0f
    private var cy = 0f
    private var baseR = 0f
    private val shaderMatrix = Matrix()
    private val tempRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        val rng = java.util.Random(42L)
        for (i in 0 until numSparks) {
            sparkTheta[i] = rng.nextFloat() * (2f * PI.toFloat())
            sparkPhi[i] = (rng.nextFloat() - 0.5f) * PI.toFloat()
            sparkRadius[i] = 1.12f + rng.nextFloat() * 0.55f
            sparkSpeed[i] = 0.25f + rng.nextFloat() * 0.55f
            sparkSize[i] = (1.3f + rng.nextFloat() * 2.2f) * dp
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h * 0.50f
        baseR = min(w, h) * 0.31f
        capNhatShaders(w, h)
    }

    private fun capNhatShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || baseR <= 0f) return

        // Quầng hào quang âm bản phía sau
        ambientPaint.shader = RadialGradient(
            cx, cy, baseR * 1.95f,
            intArrayOf(
                Color.argb(68, 255, 90, 20),
                Color.argb(28, 255, 140, 30),
                Color.argb(8, 200, 25, 15),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        // Lõi đen tuyền ở tâm, ấm dần nhẹ ở sát viền ngoài
        coreFillPaint.shader = RadialGradient(
            cx, cy, baseR * 1.08f,
            intArrayOf(
                Color.parseColor("#030203"),
                Color.parseColor("#060304"),
                Color.parseColor("#120605"),
                Color.parseColor("#2A0B07")
            ),
            floatArrayOf(0f, 0.62f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )

        // Shader cho các vòng sợi lụa 3D của Quả cầu:
        // Giữ vùng tâm (0..38%) tối sâu để tạo chiều sâu 3D đen huyền bí,
        // và bùng sáng rực rỡ (Đỏ nhung -> Cam lửa -> Vàng kim -> Trắng vàng) ở các nếp gấp rìa!
        val radialMask = RadialGradient(
            cx, cy, baseR * 1.15f,
            intArrayOf(
                Color.parseColor("#120404"),
                Color.parseColor("#380C08"),
                Color.parseColor("#903018"),
                Color.parseColor("#DF8040"),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#FFFFFF")
            ),
            floatArrayOf(0f, 0.34f, 0.56f, 0.74f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )

        val sweepAccent = SweepGradient(
            cx, cy,
            intArrayOf(
                colGold,
                colOrange,
                colRedOrange,
                colRubyMagenta,
                colRedOrange,
                colAmber,
                colGoldBright,
                colOrange,
                colGold
            ),
            floatArrayOf(0f, 0.14f, 0.28f, 0.42f, 0.56f, 0.70f, 0.82f, 0.92f, 1f)
        )
        sweepAccentShader = sweepAccent

        orbCombinedShader = ComposeShader(radialMask, sweepAccent, PorterDuff.Mode.MULTIPLY)
        waveRibbonShader = LinearGradient(
            0f, cy - baseR * 0.6f, w.toFloat(), cy + baseR * 0.6f,
            intArrayOf(
                colCrimson,
                colRedOrange,
                colOrange,
                colGoldBright,
                colAmber,
                colRedOrange,
                colRubyMagenta
            ),
            floatArrayOf(0f, 0.16f, 0.34f, 0.50f, 0.68f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )
        silkFrontPaint.shader = orbCombinedShader
        silkHighlightPaint.shader = radialMask

        // Lớp tán sắc quang học (Đỏ ruby - Tím hồng - Cam)
        dispersionPaint.shader = RadialGradient(
            cx, cy, baseR * 1.20f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(40, 184, 20, 72),
                Color.argb(155, 225, 35, 30),
                Color.argb(210, 255, 110, 25),
                Color.argb(180, 255, 210, 90)
            ),
            floatArrayOf(0f, 0.38f, 0.68f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )

        coreRimBloomPaint.shader = sweepAccent

        // Shaders cho Linh Vật AI (Neko Spirit)
        val catRimSweep = SweepGradient(
            cx, cy,
            intArrayOf(
                colGoldBright,
                colAmber,
                colRedOrange,
                colRubyMagenta,
                colGold,
                colOrange,
                colRedOrange,
                colGoldBright
            ),
            null
        )
        catOuterGlowPaint.shader = catRimSweep
        catMidGlowPaint.shader = catRimSweep
        catRimSharpPaint.shader = catRimSweep
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val maxD = min(width, height) * 0.45f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                targetTouchX = ((event.x - cx) / maxD).coerceIn(-1f, 1f)
                targetTouchY = ((event.y - cy) / maxD).coerceIn(-1f, 1f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 12f * dp || abs(event.y - downY) > 12f * dp) {
                    moved = true
                }
                targetTouchX = ((event.x - cx) / maxD).coerceIn(-1.2f, 1.2f)
                targetTouchY = ((event.y - cy) / maxD).coerceIn(-1.2f, 1.2f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                targetTouchX = 0f
                targetTouchY = 0f
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        nextStyle()
        return true
    }

    private fun capNhatVatLy() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastMs).coerceIn(0L, 50L) / 1000f
        lastMs = now

        val targetEnergy: Float
        val targetSpeed: Float
        val targetPulse: Float
        when (state) {
            VoiceState.IDLE -> {
                targetEnergy = 0.22f
                targetSpeed = 0.72f
                targetPulse = 0f
            }
            VoiceState.LISTENING -> {
                targetEnergy = 0.92f
                targetSpeed = 1.55f
                targetPulse = 1f
            }
            VoiceState.THINKING -> {
                targetEnergy = 0.75f
                targetSpeed = 2.65f
                targetPulse = 0.45f
            }
            VoiceState.SPEAKING -> {
                targetEnergy = 0.88f
                targetSpeed = 1.85f
                targetPulse = 0.85f
            }
        }

        val k = 1f - exp(-dt * 4.5f)
        energy += (targetEnergy - energy) * k
        speed += (targetSpeed - speed) * k
        pulse += (targetPulse - pulse) * k
        time += dt * speed

        val kt = 1f - exp(-dt * 7f)
        touchX += (targetTouchX - touchX) * kt
        touchY += (targetTouchY - touchY) * kt

        val targetWave = if (style == Style.SILK_WAVE) 1f else 0f
        val targetSpirit = if (style == Style.NEKO_SPIRIT) 1f else 0f
        val km = 1f - exp(-dt * 5.5f)
        waveMorph += (targetWave - waveMorph) * km
        spiritMorph += (targetSpirit - spiritMorph) * km
    }

    override fun onDraw(canvas: Canvas) {
        capNhatVatLy()
        if (baseR <= 0f) return

        // Nếu đang ở chế độ Linh Vật AI (hoặc đang chuyển sang Linh Vật AI)
        if (spiritMorph > 0.01f) {
            if (spiritMorph < 0.99f) {
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), ((1f - spiritMorph) * 255).toInt())
                veMesh3D(canvas)
                canvas.restore()

                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (spiritMorph * 255).toInt())
                veNekoSpirit3D(canvas)
                canvas.restore()
            } else {
                veNekoSpirit3D(canvas)
            }
        } else {
            veMesh3D(canvas)
        }

        postInvalidateOnAnimation()
    }

    // =========================================================================
    // PHONG CÁCH 1 & 2: QUẢ CẦU LỤA 3D ĐA CHIỀU & DẢI SÓNG LỤA 3D XOẮN
    // =========================================================================
    private fun veMesh3D(canvas: Canvas) {
        val w = width.toFloat()
        val breath = 1f + 0.025f * sin(time * 1.6f) + 0.04f * energy * sin(time * 4.8f)
        val r = baseR * breath

        // 1. Quầng sáng không gian sâu phía sau
        canvas.save()
        val haloScale = 1f + 0.15f * energy + 0.25f * waveMorph
        canvas.scale(haloScale, 1f + 0.1f * energy - 0.2f * waveMorph, cx, cy)
        canvas.drawCircle(cx, cy, baseR * 1.95f, ambientPaint)
        canvas.restore()

        // 2. Các sợi sóng âm lụa mảnh chạy ngang phía sau khi đang ở chế độ Quả cầu
        if (waveMorph < 0.95f) {
            veSongNenPhiaSau(canvas, w, r, (1f - waveMorph) * (0.28f + 0.65f * energy))
        }

        // 3. Vòng sóng xung kích 3D (khi đang nghe / nói ở chế độ Orb)
        if (pulse > 0.04f && waveMorph < 0.8f) {
            for (i in 0..2) {
                val p = (time * 0.28f + i / 3f) % 1f
                val ringR = r * (1.06f + 0.68f * p)
                val alpha = ((1f - p).pow(1.7f) * 120f * pulse * (1f - waveMorph)).toInt().coerceIn(0, 255)
                shockwavePaint.color = if (i % 2 == 0) colAmber else colRedOrange
                shockwavePaint.alpha = alpha
                shockwavePaint.strokeWidth = (2.2f - 1.4f * p) * dp
                canvas.drawCircle(cx, cy, ringR, shockwavePaint)
            }
        }

        // 4. Lõi đen tuyền 3D biến dạng theo biên nếp gấp (chỉ hiện ở chế độ Quả cầu để che mặt sau)
        val orbVisibility = (1f - waveMorph).coerceIn(0f, 1f)
        if (orbVisibility > 0.02f) {
            xayDungDuongBaoLoi3D(r)
            coreFillPaint.alpha = (orbVisibility * 252).toInt()
            coreRimBloomPaint.alpha = (orbVisibility * (110 + 90 * energy)).toInt().coerceIn(0, 255)

            shaderMatrix.reset()
            shaderMatrix.setRotate((time * 24f) % 360f, cx, cy)
            coreRimBloomPaint.shader?.setLocalMatrix(shaderMatrix)

            canvas.drawPath(coreSilhouettePath, coreRimBloomPaint)
            canvas.drawPath(coreSilhouettePath, coreFillPaint)
        }

        // 5. Tính toán & Chiếu phối cảnh 3D cho 42 Vòng Sợi Lụa (biến hình mượt giữa Orb 3D và Wave 3D)
        val pitch = 0.48f + touchY * 0.55f + 0.14f * sin(time * 0.45f)
        val yaw = time * 0.38f + touchX * 0.65f
        val roll = 0.22f * cos(time * 0.32f) + touchX * 0.2f

        val cosP = cos(pitch); val sinP = sin(pitch)
        val cosY = cos(yaw);   val sinY = sin(yaw)
        val cosR = cos(roll);  val sinR = sin(roll)

        val fov = 2.85f * baseR
        val halfW = w * 0.48f

        for (i in 0 until numRings) {
            val vNorm = i.toFloat() / (numRings - 1)          // 0..1
            val vSigned = vNorm * 2f - 1f                     // -1..1
            val theta = (0.04f + 0.92f * vNorm) * PI.toFloat() // Vĩ độ trên cầu (tránh suy biến ở cực)
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)

            val path = ringPaths[i]
            path.rewind()

            val dispIdx = if (i % 2 == 0) i / 2 else -1
            val dPath = if (dispIdx >= 0) dispPaths[dispIdx].also { it.rewind() } else null

            var zSum = 0f

            for (j in 0..numPts) {
                val uNorm = j.toFloat() / numPts              // 0..1
                val uSigned = uNorm * 2f - 1f                 // -1..1
                val phi = uNorm * 2f * PI.toFloat()           // Kinh độ 0..2PI

                // --- Tọa độ 3D trên Quả cầu Sóng Lụa (Sculpted 3D Folded Sphere) ---
                // Ba sóng cầu điều hòa tạo các múi lụa uốn lượn đa chiều (như ảnh mẫu Dola)
                val w1 = sin(3f * phi + 2f * theta + time * 1.15f) * cos(2f * theta - phi - time * 0.85f)
                val w2 = cos(5f * phi - 3f * theta - time * 1.45f)
                val w3 = sin(7f * phi + 4f * theta + time * 2.10f)
                val foldAmp = (0.115f * w1 + 0.068f * w2 + 0.032f * w3) * (0.85f + 0.42f * energy)

                val rad = r * (1f + foldAmp * sinTheta)
                // Xoắn nhẹ đường vĩ tuyến theo sóng để các sợi lụa đan quyện vào nhau
                val latWave = 0.065f * r * sin(4f * phi - time * 1.2f + theta * 2f) * sinTheta

                val ox0 = rad * sinTheta * cos(phi)
                val oy0 = rad * cosTheta + latWave
                val oz0 = rad * sinTheta * sin(phi)

                // Xoay 3D (Yaw -> Pitch -> Roll)
                val x1 = ox0 * cosY + oz0 * sinY
                val z1 = -ox0 * sinY + oz0 * cosY
                val y2 = oy0 * cosP - z1 * sinP
                val z2 = oy0 * sinP + z1 * cosP
                val xOrb = x1 * cosR - y2 * sinR
                val yOrb = x1 * sinR + y2 * cosR
                val zOrb = z2

                // --- Tọa độ 3D trên Dải Sóng Lụa Xoắn (3D Twisted Silk Ribbon Wave) ---
                val env = (1f - uSigned * uSigned).coerceAtLeast(0f).pow(0.65f)
                val twist = uSigned * 3.6f + time * 1.35f + touchX * 1.2f
                val spineY = baseR * 0.42f * env * (
                    sin(uSigned * 4.0f - time * 1.55f) +
                    0.48f * cos(uSigned * 7.2f + time * 2.25f)
                ) * (0.72f + 0.55f * energy) + touchY * baseR * 0.25f

                val ribbonHalfW = baseR * 0.44f * (0.28f + 0.72f * env) * (0.82f + 0.35f * energy)
                val crossAngle = twist + vSigned * 0.55f

                val xWave = uSigned * halfW
                val yWave = spineY + vSigned * ribbonHalfW * cos(crossAngle)
                val zWave = vSigned * ribbonHalfW * sin(crossAngle)

                // --- Nội suy 3D mượt giữa Quả Cầu và Dải Sóng ---
                val px3 = xOrb + (xWave - xOrb) * waveMorph
                val py3 = yOrb + (yWave - yOrb) * waveMorph
                val pz3 = zOrb + (zWave - zOrb) * waveMorph

                zSum += pz3

                // Chiếu phối cảnh 3D (Perspective Projection)
                val persp = fov / (fov - pz3).coerceAtLeast(baseR * 0.6f)
                val sx = cx + px3 * persp
                val sy = cy + py3 * persp

                if (j == 0) {
                    path.moveTo(sx, sy)
                } else {
                    path.lineTo(sx, sy)
                }

                // Đường tán sắc (Chromatic Dispersion) lệch pha không gian nhẹ ở các nếp gấp
                if (dPath != null) {
                    val dScale = 1.028f
                    val dsx = cx + (px3 * dScale + 2.2f * dp * sin(phi + time)) * persp
                    val dsy = cy + (py3 * dScale - 1.8f * dp * cos(phi - time)) * persp
                    if (j == 0) dPath.moveTo(dsx, dsy) else dPath.lineTo(dsx, dsy)
                }
            }

            ringZAvg[i] = zSum / (numPts + 1)
            ringOrder[i] = i
        }

        // Sắp xếp thứ tự vẽ các vòng từ xa đến gần (Painter's Algorithm theo độ sâu Z)
        for (a in 0 until numRings - 1) {
            for (b in a + 1 until numRings) {
                if (ringZAvg[ringOrder[a]] > ringZAvg[ringOrder[b]]) {
                    val tmp = ringOrder[a]
                    ringOrder[a] = ringOrder[b]
                    ringOrder[b] = tmp
                }
            }
        }

        // Cập nhật ma trận xoay cho Shader để ánh sáng cam-vàng chạy dọc các nếp gấp
        if (waveMorph > 0.5f) {
            silkFrontPaint.shader = waveRibbonShader
            shaderMatrix.reset()
            shaderMatrix.setTranslate(sin(time * 0.8f) * w * 0.08f, 0f)
            waveRibbonShader?.setLocalMatrix(shaderMatrix)
        } else {
            silkFrontPaint.shader = orbCombinedShader
            shaderMatrix.reset()
            shaderMatrix.setRotate((time * 28f) % 360f, cx, cy)
            sweepAccentShader?.setLocalMatrix(shaderMatrix)
        }

        // 6. Vẽ lớp tán sắc đỏ-ruby-tím phía dưới trước
        for (k in dispPaths.indices) {
            dispersionPaint.alpha = (115 + 75 * energy).toInt().coerceIn(0, 255)
            canvas.drawPath(dispPaths[k], dispersionPaint)
        }

        // 7. Vẽ 42 vòng sợi lụa 3D theo chiều sâu Z
        for (idx in 0 until numRings) {
            val rIdx = ringOrder[idx]
            val zNorm = (ringZAvg[rIdx] / baseR).coerceIn(-1f, 1f)
            // Độ sáng theo chiều sâu Z: mặt trước sáng sắc nét, mặt sau chìm vào bóng tối của lõi đen
            val depthFactor = ((zNorm + 1f) * 0.5f).pow(1.35f)

            if (waveMorph > 0.5f) {
                // Chế độ Dải sóng lụa 3D: Mọi sợi đều trong trẻo tạo hiệu ứng lưới lụa 3D
                val edgeHighlight = abs(rIdx.toFloat() / (numRings - 1) * 2f - 1f).pow(1.4f)
                val alpha = (65 + 165 * (0.45f * depthFactor + 0.55f * edgeHighlight)).toInt().coerceIn(35, 250)
                silkFrontPaint.alpha = alpha
                canvas.drawPath(ringPaths[rIdx], silkFrontPaint)
            } else {
                // Chế độ Quả cầu 3D: Mặt sau mờ nhẹ, mặt trước và các rìa nếp gấp rực sáng
                if (zNorm < -0.15f) {
                    silkBackPaint.color = colCrimson
                    silkBackPaint.alpha = (22 + 30 * (zNorm + 1f)).toInt().coerceIn(10, 55)
                    canvas.drawPath(ringPaths[rIdx], silkBackPaint)
                } else {
                    val alpha = (85 + 170 * depthFactor).toInt().coerceIn(60, 255)
                    silkFrontPaint.alpha = alpha
                    canvas.drawPath(ringPaths[rIdx], silkFrontPaint)

                    // Mỗi 5 vòng thêm một sợi phát sáng bloom mạnh để tạo đỉnh sáng hoàng kim
                    if (rIdx % 5 == 0) {
                        silkHighlightPaint.alpha = (70 + 110 * depthFactor * (0.6f + 0.4f * energy)).toInt().coerceIn(0, 220)
                        canvas.drawPath(ringPaths[rIdx], silkHighlightPaint)
                    }
                }
            }
        }

        // 8. Các hạt bụi năng lượng 3D bay theo quỹ đạo quanh tâm
        veHatNangLuong3D(canvas, r, fov)
    }

    /**
     * Xây dựng đường bao lõi đen 3D uốn lượn khớp với biên ngoài của quả cầu sóng lụa.
     */
    private fun xayDungDuongBaoLoi3D(r: Float) {
        coreSilhouettePath.rewind()
        val steps = 96
        val twoPi = 2f * PI.toFloat()
        for (i in 0..steps) {
            val a = twoPi * i / steps
            val lobe1 = sin(3f * a + time * 1.15f) * cos(2f * a - time * 0.85f)
            val lobe2 = cos(5f * a - time * 1.45f)
            val lobe3 = sin(7f * a + time * 2.10f)
            val disp = (0.095f * lobe1 + 0.052f * lobe2 + 0.025f * lobe3) * (0.85f + 0.42f * energy)
            val rad = r * (0.98f + disp)
            val x = cx + rad * cos(a)
            val y = cy + rad * sin(a)
            if (i == 0) coreSilhouettePath.moveTo(x, y) else coreSilhouettePath.lineTo(x, y)
        }
        coreSilhouettePath.close()
    }

    private fun veSongNenPhiaSau(canvas: Canvas, w: Float, r: Float, intensity: Float) {
        if (intensity <= 0.02f) return
        val lines = 7
        val steps = 48
        for (i in 0 until lines) {
            val f = i.toFloat() / (lines - 1) - 0.5f
            bgWavePath.rewind()
            for (s in 0..steps) {
                val u = s.toFloat() / steps
                val x = u * w
                val xn = u * 2f - 1f
                val env = (1f - xn * xn).coerceAtLeast(0f)
                val y = cy + env * (
                    sin(xn * 4.5f - time * 1.8f + i * 0.55f) * r * 0.28f * (0.5f + 0.7f * energy) +
                    f * r * 0.42f * cos(xn * 3.0f + time * 1.1f)
                )
                if (s == 0) bgWavePath.moveTo(x, y) else bgWavePath.lineTo(x, y)
            }
            bgWavePaint.color = when (i % 3) {
                0 -> colRedOrange
                1 -> colOrange
                else -> colGold
            }
            bgWavePaint.alpha = (intensity * (55 + 45 * (1f - abs(f * 2f)))).toInt().coerceIn(0, 140)
            canvas.drawPath(bgWavePath, bgWavePaint)
        }
    }

    private fun veHatNangLuong3D(canvas: Canvas, r: Float, fov: Float) {
        for (i in 0 until numSparks) {
            val th = sparkTheta[i] + time * sparkSpeed[i]
            val ph = sparkPhi[i] + 0.35f * sin(time * 0.7f + i)
            val dist = r * sparkRadius[i] * (1f + 0.12f * energy * sin(time * 2f + i))

            val x3 = dist * cos(ph) * cos(th)
            val y3 = dist * sin(ph) * (1f - 0.45f * waveMorph)
            val z3 = dist * cos(ph) * sin(th)

            val persp = fov / (fov - z3).coerceAtLeast(baseR * 0.5f)
            val sx = cx + x3 * persp * (1f + 0.35f * waveMorph)
            val sy = cy + y3 * persp

            val depthAlpha = ((z3 / dist + 1f) * 0.5f).coerceIn(0.15f, 1f)
            val twinkle = 0.55f + 0.45f * sin(time * 3.8f + i * 1.7f)
            sparkPaint.color = if (i % 3 == 0) colGoldBright else if (i % 3 == 1) colAmber else colRedOrange
            sparkPaint.alpha = (depthAlpha * twinkle * (110 + 120 * energy)).toInt().coerceIn(0, 240)
            canvas.drawCircle(sx, sy, sparkSize[i] * persp * 0.85f, sparkPaint)
        }
    }

    // =========================================================================
    // PHONG CÁCH 3: LINH VẬT AI ĐEN TUYỀN VIỀN SÁNG 3D (OBSIDIAN NEKO SPIRIT)
    // =========================================================================
    private fun veNekoSpirit3D(canvas: Canvas) {
        val breath = sin(time * 2.0f)
        val bounce = abs(sin(time * 2.8f)) * energy * 6f * dp
        val headTilt = touchX * 8f + sin(time * 0.9f) * 2.5f +
            if (state == VoiceState.THINKING) 5f * sin(time * 1.5f) else 0f

        val r = baseR * 0.86f
        val floorY = cy + r * 0.78f

        // 1. Quầng sáng nền phía sau linh vật
        canvas.drawCircle(cx, cy + r * 0.1f, baseR * 1.65f, ambientPaint)

        // 2. Đường chân trời phát sáng (Glowing Horizon Line & Floor Reflection như Ảnh 2)
        val floorHalfW = r * (1.55f + 0.35f * energy)
        floorGlowPaint.shader = RadialGradient(
            cx, floorY, floorHalfW,
            intArrayOf(
                Color.argb(180, 255, 226, 122),
                Color.argb(130, 255, 136, 27),
                Color.argb(65, 229, 46, 16),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        tempRect.set(cx - floorHalfW, floorY - 10f * dp, cx + floorHalfW, floorY + 14f * dp)
        canvas.drawOval(tempRect, floorGlowPaint)

        floorLinePaint.shader = LinearGradient(
            cx - floorHalfW, floorY, cx + floorHalfW, floorY,
            intArrayOf(
                Color.TRANSPARENT,
                colCrimson,
                colOrange,
                colGoldBright,
                colGoldBright,
                colOrange,
                colCrimson,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.16f, 0.32f, 0.46f, 0.54f, 0.68f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(cx - floorHalfW, floorY, cx + floorHalfW, floorY, floorLinePaint)

        // Sóng âm lan trên mặt gương dưới sàn khi nghe / nói
        if (pulse > 0.05f) {
            for (i in 0..2) {
                val p = (time * 0.35f + i / 3f) % 1f
                val rw = r * (0.95f + 0.85f * p)
                val rh = r * (0.10f + 0.12f * p)
                shockwavePaint.color = if (i % 2 == 0) colGold else colOrange
                shockwavePaint.alpha = ((1f - p).pow(1.5f) * 140f * pulse).toInt().coerceIn(0, 255)
                shockwavePaint.strokeWidth = (2.0f - 1.2f * p) * dp
                tempRect.set(cx - rw, floorY - rh * 0.5f, cx + rw, floorY + rh * 0.5f)
                canvas.drawOval(tempRect, shockwavePaint)
            }
        }

        // 3. Xây dựng hình dáng 3D của Linh vật Mèo AI (Mochi Neko)
        val squishX = 1f + 0.025f * breath + 0.04f * energy * cos(time * 5f)
        val squishY = 1f - 0.022f * breath + 0.04f * energy * sin(time * 5f)
        val w = r * 0.96f * squishX
        val h = r * 1.12f * squishY
        val bottom = floorY - bounce * 0.3f
        val top = bottom - h * 1.22f

        // Nhịp vểnh tai khi đang nghe
        val earPerk = if (state == VoiceState.LISTENING) 0.10f * r * abs(sin(time * 4f)) else 0.03f * r * sin(time * 1.8f)

        catBodyPath.rewind()
        // Đáy phẳng mềm ngồi trên mặt gương
        catBodyPath.moveTo(cx - w * 0.68f, bottom)
        catBodyPath.cubicTo(
            cx - w * 0.25f, bottom + r * 0.03f,
            cx + w * 0.25f, bottom + r * 0.03f,
            cx + w * 0.68f, bottom
        )
        // Má phải bầu bĩnh lên tai phải
        catBodyPath.cubicTo(
            cx + w * 1.05f, bottom - h * 0.12f,
            cx + w * 1.02f, bottom - h * 0.72f,
            cx + w * 0.68f, bottom - h * 0.96f
        )
        // Đỉnh tai phải nhọn mềm
        val earRx = cx + w * 0.54f + headTilt * 0.6f * dp
        val earRy = top - earPerk
        catBodyPath.cubicTo(
            cx + w * 0.65f, top + h * 0.05f,
            earRx + w * 0.06f, earRy,
            earRx, earRy
        )
        catBodyPath.cubicTo(
            earRx - w * 0.08f, earRy,
            cx + w * 0.32f, bottom - h * 1.08f,
            cx + w * 0.22f, bottom - h * 1.06f
        )
        // Đỉnh đầu hơi cong giữa hai tai
        catBodyPath.cubicTo(
            cx + w * 0.08f, bottom - h * 1.10f,
            cx - w * 0.08f, bottom - h * 1.10f,
            cx - w * 0.22f, bottom - h * 1.06f
        )
        // Đỉnh tai trái
        val earLx = cx - w * 0.54f + headTilt * 0.6f * dp
        val earLy = top - earPerk * 0.85f
        catBodyPath.cubicTo(
            cx - w * 0.32f, bottom - h * 1.08f,
            earLx + w * 0.08f, earLy,
            earLx, earLy
        )
        catBodyPath.cubicTo(
            earLx - w * 0.06f, earLy,
            cx - w * 0.65f, top + h * 0.05f,
            cx - w * 0.68f, bottom - h * 0.96f
        )
        // Má trái bầu bĩnh xuống đáy
        catBodyPath.cubicTo(
            cx - w * 1.02f, bottom - h * 0.72f,
            cx - w * 1.05f, bottom - h * 0.12f,
            cx - w * 0.68f, bottom
        )
        catBodyPath.close()

        // Xoay nhẹ toàn bộ màu viền theo thời gian để hiệu ứng viền neon sống động
        shaderMatrix.reset()
        shaderMatrix.setRotate((time * 32f) % 360f, cx, cy)
        catOuterGlowPaint.shader?.setLocalMatrix(shaderMatrix)
        catMidGlowPaint.shader?.setLocalMatrix(shaderMatrix)
        catRimSharpPaint.shader?.setLocalMatrix(shaderMatrix)

        // 4. Vẽ phản chiếu mờ ngược dưới mặt sàn gương
        canvas.save()
        canvas.scale(1f, -0.22f, cx, floorY)
        catOuterGlowPaint.alpha = 75
        canvas.drawPath(catBodyPath, catOuterGlowPaint)
        canvas.restore()
        catOuterGlowPaint.alpha = 255

        // 5. Vẽ hào quang viền ngoài + thân đen tuyền + ánh sáng hắt từ sàn lên bụng (Bounce Light)
        canvas.drawPath(catBodyPath, catOuterGlowPaint)
        canvas.drawPath(catBodyPath, catMidGlowPaint)

        catBodyFillPaint.color = colObsidian
        canvas.drawPath(catBodyPath, catBodyFillPaint)

        // Ánh sáng cam-vàng ấm áp hắt từ đường chân trời lên phần đáy thân mèo (như Ảnh 2)
        catBounceLightPaint.shader = RadialGradient(
            cx, bottom + r * 0.1f, w * 0.95f,
            intArrayOf(
                Color.argb(135, 255, 190, 95),
                Color.argb(75, 255, 105, 25),
                Color.argb(25, 200, 25, 45),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(catBodyPath, catBounceLightPaint)

        // Lòng tai ấm áp
        catEarInnerPaint.shader = LinearGradient(
            cx, earRy, cx, bottom - h * 0.85f,
            Color.argb(150, 255, 165, 110),
            Color.argb(20, 40, 10, 15),
            Shader.TileMode.CLAMP
        )
        catEarLeftInner.rewind()
        catEarLeftInner.moveTo(earLx + w * 0.02f, earLy + h * 0.08f)
        catEarLeftInner.lineTo(cx - w * 0.60f, bottom - h * 0.92f)
        catEarLeftInner.lineTo(cx - w * 0.28f, bottom - h * 1.00f)
        catEarLeftInner.close()
        canvas.drawPath(catEarLeftInner, catEarInnerPaint)

        catEarRightInner.rewind()
        catEarRightInner.moveTo(earRx - w * 0.02f, earRy + h * 0.08f)
        catEarRightInner.lineTo(cx + w * 0.28f, bottom - h * 1.00f)
        catEarRightInner.lineTo(cx + w * 0.60f, bottom - h * 0.92f)
        catEarRightInner.close()
        canvas.drawPath(catEarRightInner, catEarInnerPaint)

        // Viền neon sắc nét bao quanh thân và tai
        canvas.drawPath(catBodyPath, catRimSharpPaint)

        // 6. Vẽ khuôn mặt phát sáng biểu cảm (Mắt, Miệng :3, Râu phát sáng)
        val faceCenterX = cx + touchX * r * 0.14f
        val faceCenterY = bottom - h * 0.68f + touchY * r * 0.10f - bounce * 0.4f
        val eyeOffsetX = w * 0.30f
        val eyeR = r * (0.072f + 0.012f * energy)

        // Chớp mắt tự nhiên mỗi ~3.6 giây
        val blinkCycle = (time * 0.85f) % 3.6f
        val blinkScaleY = if (blinkCycle > 3.42f) {
            val b = (blinkCycle - 3.42f) / 0.18f
            abs(b * 2f - 1f).coerceIn(0.08f, 1f)
        } else 1f

        canvas.save()
        canvas.rotate(headTilt * 0.8f, faceCenterX, faceCenterY)

        // Mắt trái & Mắt phải
        val leftEyeX = faceCenterX - eyeOffsetX
        val rightEyeX = faceCenterX + eyeOffsetX
        val eyeY = faceCenterY

        canvas.drawCircle(leftEyeX, eyeY, eyeR * 1.6f, catEyeGlowPaint)
        canvas.drawCircle(rightEyeX, eyeY, eyeR * 1.6f, catEyeGlowPaint)

        canvas.save()
        canvas.scale(1f, blinkScaleY, faceCenterX, eyeY)
        catFeaturePaint.color = colGoldBright
        canvas.drawCircle(leftEyeX, eyeY, eyeR, catFeaturePaint)
        canvas.drawCircle(rightEyeX, eyeY, eyeR, catFeaturePaint)
        canvas.restore()

        // Miệng ":3" (ω) dễ thương co giãn theo nhịp nói
        val mouthY = faceCenterY + r * 0.045f
        val mouthW = r * 0.085f
        val mouthH = r * (0.042f + if (state == VoiceState.SPEAKING) 0.045f * abs(sin(time * 9f)) else 0f)
        mouthPath.rewind()
        mouthPath.moveTo(faceCenterX - mouthW, mouthY)
        mouthPath.quadTo(faceCenterX - mouthW * 0.5f, mouthY + mouthH, faceCenterX, mouthY + mouthH * 0.2f)
        mouthPath.quadTo(faceCenterX + mouthW * 0.5f, mouthY + mouthH, faceCenterX + mouthW, mouthY)
        canvas.drawPath(mouthPath, catMouthPaint)

        // 3 sợi râu phát sáng mỗi bên má
        for (k in -1..1) {
            val wy0 = faceCenterY + r * 0.08f + k * r * 0.045f
            val wy1 = wy0 + k * r * 0.035f + sin(time * 3f + k) * 2f * dp

            // Râu trái
            val lx0 = cx - w * 0.72f
            val lx1 = cx - w * 1.25f
            catWhiskerPaint.shader = LinearGradient(
                lx0, wy0, lx1, wy1,
                Color.argb(210, 255, 226, 122), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            whiskerPath.rewind()
            whiskerPath.moveTo(lx0, wy0)
            whiskerPath.quadTo((lx0 + lx1) * 0.5f, wy0 - r * 0.02f, lx1, wy1)
            canvas.drawPath(whiskerPath, catWhiskerPaint)

            // Râu phải
            val rx0 = cx + w * 0.72f
            val rx1 = cx + w * 1.25f
            catWhiskerPaint.shader = LinearGradient(
                rx0, wy0, rx1, wy1,
                Color.argb(210, 255, 226, 122), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            whiskerPath.rewind()
            whiskerPath.moveTo(rx0, wy0)
            whiskerPath.quadTo((rx0 + rx1) * 0.5f, wy0 - r * 0.02f, rx1, wy1)
            canvas.drawPath(whiskerPath, catWhiskerPaint)
        }

        canvas.restore()

        // 7. Các hạt đom đóm lửa bay lơ lửng quanh linh vật
        veHatNangLuong3D(canvas, r * 0.95f, 2.85f * baseR)
    }
}
