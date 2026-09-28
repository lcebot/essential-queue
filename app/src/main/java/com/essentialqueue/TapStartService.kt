package com.essentialqueue

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Taps the editor's "Start" button on behalf of the user, and nothing else.
 *
 * The scope is kept narrow on purpose:
 *  - It only receives events from com.miui.mediaeditor (see packageNames in res/xml/tap_start_service.xml).
 *  - It taps id/render_start once, and only while the queue has just opened the editor.
 *  - It never touches dialogs such as sign-in, mobile data or notification prompts. Those stay with the user.
 *
 * An enabled accessibility service may start activities from the background, which is why Auto mode
 * launches the editor from this context.
 */
class TapStartService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapStartService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var clickedOnce = false

    override fun onServiceConnected() {
        instance = this
        Queue.listenerPing()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        if (Queue.mode == Queue.Mode.AUTO &&
            Queue.state in setOf(Queue.State.OPENING, Queue.State.CLICKED, Queue.State.WAITING)) {
            Queue.pause(Queue.Msg(R.string.msg_a11y_turned_off))
        }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != Queue.EDITOR_PKG) return
        when (Queue.state) {
            Queue.State.OPENING -> tryClickStart()
            Queue.State.CLICKED -> checkAccepted()
            else -> clickedOnce = false
        }
    }

    private fun startButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != Queue.EDITOR_PKG) return null
        return root.findAccessibilityNodeInfosByViewId(Queue.START_BUTTON_ID).firstOrNull()
    }

    private fun tryClickStart() {
        if (clickedOnce) return
        val btn = startButton() ?: return
        // The button stays disabled while the editor decodes the photo.
        if (!btn.isVisibleToUser || !btn.isEnabled) return
        clickedOnce = true
        // A tap during the enter transition can be ignored, so let the screen settle first.
        handler.postDelayed({
            val b = startButton()
            if (Queue.state == Queue.State.OPENING && b != null && b.isVisibleToUser && b.isEnabled &&
                b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Queue.onClicked()
                pollAccepted()
            } else {
                clickedOnce = false
            }
        }, 800)
    }

    /** Content change events are not guaranteed, so poll as well. */
    private fun pollAccepted() {
        handler.postDelayed({
            if (Queue.state == Queue.State.CLICKED) { checkAccepted(); pollAccepted() }
        }, 700)
    }

    private fun visible(root: AccessibilityNodeInfo, id: String): Boolean =
        root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }

    /**
     * Requires positive evidence that the job was accepted: the Start button is hidden and the processing
     * UI (rendering_menu or the progress bar) is visible. A dialog on top has neither, so it can never be
     * mistaken for success, and the queue timeout pauses instead.
     */
    private fun checkAccepted() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != Queue.EDITOR_PKG) return
        val startShown = visible(root, Queue.START_BUTTON_ID)
        val processing = visible(root, Queue.RENDERING_MENU_ID) || visible(root, Queue.PROGRESS_ID)
        if (startShown || !processing) return
        clickedOnce = false
        Queue.onAccepted(this)
        // The job runs in the editor's own foreground service, so leaving the screen does not cancel it.
        // Only go back while the processing screen is on top, so an unrelated dialog is never dismissed.
        handler.postDelayed({
            val r = rootInActiveWindow
            if (r?.packageName?.toString() == Queue.EDITOR_PKG &&
                (visible(r, Queue.RENDERING_MENU_ID) || visible(r, Queue.PROGRESS_ID))) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 1500)
    }
}
