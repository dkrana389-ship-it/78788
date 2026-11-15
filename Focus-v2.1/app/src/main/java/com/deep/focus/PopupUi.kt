package com.deep.focus

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/** The check-in screen as plain views, so it can live in a window overlay OR in an Activity. */
class PopupViews(
    val root: FrameLayout,
    val content: LinearLayout,
    val countdown: CountdownButton,
    val start: () -> Unit
)

private fun Context.divider(): View = View(this).apply { setBackgroundColor(C.DIVIDER) }

private fun Context.statColumn(value: String, caption: String, startPad: Int): LinearLayout {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(startPad, 0, 0, 0)
    col.addView(label(value, 28f, Color.WHITE, 2), lp(WRAP, WRAP))
    col.addView(label(caption, 15f, C.MUTED), lp(WRAP, WRAP, dp(6)))
    return col
}

fun Context.buildPopup(
    appName: String,
    seconds: Long,
    opens: Int,
    streak: Int,
    goal: String,
    countdownSeconds: Int,
    onClose: () -> Unit,
    onContinue: () -> Unit
): PopupViews {
    val root = FrameLayout(this)
    root.addView(AuroraBackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))

    val content = LinearLayout(this)
    content.orientation = LinearLayout.VERTICAL
    content.setPadding(dp(20), dp(28), dp(20), dp(24))
    root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

    // ---- Header: logo + name on the left, streak pill on the right ----------------------------
    val header = LinearLayout(this)
    header.orientation = LinearLayout.HORIZONTAL
    header.gravity = Gravity.CENTER_VERTICAL
    header.addView(LogoView(this), LinearLayout.LayoutParams(dp(30), dp(30)))
    val name = label("Focus", 24f, Color.WHITE, 2)
    name.setPadding(dp(12), 0, 0, 0)
    header.addView(name, LinearLayout.LayoutParams(WRAP, WRAP))
    header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

    val pill = LinearLayout(this)
    pill.orientation = LinearLayout.HORIZONTAL
    pill.gravity = Gravity.CENTER_VERTICAL
    pill.setPadding(dp(16), dp(10), dp(18), dp(10))
    pill.background = rounded(Color.parseColor("#47121218"), dpf(40f), Color.parseColor("#33FFFFFF"), dp(1))
    val dot = View(this)
    dot.background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (streak > 0) C.GREEN else C.ORANGE)
    }
    pill.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)))
    val pillText = label(if (streak > 0) "Day $streak streak" else "Streak reset", 15f, Color.WHITE, 1)
    pillText.setPadding(dp(10), 0, 0, 0)
    pill.addView(pillText, LinearLayout.LayoutParams(WRAP, WRAP))
    header.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP))
    content.addView(header, lp(MATCH, WRAP))

    content.addView(View(this), lp(MATCH, 0, 0, 1f))

    // ---- Glass card -----------------------------------------------------------------------------
    val card = LinearLayout(this)
    card.orientation = LinearLayout.VERTICAL
    card.setPadding(dp(26), dp(26), dp(26), dp(26))
    card.background = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#38FFFFFF"), Color.parseColor("#E61C1C21"), Color.parseColor("#F0141417"))
    ).apply {
        cornerRadius = dpf(34f)
        setStroke(dp(1), Color.parseColor("#33FFFFFF"))
    }

    card.addView(sectionLabel("Today on $appName"), lp(WRAP, WRAP))

    val totalMin = ((seconds + 30L) / 60L).toInt()
    val bigText: String
    val unit: String
    if (totalMin < 60) {
        bigText = totalMin.toString()
        unit = "min"
    } else {
        bigText = (totalMin / 60).toString() + ":" + String.format("%02d", totalMin % 60)
        unit = "hr"
    }
    val bigRow = LinearLayout(this)
    bigRow.orientation = LinearLayout.HORIZONTAL
    val big = label(bigText, 92f, Color.WHITE, 2)
    big.letterSpacing = -0.03f
    bigRow.addView(big, LinearLayout.LayoutParams(WRAP, WRAP))
    val unitTv = label(unit, 30f, C.MUTED, 1)
    val unitLp = LinearLayout.LayoutParams(WRAP, WRAP)
    unitLp.marginStart = dp(10)
    bigRow.addView(unitTv, unitLp)
    card.addView(bigRow, lp(WRAP, WRAP, dp(10)))

    val countUp = totalMin in 1..59
    if (countUp) big.text = "0"

    card.addView(divider(), lp(MATCH, dp(1), dp(14)))

    val yearHours = Math.round(seconds * 365.0 / 3600.0)
    val stats = LinearLayout(this)
    stats.orientation = LinearLayout.HORIZONTAL
    stats.addView(statColumn("$yearHours hrs", "a year at this pace", 0), lp(0, WRAP, 0, 1f))
    val vDivider = View(this)
    vDivider.setBackgroundColor(C.DIVIDER)
    stats.addView(vDivider, LinearLayout.LayoutParams(dp(1), MATCH))
    val opensWord = if (opens == 1) "time" else "times"
    stats.addView(statColumn("$opens $opensWord", "opened today", dp(20)), lp(0, WRAP, 0, 1f))
    card.addView(stats, lp(MATCH, WRAP, dp(18)))

    card.addView(divider(), lp(MATCH, dp(1), dp(18)))

    card.addView(label("Is this getting you closer to", 20f, Color.parseColor("#B8B8C0")), lp(WRAP, WRAP, dp(20)))
    val goalTv = label("$goal?", 36f, C.PEACH, 2)
    val gw = goalTv.paint.measureText(goalTv.text.toString())
    goalTv.paint.shader = LinearGradient(
        0f, 0f, maxOf(gw, 1f), 0f,
        intArrayOf(Color.parseColor("#FFE2B8"), Color.parseColor("#F7A95B")),
        null, Shader.TileMode.CLAMP
    )
    card.addView(goalTv, lp(WRAP, WRAP, dp(6)))

    val close = label("Close $appName", 20f, C.INK, 2)
    close.gravity = Gravity.CENTER
    close.background = rounded(Color.WHITE, dpf(40f))
    close.isClickable = true
    close.pressFeedback()
    close.setOnClickListener { onClose() }
    card.addView(close, lp(MATCH, dp(64), dp(22)))

    val cont = CountdownButton(this)
    cont.readyText = "Continue to $appName"
    cont.onContinue = { onContinue() }
    card.addView(cont, lp(MATCH, dp(58), dp(12)))

    content.addView(card, lp(MATCH, WRAP))

    card.alpha = 0f
    card.translationY = dpf(56f)
    header.alpha = 0f

    val start: () -> Unit = {
        card.animate().alpha(1f).translationY(0f).setDuration(480)
            .setInterpolator(DecelerateInterpolator()).start()
        header.animate().alpha(1f).setDuration(480).start()
        if (countUp) {
            val anim = ValueAnimator.ofInt(0, totalMin)
            anim.duration = 750
            anim.startDelay = 250
            anim.interpolator = DecelerateInterpolator()
            anim.addUpdateListener { big.text = (it.animatedValue as Int).toString() }
            anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) {
                    big.text = totalMin.toString()
                }
            })
            anim.start()
        }
        cont.start(countdownSeconds)
    }
    return PopupViews(root, content, cont, start)
}
