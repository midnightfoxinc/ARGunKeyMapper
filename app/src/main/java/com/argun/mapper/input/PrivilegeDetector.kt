package com.argun.mapper.input

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Detects which input-injection privileges this device offers.
 *
 * The three routes, in the order they are usually preferred:
 *
 *  - **Root** — a usable `su` binary. Lets the app dispatch keys directly and also
 *    lets it grant itself INJECT_EVENTS, so it is the most capable option.
 *  - **Shizuku** — a running Shizuku service. Shizuku exposes its own shell binder,
 *    which can run `pm grant` on our behalf.
 *  - **adb** — a previously granted INJECT_EVENTS (typically
 *    `adb shell pm grant com.argun.mapper android.permission.INJECT_EVENTS`).
 *
 * Root and Shizuku are discovered, not assumed; `adb` has no marker on the device,
 * so it is inferred from the permission actually being held.
 */
object PrivilegeDetector {

    private const val TAG = "PrivilegeDetector"
    private const val PROBE_TIMEOUT_S = 30L

    /**
     * `INJECT_EVENTS` has no constant in [Manifest.permission] because it is not
     * public API. The literal string is stable and is what `pm grant` expects.
     */
    const val INJECT_EVENTS = "android.permission.INJECT_EVENTS"

    private const val SHIZUKU_SERVER_SERVICE = "moe.shizuku.server"
    private const val SHIZUKU_PERMISSION_GRANTOR = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PERMISSION_GRANTOR_DEBUG = "moe.shizuku.privileged.api.debug"

