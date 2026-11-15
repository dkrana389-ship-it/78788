package com.deep.focus

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Activity version of the check-in. The real popup is a window overlay drawn by [MonitorService];
 * this screen is used for "Preview the check-in" and as a fallback if the overlay cannot be drawn.
 */
class FocusActivity : Activity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_OPENS = "opens"
        const val EXTRA_STREAK = "streak"
        const val EXTRA_PREVIEW = "preview"
    }

    private var pkg = "com.instagram.android"
    private var preview = false
    private var countdown: CountdownButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        render(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        render(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        countdown?.cancel()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        closeApp()
    }

    private fun render(i: Intent) {
        countdown?.cancel()
        pkg = i.getStringExtra(EXTRA_PKG) ?: "com.instagram.android"
        preview = i.getBooleanExtra(EXTRA_PREVIEW, false)
        val seconds = i.getLongExtra(EXTRA_SECONDS, 0L)
        val opens = i.getIntExtra(EXTRA_OPENS, 1)
        val streak = i.getIntExtra(EXTRA_STREAK, 0)
        // Preview uses a short lock so you can test quickly; the real popup uses your setting.
        val lock = if (preview) 5 else Prefs.countdownSeconds(this)

        val ui = buildPopup(
            TrackedApps.label(pkg), seconds, opens, streak, Prefs.goal(this), lock,
            onClose = { closeApp() },
            onContinue = { continueApp() }
        )
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            ui.content.setPadding(dp(20) + b.left, dp(28) + b.top, dp(20) + b.right, dp(24) + b.bottom)
            insets
        }
        setContentView(ui.root)
        countdown = ui.countdown
        hideSystemBars()
        ui.start()
    }

    private fun closeApp() {
        if (!preview) {
            FocusState.sessionPackage = null
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    private fun continueApp() {
        if (!preview) FocusState.sessionPackage = pkg
        finish()
    }
}
