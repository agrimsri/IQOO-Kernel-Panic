package com.dpdpxray.app.consent

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.dpdpxray.app.ui.theme.Palette
import com.dpdpxray.app.ui.theme.Palettes
import androidx.compose.ui.graphics.toArgb

/**
 * What the person sees on top of the app being checked, drawn as accessibility overlays (no extra permission):
 *
 * - a soft glow along the top edge for as long as the app is under inspection, so it is always clear that TraceVeil is
 *   watching. It never takes touches or focus, and it stands still when the person has turned animations off;
 * - a small card, shown only while a check waits for the person, naming the field to fill in. It sits below the status bar
 *   so the keyboard and the field stay free, can be hidden, and offers to stop the check.
 *
 * Every call is made on the main thread. A window that cannot be added (for example while the screen is locked) is
 * ignored: the overlay is a cue, never something a check depends on.
 */
internal class InspectionOverlay(private val service: AccessibilityService) {
    private val wm get() = service.getSystemService(WindowManager::class.java)
    private var glow: View? = null
    private var pulse: ValueAnimator? = null
    private var card: View? = null

    private val palette: Palette
        get() = if ((service.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES) Palettes.Dark else Palettes.Light

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics)

    val glowShowing: Boolean get() = glow != null

    fun showGlow() {
        if (glow != null) return
        // Dusty Lavender reads on light and dark apps alike; it fades to nothing over about 80 dp.
        val top = ColorUtils.setAlphaComponent(DUSTY_LAVENDER, 0x8C)
        val mid = ColorUtils.setAlphaComponent(DUSTY_LAVENDER, 0x33)
        val view = View(service).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, mid, Color.TRANSPARENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(GLOW_DP).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (runCatching { wm.addView(view, params) }.isFailure) return
        glow = view
        if (ValueAnimator.areAnimatorsEnabled()) {
            pulse = ValueAnimator.ofFloat(0.55f, 1f).apply {
                duration = 2_400
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { view.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    fun hideGlow() {
        pulse?.cancel()
        pulse = null
        glow?.let { runCatching { wm.removeView(it) } }
        glow = null
    }

    /** The card asking the person to fill something in. Replaces one already showing. */
    fun showPrompt(title: String, body: String, stopLabel: String, hideLabel: String, onStop: () -> Unit) {
        hidePrompt()
        val p = palette
        val bg = GradientDrawable().apply { setColor(p.surfaceRaised.toArgb()); cornerRadius = dp(18f) }
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            elevation = dp(8f)
            setPadding(dp(18f).toInt(), dp(14f).toInt(), dp(18f).toInt(), dp(6f).toInt())
        }
        column.addView(TextView(service).apply {
            text = title
            setTextColor(p.textPrimary.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(service).apply {
            text = body
            setTextColor(p.textSecondary.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
        })
        val actions = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun action(label: String, color: Int, onClick: () -> Unit) = TextView(service).apply {
            text = label
            isAllCaps = false
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(48f).toInt()
            gravity = Gravity.CENTER
            setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
            setOnClickListener { onClick() }
        }
        actions.addView(action(hideLabel, p.textSecondary.toArgb()) { hidePrompt() })
        actions.addView(action(stopLabel, p.accent.toArgb()) { hidePrompt(); onStop() })
        column.addView(actions)

        val params = WindowManager.LayoutParams(
            service.resources.displayMetrics.widthPixels - 2 * dp(16f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(TOP_MARGIN_DP).toInt()
        }
        if (runCatching { wm.addView(column, params) }.isSuccess) card = column
    }

    fun hidePrompt() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    fun hideAll() {
        hidePrompt()
        hideGlow()
    }

    companion object {
        private const val GLOW_DP = 88f
        private const val TOP_MARGIN_DP = 72f
        /** Dusty Lavender #8B687F, one of the five scheme colours. */
        private val DUSTY_LAVENDER = Color.parseColor("#8B687F")
    }
}
