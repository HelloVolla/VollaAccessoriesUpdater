package com.volla.accessories.fw.updater.app.ui

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Hidden gate for the manual firmware picker. It stays invisible: nothing is drawn, no touch or
 * key event is consumed. Unlocks on a tap sequence of the four screen corners in clockwise order
 * starting top-left, or on typing [MAGIC_WORD] on a hardware keyboard.
 */
class SecretUnlock(private val onUnlocked: () -> Unit) {

    private var corner = 0
    private var lastTapAt = 0L
    private val typed = StringBuilder()

    fun onTouch(event: MotionEvent, width: Int, height: Int) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        if (width <= 0 || height <= 0) return
        if (corner > 0 && event.eventTime - lastTapAt > STEP_TIMEOUT_MS) corner = 0

        val tapped = cornerAt(event.x, event.y, width, height)
        corner = when {
            tapped == null -> 0
            tapped == corner -> corner + 1
            tapped == TOP_LEFT -> 1
            else -> 0
        }
        lastTapAt = event.eventTime
        if (corner == CORNER_SEQUENCE) unlock()
    }

    fun onKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val char = event.unicodeChar.toChar().lowercaseChar()
        if (char !in 'a'..'z') {
            typed.setLength(0)
            return
        }
        typed.append(char)
        if (typed.length > MAGIC_WORD.length) typed.deleteCharAt(0)
        if (typed.toString() == MAGIC_WORD) unlock()
    }

    private fun unlock() {
        corner = 0
        typed.setLength(0)
        onUnlocked()
    }

    /** Corner index in tap order: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left. */
    private fun cornerAt(x: Float, y: Float, width: Int, height: Int): Int? {
        val left = x <= width * CORNER_FRACTION
        val right = x >= width * (1f - CORNER_FRACTION)
        val top = y <= height * CORNER_FRACTION
        val bottom = y >= height * (1f - CORNER_FRACTION)
        return when {
            top && left -> TOP_LEFT
            top && right -> 1
            bottom && right -> 2
            bottom && left -> 3
            else -> null
        }
    }

    private companion object {
        const val MAGIC_WORD = "magic"
        const val CORNER_SEQUENCE = 4
        const val TOP_LEFT = 0
        const val CORNER_FRACTION = 0.15f
        const val STEP_TIMEOUT_MS = 3000L
    }
}
