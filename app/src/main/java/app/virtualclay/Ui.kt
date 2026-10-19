package app.virtualclay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object C {
    const val BG = 0xFF0A0A0A.toInt()
    const val CARD = 0xFF161616.toInt()
    const val CARD2 = 0xFF1F1F1F.toInt()
    const val RED = 0xFFE10A1F.toInt()
    const val RED_D = 0xFF8E0612.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GRAY = 0xFF9A9A9A.toInt()
}

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

fun rounded(color: Int, r: Float, stroke: Int = 0, sw: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = r
    if (stroke != 0) setStroke(sw, stroke)
}

fun ripple(base: android.graphics.drawable.Drawable) =
    RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), base, null)

fun Context.label(text: String, size: Float = 15f, color: Int = C.WHITE, bold: Boolean = false, center: Boolean = false) =
    TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER
    }

fun Context.redButton(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = 16f; setTextColor(C.WHITE); setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER; setPadding(dp(24), dp(14), dp(24), dp(14)); letterSpacing = 0.05f
    background = ripple(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.RED, C.RED_D)).apply { cornerRadius = dp(14).toFloat() })
    setOnClickListener { onClick() }
}

fun Context.ghostButton(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = 13f; setTextColor(C.WHITE); gravity = Gravity.CENTER
    setPadding(dp(14), dp(9), dp(14), dp(9))
    background = ripple(rounded(C.CARD2, dp(12).toFloat(), 0x33FFFFFF, 1))
    setOnClickListener { onClick() }
}

fun vbox(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
fun hbox(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

/** Анимированная маска Clay: дыхание, покачивание, красное свечение. Используется на всех экранах. */
class MaskView(c: Context) : View(c) {
    private val bmp = BitmapFactory.decodeResource(resources, R.drawable.mask)
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var t = 0f

    /** true — маска вращается вокруг вертикальной оси (экран загрузки) */
    var spin = false
    var glowOn = true

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!anim.isStarted) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = min(w, h) * 0.75f
        pGlow.shader = RadialGradient(w / 2f, h / 2f, r, intArrayOf(0xFFE10A1F.toInt(), 0x00E10A1F), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val a = t * 2f * PI.toFloat()
        val pulse = 0.5f + 0.5f * sin(a)
        if (glowOn) { pGlow.alpha = (55 + 110 * pulse).toInt(); cv.drawCircle(w / 2, h / 2, min(w, h) * 0.75f, pGlow) }
        val s = min(w / bmp.width, h / bmp.height) * 0.86f * (1f + 0.035f * pulse)
        val sx = if (spin) (0.18f + 0.82f * kotlin.math.abs(cos(a))) else 1f
        m.reset()
        m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        m.postScale(s * sx, s)
        m.postRotate(if (spin) 0f else sin(a * 2f) * 3.2f)
        m.postTranslate(w / 2f, h / 2f + (if (spin) 0f else sin(a) * h * 0.022f))
        cv.drawBitmap(bmp, m, pBmp)
    }

    fun shake() {
        ObjectAnimator.ofFloat(this, "translationX", 0f, -26f, 26f, -20f, 20f, -10f, 10f, 0f).setDuration(480).start()
    }

    fun pop() {
        scaleX = 0.2f; scaleY = 0.2f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(700)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()
    }
}

/** Красная полоска-прогресс для сплэша. */
class BarView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var progress = 0f
        set(v) { field = v; invalidate() }
    override fun onDraw(cv: Canvas) {
        val h = height.toFloat(); val r = h / 2
        p.color = 0xFF222222.toInt(); cv.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, p)
        p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, C.RED_D, C.RED, Shader.TileMode.CLAMP)
        cv.drawRoundRect(0f, 0f, width * progress, h, r, r, p); p.shader = null
    }
}
