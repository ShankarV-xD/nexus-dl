package com.kira.ytdlp

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Utility for providing haptic feedback throughout the app.
 * 
 * Provides different levels of feedback for different interactions:
 * - Light: For subtle interactions like toggling
 * - Medium: For standard button presses
 * - Heavy: For important actions like download start
 * - Success: For completion feedback
 * - Error: For error states
 */
object HapticFeedback {

    /**
     * Performs light haptic feedback for subtle interactions.
     * Used for: toggling selections, switches
     * 
     * @param context The context to use for vibration
     */
    fun light(context: Context) {
        vibrate(context, 10)
    }

    /**
     * Performs medium haptic feedback for standard button presses.
     * Used for: clicking buttons, selecting formats
     * 
     * @param context The context to use for vibration
     */
    fun medium(context: Context) {
        vibrate(context, 20)
    }

    /**
     * Performs heavy haptic feedback for important actions.
     * Used for: starting downloads, major actions
     * 
     * @param context The context to use for vibration
     */
    fun heavy(context: Context) {
        vibrate(context, 40)
    }

    /**
     * Performs success haptic feedback for completed actions.
     * Used for: download complete, operation success
     * 
     * @param context The context to use for vibration
     */
    fun success(context: Context) {
        vibrate(context, 30)
    }

    /**
     * Performs error haptic feedback for failed actions.
     * Used for: errors, validation failures
     * 
     * @param context The context to use for vibration
     */
    fun error(context: Context) {
        vibrate(context, 60)
    }

    /**
     * Internal vibration helper.
     */
    private fun vibrate(context: Context, milliseconds: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(milliseconds)
        }
    }
}
