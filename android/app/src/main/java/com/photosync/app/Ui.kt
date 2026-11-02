package com.photosync.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Тёплая палитра в духе Claude: кремовый фон и терракотовый акцент. */
class Palette(context: Context) {
    val isDark = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    val background = if (isDark) 0xFF262624.toInt() else 0xFFFAF9F5.toInt()
    val surface = if (isDark) 0xFF30302E.toInt() else 0xFFFFFFFF.toInt()
    val border = if (isDark) 0xFF45443F.toInt() else 0xFFE8E6DC.toInt()
    val text = if (isDark) 0xFFF5F4EE.toInt() else 0xFF141413.toInt()
    val muted = if (isDark) 0xFFA6A39A.toInt() else 0xFF6B6A64.toInt()
    val accent = 0xFFC15F3C.toInt()
    val onAccent = 0xFFFFFFFF.toInt()
    val ok = if (isDark) 0xFF8CC38A.toInt() else 0xFF4E8A4C.toInt()
}

/** Готовые элементы интерфейса, чтобы экран собирался из одинаковых кусочков. */
class Ui(private val context: Context) {
    val c = Palette(context)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, stroke: Int? = null, radius: Int = 16) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 36f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        setTextColor(c.text)
    }

    fun body(text: String = "", muted: Boolean = false, size: Float = 15f) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(if (muted) c.muted else c.text)
        setLineSpacing(0f, 1.2f)
    }

    fun label(text: String) = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.1f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(c.muted)
        setPadding(dp(4), dp(28), 0, dp(2))
    }

    fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(c.surface, c.border, 20)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = fullWidth(top = 16)
    }

    fun dot(color: Int) = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(14) }
    }

    fun input(hint: String) = EditText(context).apply {
        this.hint = hint
        textSize = 16f
        setTextColor(c.text)
        setHintTextColor(c.muted)
        background = rounded(c.surface, c.border, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = fullWidth(top = 10)
    }

    fun primaryButton(text: String, onClick: () -> Unit) =
        button(text, c.accent, null, c.onAccent, onClick)

    fun secondaryButton(text: String, onClick: () -> Unit) =
        button(text, c.surface, c.border, c.text, onClick)

    private fun button(text: String, fill: Int, stroke: Int?, textColor: Int, onClick: () -> Unit) =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            val ripple = ColorStateList.valueOf(if (c.isDark) 0x33FFFFFF else 0x22000000)
            background = RippleDrawable(ripple, rounded(fill, stroke, 14), null)
            stateListAnimator = null // без тени, плоские кнопки
            minHeight = dp(54)
            layoutParams = fullWidth(top = 10)
            setOnClickListener { onClick() }
        }

    fun switch() = Switch(context).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(c.accent, if (c.isDark) 0xFFBDBAB0.toInt() else c.onAccent))
        trackTintList = ColorStateList(states, intArrayOf((c.accent and 0x00FFFFFF) or 0x66000000, c.border))
    }

    private fun fullWidth(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }
}