    /** Common locations for the `su` binary across root implementations. */
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/debug_ramdisk/su",
        "/system/sbin/su",
        "/vendor/bin/su"
    )

    /** Packages that indicate a root manager is installed. */
    private val ROOT_MANAGER_PACKAGES = arrayOf(
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.topjohnwu.magisk",
        "com.koushikdutta.superuser",
        "me.phh.superuser",
        "com.ryosoftware.suhelper",
        "com.devadvance.rootcloak"
    )

    /**
     * True when INJECT_EVENTS is currently held.
     *
     * On API 23+ this is a normal runtime grant as far as the check is concerned;
     * the platform still only lets adb/root/Shizuku hand it over.
     */
    fun hasInjectEventsPermission(context: Context): Boolean =
        try {
            // checkSelfPermission reflects the grant state for our own UID, which is
            // what matters here — signature-level permissions are reported the same
            // way as runtime ones.
            context.checkSelfPermission(INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Could not check INJECT_EVENTS: ${e.message}")
            false
        }

    /**
     * Cached result of the last root probe, plus the moment it was taken.
     *
     * Probing `su` is expensive and, the first time, *blocks* while the root
     * manager shows its permission dialog. Caching keeps the UI responsive and
     * stops the app from spawning a fresh `su` on every state refresh.
     */
    @Volatile
    private var cachedRoot: Boolean? = null

    @Volatile
    private var cachedRootAt: Long = 0L

    private const val ROOT_CACHE_TTL_MS = 30_000L

    /** Forget the cached probe so the next call re-tests `su` immediately. */
    fun invalidateRootCache() {
        cachedRoot = null
        cachedRootAt = 0L
    }

    /**
     * True when a `su` binary exists and actually grants root.
     *
     * Existence alone is not enough: Magisk's stub `su` reports failure until the
     * app is added to the superuser list, so we execute it and read the result.
     *
     * [force] bypasses the cache — used after the user has just been through the
     * root manager's permission dialog.
     */
    fun isRootAvailable(force: Boolean = false): Boolean {
        val cached = cachedRoot
        if (!force && cached != null &&
            System.currentTimeMillis() - cachedRootAt < ROOT_CACHE_TTL_MS
        ) {
            return cached
        }

        val result = probeSu()
        cachedRoot = result
        cachedRootAt = System.currentTimeMillis()
        Log.i(TAG, "Root probe result: $result")
        return result
    }

    private fun probeSu(): Boolean {
        val su = findSuBinary() ?: return false
        return try {
            val process = ProcessBuilder(su, "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            // The first invocation blocks on the root manager's dialog, which the
            // user drives by hand. 30s tolerates that; a short timeout here is what
            // made root look permanently unavailable right after granting it.
            if (!process.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroy()
                Log.w(TAG, "su probe timed out - probably waiting on a root dialog")
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            // A working root shell reports uid 0.
            val uid = output.trim().substringAfter("uid=", "")
            uid.trim().startsWith("0")
        } catch (e: Exception) {
            Log.w(TAG, "su present but not usable: ${e.message}")
            false
        }
    }

    fun findSuBinary(): String? =
        SU_PATHS.firstOrNull { path -> File(path).canExecute() }

    /** True when any known root manager package is installed. */
    fun hasRootManager(context: Context): Boolean =
        ROOT_MANAGER_PACKAGES.any { isPackageInstalled(context, it) }

    fun isShizukuInstalled(context: Context): Boolean =
        isPackageInstalled(context, SHIZUKU_PERMISSION_GRANTOR) ||
            isPackageInstalled(context, SHIZUKU_PERMISSION_GRANTOR_DEBUG)

    /**
     * True when the Shizuku *service* is running, which is what actually matters —
     * the app can be installed while Shizuku is stopped.
     *
     * Checked by asking the system service manager whether Shizuku's binder exists.
     * On API 28+ hidden-API restrictions make this unreliable, so callers fall back
     * to a permission grant on those releases.
     */
    fun isShizukuRunning(): Boolean = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        @Suppress("UNCHECKED_CAST")
        (getService.invoke(null, SHIZUKU_SERVER_SERVICE) as android.os.IBinder?) != null
    } catch (e: Exception) {
        Log.d(TAG, "Could not query Shizuku binder: ${e.message}")
        false
    }

    /**
     * Full picture of what this device supports, for the settings screen.
     */
    fun detect(context: Context): PrivilegeStatus {
        val root = isRootAvailable()
        val shizuku = isShizukuInstalled(context)
        val shizukuRunning = shizuku && isShizukuRunning()
        val granted = hasInjectEventsPermission(context)
        return PrivilegeStatus(
            rootAvailable = root,
            rootManagerInstalled = hasRootManager(context),
            shizukuInstalled = shizuku,
            shizukuRunning = shizukuRunning,
            injectEventsGranted = granted,
            hogpSupported = isHogpSupported(context)
        )
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    /**
     * True when this device can act as a BLE peripheral, which is what HOGP needs.
     *
     * [BluetoothLeAdvertiser] arrived in API 21, but plenty of older and TV
     * chipsets report no advertiser at all, so ask the adapter rather than
     * trusting the SDK level alone. This does not touch the radio — it only
     * allocates the advertiser object.
     */
    fun isHogpSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.bluetoothLeAdvertiser != null
        } catch (e: SecurityException) {
            Log.w(TAG, "No BLUETOOTH_CONNECT permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Could not query BLE advertiser: ${e.message}")
            false
        }
    }
}

/**
 * What the current device offers. Rendered directly by the settings screen.
 */
data class PrivilegeStatus(
    val rootAvailable: Boolean = false,
    val rootManagerInstalled: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val injectEventsGranted: Boolean = false,
    val hogpSupported: Boolean = false
) {
    /**
     * The route to use when the user taps "Enable automatically".
     *
     * HOGP comes first: it needs no permission at all, so it is the only option
     * on a stock Android 12+ device, and it is far better suited to a trigger
     * than root — a `su` process per event cannot keep up with a fast trigger.
     * The privileged routes stay available as explicit choices.
     */
    fun bestAvailableMode(): InjectionMode = when {
        hogpSupported -> InjectionMode.HOGP
        rootAvailable -> InjectionMode.ROOT
        injectEventsGranted -> InjectionMode.PERMISSION
        else -> InjectionMode.NONE
    }

    /** True when the user could plausibly enable injection with a little help. */
    val canBeEnabled: Boolean
        get() = hogpSupported || rootAvailable || rootManagerInstalled || shizukuInstalled
            || shizukuRunning || injectEventsGranted
}