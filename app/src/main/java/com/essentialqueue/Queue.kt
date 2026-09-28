package com.essentialqueue

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes

/**
 * Opens photos in the editor's Leica Essential screen one at a time, using the same intent the Gallery sends.
 * Uploading, processing and saving stay inside the editor, so this app never talks to the server itself.
 *
 * The editor already runs one job at a time, so no delay is needed for correctness. The optional delay
 * spaces out uploads because a long burst of requests may get the user's account rate limited or blocked.
 * It defaults to [DEFAULT_INTERVAL_MS] so a new user starts on the cautious side.
 */
object Queue {
    const val EDITOR_PKG = "com.miui.mediaeditor"
    const val EDITOR_ACTIVITY = "com.miui.mediaeditor.legendary.LegendaryActivity"
    const val START_BUTTON_ID = "com.miui.mediaeditor:id/render_start"
    const val RENDERING_MENU_ID = "com.miui.mediaeditor:id/rendering_menu"
    const val PROGRESS_ID = "com.miui.mediaeditor:id/render_progress_front"
    // A real Button inside the processing layout, so it is reported even without the not-important views flag.
    const val CANCEL_ID = "com.miui.mediaeditor:id/render_cancel"
    const val DEFAULT_INTERVAL_MS = 30_000L
    const val MAX_INTERVAL_MS = 60_000L
    // Below this the UI shows a warning, since uploads then follow each other almost back to back.
    const val CAUTIOUS_INTERVAL_MS = 30_000L

    // Decoding a large photo can take several seconds before Start becomes tappable.
    private const val BUTTON_TIMEOUT_MS = 25_000L
    // Long enough for the editor to switch to its processing UI, short enough to notice a dialog quickly.
    private const val ACCEPT_TIMEOUT_MS = 10_000L
    // Coming back from the editor, the accessibility service may still be finishing its Back and the
    // window transition is still running. A short pause keeps the next launch from landing on top of that.
    private const val RESUME_SETTLE_MS = 1_000L

    enum class Mode { AUTO, SEMI }
    enum class State { IDLE, WAITING, OPENING, CLICKED, PAUSED, DONE }

    /** Scheduling uses its own handler so it can be cleared in one call without dropping UI updates. */
    private val sched = Handler(Looper.getMainLooper())
    private val ui = Handler(Looper.getMainLooper())

    var mode = Mode.AUTO
    var intervalMs = DEFAULT_INTERVAL_MS
        set(v) { field = v.coerceIn(0L, MAX_INTERVAL_MS) }

    var items: List<EssentialScanner.Photo> = emptyList()
        private set
    var index = 0
        private set
    var state = State.IDLE
        private set(v) {
            // Every transition is logged so a stuck queue can be diagnosed with: adb logcat -s EssentialQueue
            // (debug builds and untagged CI builds only, see Logs)
            if (field != v) Logs.i { "state $field to $v, index $index of ${items.size}" }
            field = v
        }
    /** Stores a resource id and arguments so the UI can format it in the current language. */
    data class Msg(@StringRes val id: Int, val args: List<Any> = emptyList())

    var message: Msg? = null
        private set
    // elapsedRealtime keeps the throttle correct even if the wall clock changes.
    private var lastLaunchAt = 0L
    private var phaseStartedAt = 0L
    // Launches only ever happen from the resumed activity, so the queue tracks when that was.
    private var foreground = false
    private var resumedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    // Held so the foreground service can be stopped from paths that have no Context of their own.
    private var appContext: Context? = null

    val current: EssentialScanner.Photo? get() = items.getOrNull(index)
    val nextAllowedAt: Long get() = lastLaunchAt + intervalMs

    var listener: (() -> Unit)? = null
    private fun notifyUi() {
        ui.post {
            listener?.invoke()
            appContext?.let { QueueService.sync(it) }
        }
    }
    fun listenerPing() = notifyUi()

    fun load(list: List<EssentialScanner.Photo>) {
        stop()
        items = list
        index = 0
        state = State.IDLE
        message = Msg(R.string.msg_loaded, listOf(list.size))
        notifyUi()
    }

    fun start(ctx: Context) {
        if (items.isEmpty() || index >= items.size) { message = Msg(R.string.msg_queue_empty); notifyUi(); return }
        if (mode == Mode.AUTO && TapStartService.instance == null) {
            message = Msg(R.string.msg_need_a11y); notifyUi(); return
        }
        appContext = ctx.applicationContext
        acquireWakeLock(ctx)
        // Must start while the app is in the foreground, which is always true here since only the UI calls start().
        QueueService.sync(ctx)
        scheduleNext()
    }

    fun pause(reason: Msg) {
        Logs.i { "pause: ${appContext?.getString(reason.id) ?: reason.id}" }
        sched.removeCallbacksAndMessages(null)
        state = State.PAUSED
        message = reason
        releaseWakeLock()
        notifyUi()
    }

    fun stop() {
        sched.removeCallbacksAndMessages(null)
        if (state != State.DONE) state = State.IDLE
        releaseWakeLock()
        notifyUi()
    }

    /** Only allowed while stopped, so skipping never races with a launch in progress. */
    fun skip() {
        if (state != State.PAUSED && state != State.IDLE) return
        if (index < items.size) index++
        message = Msg(R.string.msg_skipped)
        notifyUi()
    }

    /** For when the user tapped Start manually during a pause, so the same photo is not sent twice. */
    fun markCurrentDone(ctx: Context) {
        if (state != State.PAUSED && state != State.IDLE) return
        current?.let { History.markSent(ctx, it); index++ }
        message = Msg(R.string.msg_marked_done)
        notifyUi()
    }

