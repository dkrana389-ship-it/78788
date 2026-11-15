package com.deep.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * Watches usage events (Usage Access permission, NOT Accessibility) and draws the full-screen
 * check-in as a window overlay whenever a guarded app comes to the foreground.
 *
 * The overlay is drawn directly with WindowManager (needs only "Display over other apps"),
 * so it is not blocked by Android's background-activity-start rules.
 */
class MonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "focus_monitor"
        const val ALERT_CHANNEL_ID = "focus_checkin"
        const val NOTIF_ID = 1001
        const val ALERT_NOTIF_ID = 1002
        private const val POLL_MS = 500L
        private const val LEAVE_GRACE_MS = 2500L
        private const val STUCK_MS = 3000L

        @Volatile
        var running: Boolean = false

        fun start(c: Context) {
            Prefs.setMonitoring(c, true)
            ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))
        }

        fun stop(c: Context) {
            Prefs.setMonitoring(c, false)
            c.stopService(Intent(c, MonitorService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastEventTs = 0L
    private var otherSince = 0L
    private var ticks = 0

    @Volatile private var popupRequestedAt = 0L
    @Volatile private var overlay: View? = null
    private var countdownBtn: CountdownButton? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // New screen-on counts as a fresh open
            FocusState.sessionPackage = null
            otherSince = 0L
            main.post { removeOverlay() }
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            try {
                poll()
            } catch (e: Throwable) {
                // keep the monitor alive no matter what
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
        val t = HandlerThread("focus-monitor")
        t.start()
        thread = t
        handler = Handler(t.looper)
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        lastEventTs = System.currentTimeMillis()
        handler?.removeCallbacks(loop)
        handler?.post(loop)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler?.removeCallbacks(loop)
        thread?.quitSafely()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
        }
        removeOverlay()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------

    private fun poll() {
        if (!UsageHelper.hasUsageAccess(this)) return
        val tracked = Prefs.trackedPackages(this)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastEventTs + 1, now + 1000)
        val ev = UsageEvents.Event()
        var launchPkg: String? = null
        var leftTracked = false

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.timeStamp > lastEventTs) lastEventTs = ev.timeStamp
            if (ev.eventType != UsageHelper.EV_RESUMED) continue
            val pkg = ev.packageName ?: continue
            if (pkg == packageName) continue

            if (pkg in tracked) {
                otherSince = 0L
                launchPkg = if (pkg != FocusState.sessionPackage && !FocusState.popupShowing) pkg else null
            } else {
                launchPkg = null
                leftTracked = true
                if (otherSince == 0L) otherSince = SystemClock.elapsedRealtime()
            }
        }

        // User went Home / Recents / another app: the overlay must not stay on top of it
        if (leftTracked && FocusState.popupShowing) {
            main.post { removeOverlay() }
        }

        // Safety net: a popup was requested but never appeared -> don't block future popups
        if (FocusState.popupShowing && overlay == null &&
            SystemClock.elapsedRealtime() - popupRequestedAt > STUCK_MS
        ) {
            FocusState.popupShowing = false
        }

        // User really left the guarded app (not just a quick system dialog)
        if (otherSince != 0L && SystemClock.elapsedRealtime() - otherSince >= LEAVE_GRACE_MS) {
            FocusState.sessionPackage = null
            otherSince = 0L
        }

        launchPkg?.let { showPopup(it, tracked) }

        ticks++
        if (ticks % 60 == 1) {
            UsageHelper.recordToday(this, UsageHelper.todayStats(this, tracked))
        }
    }

    private fun showPopup(pkg: String, tracked: Set<String>) {
        val stats = UsageHelper.todayStats(this, tracked)
        UsageHelper.recordToday(this, stats)
        val s = stats[pkg]
        val seconds = s?.seconds ?: 0L
        val opens = maxOf(1, s?.opens ?: 1)
        val streak = UsageHelper.streak(this)

        if (UsageHelper.hasOverlay(this)) {
            FocusState.popupShowing = true
            popupRequestedAt = SystemClock.elapsedRealtime()
            main.post { showOverlay(pkg, seconds, opens, streak) }
        } else {
            postFullScreenNotification(activityIntent(pkg, seconds, opens, streak))
        }
    }

    // ---- Overlay window (runs on the main thread) ---------------------------------------------

    private fun showOverlay(pkg: String, seconds: Long, opens: Int, streak: Int) {
        removeOverlay()
        FocusState.popupShowing = true
        try {
            val ui = buildPopup(
                TrackedApps.label(pkg), seconds, opens, streak,
                Prefs.goal(this), Prefs.countdownSeconds(this),
                onClose = { closeFromOverlay() },
                onContinue = {
                    FocusState.sessionPackage = pkg
                    removeOverlay()
                }
            )

            val res = resources
            val sbId = res.getIdentifier("status_bar_height", "dimen", "android")
            val nbId = res.getIdentifier("navigation_bar_height", "dimen", "android")
            val sb = if (sbId > 0) res.getDimensionPixelSize(sbId) else dp(24)
            val nb = if (nbId > 0) res.getDimensionPixelSize(nbId) else dp(24)
            ui.content.setPadding(dp(20), dp(28) + sb, dp(20), dp(24) + nb)

            ui.root.isFocusableInTouchMode = true
            ui.root.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) closeFromOverlay()
                    true
                } else false
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(ui.root, params)
            overlay = ui.root
            countdownBtn = ui.countdown
            ui.root.requestFocus()
            ui.start()
        } catch (e: Throwable) {
            overlay = null
            FocusState.popupShowing = false
            // Last resort: full-screen notification that opens the Activity version
            postFullScreenNotification(activityIntent(pkg, seconds, opens, streak))
        }
    }

    private fun closeFromOverlay() {
        FocusState.sessionPackage = null
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
        removeOverlay()
    }

    private fun removeOverlay() {
        val v = overlay
        overlay = null
        countdownBtn?.cancel()
        countdownBtn = null
        if (v != null) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        FocusState.popupShowing = false
    }

    // ---- Fallback + notifications -------------------------------------------------------------

    private fun activityIntent(pkg: String, seconds: Long, opens: Int, streak: Int): Intent =
        Intent(this, FocusActivity::class.java)
            .putExtra(FocusActivity.EXTRA_PKG, pkg)
            .putExtra(FocusActivity.EXTRA_SECONDS, seconds)
            .putExtra(FocusActivity.EXTRA_OPENS, opens)
            .putExtra(FocusActivity.EXTRA_STREAK, streak)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )

    private fun postFullScreenNotification(intent: Intent) {
        val pi = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle("Focus check-in")
            .setContentText("Tap to take a moment before you scroll")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIF_ID, n)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Focus monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Focus check-in", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val names = Prefs.trackedPackages(this).joinToString(", ") { TrackedApps.label(it) }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle("Focus is on")
            .setContentText(if (names.isEmpty()) "No apps selected" else "Guarding $names")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
