package com.tuanvu.voiceassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Nút micro: vòng viền vàng cam phát sáng, lõi tối ở giữa, icon mic.
 *
 * Chuyển động: dải màu chạy quanh viền, quầng sáng "thở"; khi nghe / nói có hai sợi sóng
 * mảnh chạy dọc viền và các vòng sóng lan ra; icon đổi màu theo đúng màu đang ở đỉnh viền.
 *
 * Hai kiểu vẽ (đổi bằng KIEU_PIXEL ở cuối file):
 *  - Pixel: vẽ lên một lưới điểm ảnh nhỏ rồi phóng lên không làm mịn (nhìn ra từng ô vuông).
 *  - Mượt: vòng + quầng sáng vẽ sẵn một lần có làm mờ, mỗi khung chỉ xoay ảnh.
 */
class MicButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    /** Trạng thái trợ lý; quyết định tốc độ quay, độ sóng và màu icon. */
    var state: VoiceState = VoiceState.IDLE

    private val dp = resources.displayMetrics.density
    private val ringW = 6f * dp
    private val outerR = 56f * dp          // bán kính ngoài của viền (cố định)

    // ---- Bảng màu của viền (cũng dùng để tính màu icon) ----
    private val stopColors = intArrayOf(
        Color.parseColor("#FFE27A"),
        Color.parseColor("#FF9A1F"),
        Color.parseColor("#FF4D1A"),
        Color.parseColor("#FFB62E"),
        Color.parseColor("#FFE27A")
    )
    private val stopPos = floatArrayOf(0f, 0.28f, 0.52f, 0.78f, 1f)
    private val white = Color.parseColor("#FFF6F0")

    // =========================================================================
    // Kiểu MƯỢT
    // =========================================================================
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW
    }
    private val halo1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW * 2f
        alpha = 150
        maskFilter = BlurMaskFilter(7f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val halo2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW * 3f
        alpha = 80
        maskFilter = BlurMaskFilter(14f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val glowBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rippleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val waveP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * dp
        strokeJoin = Paint.Join.ROUND
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 255, 255)
    }
    private val wavePath = Path()
    private var ringBitmap: Bitmap? = null

    // =========================================================================
    // Kiểu PIXEL (không khử răng cưa, phóng lên bằng nearest-neighbor)
    // =========================================================================
    private fun pixelPaint(style: Paint.Style) = Paint().apply {
        isAntiAlias = false
        isDither = false
        this.style = style
    }
    private val pxRing = pixelPaint(Paint.Style.STROKE)
    private val pxHalo1 = pixelPaint(Paint.Style.STROKE)
    private val pxHalo2 = pixelPaint(Paint.Style.STROKE)
    private val pxRipple = pixelPaint(Paint.Style.STROKE)
    private val pxWave = pixelPaint(Paint.Style.STROKE)
    private val pxDisc = pixelPaint(Paint.Style.FILL)
    private val pxInner = pixelPaint(Paint.Style.FILL)
    private val pxPress = pixelPaint(Paint.Style.FILL).apply { color = Color.argb(40, 255, 255, 255) }
    private val pxIcon = pixelPaint(Paint.Style.FILL)
    private val pxBlit = Paint().apply {          // phóng ảnh nhỏ lên: không lọc mịn
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private var pxBitmap: Bitmap? = null
    private var pxCanvas: Canvas? = null
    private var pxN = 0                // số ô mỗi cạnh (số lẻ để có ô ở chính giữa)
    private var pxCellPx = 8           // một ô = bao nhiêu pixel thật (số nguyên -> ô đều nhau)
    private val pxDst = RectF()
    private val pxPath = Path()

    // Icon mic pixel (xem PixelArt)
    private val micArt = PixelArt.MIC

    // =========================================================================
    // Hình học chung
    // =========================================================================
    private var cx = 0f
    private var cy = 0f
    private var ringR = 0f   // bán kính đường giữa của viền (kiểu mượt)
    private var discR = 0f   // bán kính lõi tối (kiểu mượt)

    // ---- Trạng thái chuyển động, được làm mượt ----
    private var lastMs = SystemClock.uptimeMillis()
    private var t = 0f
    private var angle = 0f          // độ
    private var omega = 14f         // độ / giây
    private var breathAmp = 0.012f
    private var breathHz = 0.3f
    private var breathPhase = 0f
    private var active = 0f         // 0 = yên, 1 = đang nghe: bật sóng và vòng lan
    private var iconMix = 0f        // 0 = icon trắng, 1 = icon theo màu vòng

    init {
        background = null
        setColorFilter(white, PorterDuff.Mode.SRC_IN)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (KIEU_PIXEL) taoPixel(w, h) else taoMuot(w, h)
    }

    private fun taoMuot(w: Int, h: Int) {
        cx = w / 2f
        cy = h / 2f
        ringR = outerR - ringW / 2f
        discR = outerR - ringW

        val sweep = SweepGradient(cx, cy, stopColors, stopPos)
        ringPaint.shader = sweep
        halo1.shader = sweep
        halo2.shader = sweep
        rippleP.shader = sweep
        waveP.shader = sweep

        ringBitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(cx, cy, ringR, halo2)
        c.drawCircle(cx, cy, ringR, halo1)
        c.drawCircle(cx, cy, ringR, ringPaint)
        ringBitmap = bmp

        discPaint.shader = RadialGradient(
            cx, cy - discR * 0.15f, discR * 1.2f,
            intArrayOf(Color.parseColor("#0A0908"), Color.parseColor("#14110F")),
            null, Shader.TileMode.CLAMP
        )
        innerGlow.shader = RadialGradient(
            cx, cy, discR,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(56, 255, 138, 26)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun taoPixel(w: Int, h: Int) {
        // Cỡ ô dùng chung với mọi nút (PixelStyle), là số nguyên pixel để mọi ô to bằng nhau
        pxCellPx = PixelStyle.cellPx(dp)
        var n = minOf(w, h) / pxCellPx
        if (n % 2 == 0) n -= 1                       // số lẻ: có ô nằm chính giữa
        pxN = n
        val side = n * pxCellPx.toFloat()
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        pxDst.set(left, top, left + side, top + side)

        pxBitmap?.recycle()
        pxBitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        pxCanvas = Canvas(pxBitmap!!)

        val c = n / 2f                                // tâm lưới (giữa ô chính giữa)
        val outer = outerR / pxCellPx                 // bán kính ngoài của viền, đơn vị ô
        val ringWpx = 3f                              // viền dày 3 ô
        val sweep = SweepGradient(c, c, stopColors, stopPos)
        pxRing.shader = sweep
        pxHalo1.shader = sweep
        pxHalo2.shader = sweep
        pxRipple.shader = sweep
        pxWave.shader = sweep
        pxRing.strokeWidth = ringWpx
        pxHalo1.strokeWidth = ringWpx * 2.2f
        pxHalo2.strokeWidth = ringWpx * 3.6f
        pxRipple.strokeWidth = 1f
        pxWave.strokeWidth = 1f

        val disc = outer - ringWpx
        pxDisc.shader = RadialGradient(
            c, c - disc * 0.15f, disc * 1.2f,
            intArrayOf(Color.parseColor("#0A0908"), Color.parseColor("#14110F")),
            null, Shader.TileMode.CLAMP
        )
        pxInner.shader = RadialGradient(
            c, c, disc,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(70, 255, 138, 26)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        ringBitmap?.recycle()
        ringBitmap = null
        pxBitmap?.recycle()
        pxBitmap = null
        pxCanvas = null
        super.onDetachedFromWindow()
    }

    /** Màu của viền tại vị trí tỉ lệ f (0..1) dọc theo dải màu. */
    private fun mauTaiViTri(f: Float): Int {
        var i = 0
        while (i < stopPos.size - 2 && f > stopPos[i + 1]) i++
        val u = ((f - stopPos[i]) / (stopPos[i + 1] - stopPos[i])).coerceIn(0f, 1f)
        return lerpColor(stopColors[i], stopColors[i + 1], u)
    }

    private fun lerpColor(a: Int, b: Int, u: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt()
    )

    /** Cập nhật thời gian, góc quay, mức hoạt động; trả về nhịp thở (-1..1). */
    private fun capNhat(): Float {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastMs).coerceIn(0L, 50L) / 1000f
        lastMs = now
        t += dt

        val omegaT: Float
        val ampT: Float
        val hzT: Float
        val activeT: Float
        val iconT: Float
        when (state) {
            VoiceState.IDLE -> { omegaT = 14f; ampT = 0.012f; hzT = 0.3f; activeT = 0f; iconT = 0f }
            VoiceState.LISTENING -> { omegaT = 38f; ampT = 0.035f; hzT = 0.7f; activeT = 1f; iconT = 1f }
            VoiceState.THINKING -> { omegaT = 70f; ampT = 0.018f; hzT = 0.5f; activeT = 0.35f; iconT = 0.7f }
            VoiceState.SPEAKING -> { omegaT = 28f; ampT = 0.03f; hzT = 0.55f; activeT = 0.75f; iconT = 1f }
        }
        val k = 1f - exp(-dt * 2.5f)
        omega += (omegaT - omega) * k
        breathAmp += (ampT - breathAmp) * k
        breathHz += (hzT - breathHz) * k
        active += (activeT - active) * k
        iconMix += (iconT - iconMix) * k

        angle = (angle + omega * dt) % 360f
        val twoPi = 2f * PI.toFloat()
        breathPhase = (breathPhase + dt * breathHz * twoPi) % twoPi
        return sin(breathPhase)
    }

    /** Màu icon: lấy màu của viền ngay phía trên đỉnh; viền quay thì icon đổi màu theo. */
    private fun mauIcon(): Int {
        val f = (((270f - angle) % 360f) + 360f) % 360f / 360f
        val mauVong = lerpColor(mauTaiViTri(f), white, 0.18f)
        return lerpColor(white, mauVong, iconMix)
    }

    override fun onDraw(canvas: Canvas) {
        val breath = capNhat()
        if (KIEU_PIXEL) vePixel(canvas, breath) else veMuot(canvas, breath)
        postInvalidateOnAnimation()
    }

    // -------------------------------------------------------------------------
    // Kiểu PIXEL
    // -------------------------------------------------------------------------
    private fun vePixel(canvas: Canvas, breath: Float) {
        val bmp = pxBitmap ?: return
        val c = pxCanvas ?: return
        val n = pxN
        val m = n / 2f
        val outer = outerR / pxCellPx
        val ringWpx = 3f
        val ringRpx = outer - ringWpx / 2f
        val discRpx = outer - ringWpx
        val twoPi = 2f * PI.toFloat()

        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Quầng sáng thở bằng độ đậm (không đổi kích thước để lưới pixel khỏi nhấp nháy)
        pxHalo1.alpha = (85 + 25 * breath).toInt().coerceIn(0, 255)
        pxHalo2.alpha = (42 + 14 * breath).toInt().coerceIn(0, 255)

        c.save()
        c.rotate(angle, m, m)
        c.drawCircle(m, m, ringRpx, pxHalo2)
        c.drawCircle(m, m, ringRpx, pxHalo1)
        c.drawCircle(m, m, ringRpx, pxRing)

        if (active > 0.02f) {
            // Vòng sóng lan ra ngoài (mỗi vòng dày 1 ô)
            for (i in 0..2) {
                val p = (t * 0.22f + i / 3f) % 1f
                val r = ringRpx + 9f * p
                pxRipple.alpha = ((1f - p).pow(1.6f) * 200f * active).toInt().coerceIn(0, 255)
                c.drawCircle(m, m, r, pxRipple)
            }
            // Hai sợi sóng chạy dọc viền
            songPixel(c, m, ringRpx, 6, 0.8f, 1.5f * active, 230)
            songPixel(c, m, ringRpx, 9, -0.6f, 1.0f * active, 150)
        }
        c.restore()

        // Lõi tối + ánh cam từ viền vào
        c.drawCircle(m, m, discRpx, pxDisc)
        c.drawCircle(m, m, discRpx, pxInner)
        if (isPressed) c.drawCircle(m, m, discRpx, pxPress)

        // Icon mic pixel, đổi màu theo viền
        pxIcon.color = mauIcon()
        val artW = micArt[0].length
        val artH = micArt.size
        val x0 = (n / 2) - artW / 2
        val y0 = (n / 2) - artH / 2
        for (row in 0 until artH) {
            for (col in 0 until artW) {
                if (micArt[row][col] == 'X') {
                    c.drawRect(
                        (x0 + col).toFloat(), (y0 + row).toFloat(),
                        (x0 + col + 1).toFloat(), (y0 + row + 1).toFloat(), pxIcon
                    )
                }
            }
        }

        // Phóng lưới nhỏ lên toàn nút, không làm mịn -> thấy từng ô vuông
        canvas.drawBitmap(bmp, null, pxDst, pxBlit)
    }

    private fun songPixel(c: Canvas, m: Float, ringRpx: Float, soSong: Int, tocDo: Float, bienDo: Float, alpha: Int) {
        val n = 160
        val twoPi = 2f * PI.toFloat()
        pxPath.rewind()
        for (i in 0..n) {
            val th = twoPi * i / n
            val r = ringRpx + bienDo * sin(soSong * th - tocDo * t * 2f)
            val x = m + r * cos(th)
            val y = m + r * sin(th)
            if (i == 0) pxPath.moveTo(x, y) else pxPath.lineTo(x, y)
        }
        pxPath.close()
        pxWave.alpha = alpha
        c.drawPath(pxPath, pxWave)
    }

    // -------------------------------------------------------------------------
    // Kiểu MƯỢT
    // -------------------------------------------------------------------------
    private fun veMuot(canvas: Canvas, breath: Float) {
        val scale = 1f + breathAmp * breath
        glowBitmapPaint.alpha = (235 + 20 * breath).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.rotate(angle, cx, cy)
        ringBitmap?.let { canvas.drawBitmap(it, 0f, 0f, glowBitmapPaint) }

        if (active > 0.02f) {
            for (i in 0..2) {
                val p = (t * 0.22f + i / 3f) % 1f
                val r = ringR + 26f * dp * p
                rippleP.alpha = ((1f - p).pow(1.6f) * 150f * active).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, r, rippleP)
            }
            tuongSong(canvas, 6, 0.8f, 3.2f * dp * active, 190)
            tuongSong(canvas, 9, -0.6f, 2.2f * dp * active, 120)
        }
        canvas.restore()

        canvas.drawCircle(cx, cy, discR, discPaint)
        canvas.drawCircle(cx, cy, discR, innerGlow)
        if (isPressed) canvas.drawCircle(cx, cy, discR, pressPaint)

        drawable?.colorFilter = PorterDuffColorFilter(mauIcon(), PorterDuff.Mode.SRC_IN)
        super.onDraw(canvas)   // vẽ icon mic ở giữa
    }

    private fun tuongSong(canvas: Canvas, soSong: Int, tocDo: Float, bienDo: Float, alpha: Int) {
        val n = 120
        val twoPi = 2f * PI.toFloat()
        wavePath.rewind()
        for (i in 0..n) {
            val th = twoPi * i / n
            val r = ringR + bienDo * sin(soSong * th - tocDo * t * 2f)
            val x = cx + r * cos(th)
            val y = cy + r * sin(th)
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        wavePath.close()
        waveP.alpha = alpha
        canvas.drawPath(wavePath, waveP)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    companion object {
        /** true: kiểu pixel (ô vuông); false: kiểu mượt (làm mờ). */
        private const val KIEU_PIXEL = false
    }
}