    /**
     * A waiting queue continues as soon as the app is back in front: right away if the countdown already
     * ran out while the editor was showing, otherwise when it ends. Either way after [RESUME_SETTLE_MS].
     */
    fun onAppResumed() {
        foreground = true
        resumedAt = SystemClock.elapsedRealtime()
        if (state == State.WAITING) scheduleNext()
    }

    /** A launch due while the app is in the background waits for the next [onAppResumed]. */
    fun onAppPaused() {
        foreground = false
    }

    private fun scheduleNext() {
        sched.removeCallbacksAndMessages(null)
        if (index >= items.size) {
            state = State.DONE; message = Msg(R.string.msg_done); releaseWakeLock(); notifyUi(); return
        }
        state = State.WAITING
        val now = SystemClock.elapsedRealtime()
        // The countdown runs from the previous launch, not from the return, so time spent in the editor counts.
        val countdown = maxOf(0L, nextAllowedAt - now)
        val settle = if (foreground) maxOf(0L, resumedAt + RESUME_SETTLE_MS - now) else 0L
        val delay = maxOf(countdown, settle)
        message = when {
            countdown > 0 -> Msg(R.string.msg_waiting, listOf(((countdown + 999) / 1000).toInt()))
            foreground -> Msg(R.string.msg_opening)
            else -> Msg(R.string.msg_return_to_app)
        }
        notifyUi()
        // Scheduled even while in the background, so the message switches to "return to continue"
        // when the countdown ends.
        sched.postDelayed({ launchCurrent() }, delay)
    }

    private fun launchCurrent() {
        val p = current ?: return scheduleNext()
        // Launches only come from the resumed activity. That is never a background start, the editor opens
        // inside this app's task so Back always returns here, and it guarantees the previous editor screen
        // and the accessibility service are done before the next photo opens.
        val activity = MainActivity.resumedInstance
        val now = SystemClock.elapsedRealtime()
        if (activity == null || !foreground) {
            state = State.WAITING
            message = Msg(R.string.msg_return_to_app)
            notifyUi()
            return
        }
        if (mode == Mode.AUTO && TapStartService.instance == null) {
            pause(Msg(R.string.msg_a11y_not_running))
            return
        }
        // Check the throttle and the settle time right before launching so no code path can skip either.
        if ((lastLaunchAt != 0L && now < nextAllowedAt) || now < resumedAt + RESUME_SETTLE_MS) {
            scheduleNext()
            return
        }

        val intent = Intent()
            .setClassName(EDITOR_PKG, EDITOR_ACTIVITY)
            .setData(p.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Logs.i { "launching ${p.name}" }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            pause(Msg(R.string.msg_open_failed, listOf("${e.javaClass.simpleName} ${e.message.orEmpty()}")))
            return
        }
        lastLaunchAt = now
        phaseStartedAt = now
        val pos = index + 1
        message = Msg(R.string.msg_opened, listOf(p.name, pos, items.size))
        if (mode == Mode.AUTO) {
            state = State.OPENING
            sched.postDelayed({ checkTimeout() }, BUTTON_TIMEOUT_MS)
        } else {
            // The user taps Start themselves, and the next launch waits until they return.
            History.markSent(activity, p)
            index++
            state = State.WAITING
            message = Msg(R.string.msg_opened_semi, listOf(p.name, pos, items.size))
        }
        notifyUi()
    }

    fun onClicked() {
        if (state != State.OPENING) return
        state = State.CLICKED
        phaseStartedAt = SystemClock.elapsedRealtime()
        sched.removeCallbacksAndMessages(null)
        sched.postDelayed({ checkTimeout() }, ACCEPT_TIMEOUT_MS)
        message = Msg(R.string.msg_clicked, listOf(current?.name.orEmpty()))
        notifyUi()
    }

    fun onAccepted(ctx: Context) {
        if (state != State.CLICKED) return
        current?.let { History.markSent(ctx, it) }
        index++
        message = Msg(R.string.msg_accepted)
        scheduleNext()
    }

    private fun checkTimeout() {
        when (state) {
            State.OPENING -> pause(Msg(R.string.msg_timeout_button))
            State.CLICKED -> pause(Msg(R.string.msg_timeout_accept))
            else -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock(ctx: Context) {
        if (mode != Mode.AUTO || wakeLock?.isHeld == true) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "EssentialQueue:run").apply {
            setReferenceCounted(false)
            acquire((items.size - index + 1) * (intervalMs + 60_000L))
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

/** Remembers mode and delay so a chosen delay is not silently reset to the default on the next launch. */
object AppSettings {
    private const val PREF = "settings"

    /** Kept across launches because a user who needs the full search usually needs it every time. */
    var fullResultScan = false

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        Queue.intervalMs = sp.getLong("interval_ms", Queue.DEFAULT_INTERVAL_MS)
        Queue.mode = runCatching { Queue.Mode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(Queue.Mode.AUTO)
        fullResultScan = sp.getBoolean("full_result_scan", false)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong("interval_ms", Queue.intervalMs)
            .putString("mode", Queue.mode.name)
            .putBoolean("full_result_scan", fullResultScan)
            .apply()
    }
}

/** Kept in private SharedPreferences so the photo library itself is never written to. */
object History {
    private const val PREF = "history"
    private const val KEY = "sent"

    fun load(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())!!.toSet()

    fun markSent(ctx: Context, p: EssentialScanner.Photo) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val set = sp.getStringSet(KEY, emptySet())!!.toMutableSet()
        set += p.historyKey
        sp.edit().putStringSet(KEY, set).apply()
        p.sentBefore = true
    }

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
}
