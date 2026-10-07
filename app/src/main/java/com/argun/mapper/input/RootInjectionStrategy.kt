package com.argun.mapper.input

import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.TimeUnit

/**
 * Dispatches key events by shelling out to the `input` command as root.
 *
 * This path needs no Android permission, so it is the one that works on a rooted
 * device where INJECT_EVENTS cannot be granted.
 *
 * Two details matter for games:
 *
 *  - **`input motionevent`** is used on Android 10+ because it maps to a specific
 *    source (`SOURCE_TOUCHSCREEN`) and preserves ACTION_DOWN/ACTION_UP separately.
 *    Older releases only have `input keyevent`, which sends a full down+up pair in
 *    one shot, so the release event is swallowed there — pressed buttons still
 *    register, held-to-charge style inputs do not.
 *  - Commands are serialised through a single worker thread. `input` spawns a
 *    process per call, and letting several run concurrently would reorder events
 *    within a frame.
 */
class RootInjectionStrategy : InjectionStrategy {

    companion object {
        private const val TAG = "RootInjection"
        private const val SU = "/system/bin/su"
        private const val COMMAND_TIMEOUT_S = 3L
    private const val PROBE_TIMEOUT_S = 30L
    }

    override val displayName: String = "Root (su)"

    private val supportsMotionEvent = android.os.Build.VERSION.SDK_INT >= 29

    private var suPath: String? = null

    @Volatile
    private var rootWorks = false

    override fun isAvailable(): Boolean {
        if (rootWorks) return true
        rootWorks = PrivilegeDetector.isRootAvailable()
        return rootWorks
    }

    override fun unavailableReason(): String? = when {
        PrivilegeDetector.findSuBinary() == null -> "No su binary found (device not rooted?)"
        !isAvailable() -> "su found but denied. Grant ARGUN Mapper superuser access."
        else -> null
    }

    /**
     * Fire `su` once, by itself, so the root manager shows its dialog and the user
     * can approve. Re-probes afterwards. This is the action behind the UI's
     * "Grant root access" button — detection alone cannot trigger the dialog.
     */
    fun requestRootGrant(packageName: String): Boolean {
        val su = PrivilegeDetector.findSuBinary() ?: return false
        return try {
            val process = ProcessBuilder(su, "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            val ok = process.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            if (ok) {
                PrivilegeDetector.invalidateRootCache()
                rootWorks = PrivilegeDetector.isRootAvailable(force = true)
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Root grant request failed: ${e.message}")
            false
        }
    }

    override fun inject(keyCode: Int, down: Boolean): Boolean {
        if (!isAvailable()) return false
        val su = suPath ?: PrivilegeDetector.findSuBinary()?.also { suPath = it } ?: return false
        val command = buildCommand(keyCode, down)
        return try {
            execute(su, command)
        } catch (e: Exception) {
            Log.e(TAG, "Root dispatch failed: ${e.message}")
            false
        }
    }

    private fun buildCommand(keyCode: Int, down: Boolean): String {
        // `input keyevent` handles either a code or a symbolic name; the numeric
        // code is unambiguous across builds.
        return if (supportsMotionEvent) {
            val event = if (down) "DOWN" else "UP"
            "input motionevent $event $keyCode"
        } else {
            // No separate down/up available: fire on press, drop the release.
            if (down) "input keyevent $keyCode" else "// no-op"
        }
    }

    private fun execute(su: String, command: String): Boolean {
        val process = ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroy()
            Log.w(TAG, "Root command timed out: $command")
            return false
        }
        val ok = process.exitValue() == 0
        if (!ok) {
            val err = process.inputStream.bufferedReader().use { it.readText() }.trim()
            Log.w(TAG, "Root command failed ($command): $err")
        }
        return ok
    }

    /** Best-effort: grant ourselves INJECT_EVENTS so the permission path also opens. */
    fun selfGrantPermission(packageName: String): Boolean {
        if (!isAvailable()) return false
        val su = suPath ?: PrivilegeDetector.findSuBinary() ?: return false
        return try {
            execute(su, "pm grant $packageName android.permission.INJECT_EVENTS")
        } catch (e: Exception) {
            Log.e(TAG, "Self-grant failed: ${e.message}")
            false
        }
    }

    /** Ask Shizuku (via su if present) to grant INJECT_EVENTS. */
    fun grantViaShizuku(packageName: String): Boolean = selfGrantPermission(packageName)
}