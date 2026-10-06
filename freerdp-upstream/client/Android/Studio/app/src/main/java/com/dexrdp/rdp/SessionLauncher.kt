package com.dexrdp.rdp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.preference.PreferenceManager
import com.dexrdp.data.ConnectionProfile
import com.dexrdp.data.Security
import com.dexrdp.data.Transport

object SessionLauncher {

    const val SESSION_ACTIVITY = "com.freerdp.freerdpcore.presentation.SessionActivity"
    const val KEYBOARD_SERVICE = "com.freerdp.freerdpcore.presentation.KeyboardAccessibilityService"

    private const val PREF_HIDE_STATUS_BAR = "ui.hide_status_bar"
    private const val PREF_HIDE_NAVIGATION_BAR = "ui.hide_navigation_bar"
    private const val PREF_KEEP_SCREEN_ON = "power.keep_screen_on_when_connected"
    private const val PREF_USE_BACK_AS_ALTF4 = "ui.use_back_as_altf4"
    private const val PREF_INVERT_SCROLLING = "ui.invert_scrolling"

    fun applySessionDefaults(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        preferences.edit()
            .putBoolean(PREF_HIDE_STATUS_BAR, true)
            .putBoolean(PREF_HIDE_NAVIGATION_BAR, true)
            .putBoolean(PREF_KEEP_SCREEN_ON, true)
            .putBoolean(PREF_USE_BACK_AS_ALTF4, false)
            .putBoolean(PREF_INVERT_SCROLLING, true)
            .apply()
    }

    fun displaySize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    fun buildUri(profile: ConnectionProfile, displayWidth: Int, displayHeight: Int): Uri {
        val (width, height) = profile.resolution.sizeOr(displayWidth, displayHeight)

        val authority = StringBuilder()
        if (profile.username.isNotBlank()) {
            authority.append(profile.username).append('@')
        }
        authority.append(profile.host)
        if (profile.port != 3389) {
            authority.append(':').append(profile.port)
        }

        val builder = Uri.Builder()
            .scheme("freerdp")
            .authority(authority.toString())
            .path("connect")
            .appendQueryParameter("v", "${profile.host}:${profile.port}")
            .appendQueryParameter("size", "${width}x$height")
            .appendQueryParameter("bpp", profile.colorDepth.toString())
            .appendQueryParameter("cert", "ignore")

        if (profile.security != Security.AUTO) {
            builder.appendQueryParameter("sec", profile.security.argument)
        }

        if (profile.username.isNotBlank()) {
            builder.appendQueryParameter("u", profile.username)
        }

        if (profile.domain.isNotBlank()) {
            builder.appendQueryParameter("d", profile.domain)
        }
        if (profile.password.isNotBlank()) {
            builder.appendQueryParameter("p", profile.password)
        }

        builder.appendQueryParameter("gfx", if (profile.h264) "AVC444" else "-")
        builder.appendQueryParameter("rfx", if (profile.remoteFx) "" else "-")
        builder.appendQueryParameter("themes", if (profile.themes) "" else "-")
        builder.appendQueryParameter("clipboard", if (profile.clipboard) "" else "-")
        builder.appendQueryParameter("sound", if (profile.audio) "" else "-")
        builder.appendQueryParameter("microphone", if (profile.microphone) "" else "-")

        if (profile.transport == Transport.UDP && UdpSupport.isAvailable()) {
            builder.appendQueryParameter(UdpSupport.OPTION, "")
        }

        return builder.build()
    }

    fun launch(context: Context, profile: ConnectionProfile): Boolean {
        if (profile.host.isBlank()) return false
        applySessionDefaults(context)
        val (width, height) = displaySize(context)
        val uri = buildUri(profile, width, height)
        SessionDiagnostics.appendLine(context, "Connecting: ${describeUri(uri)} (display ${width}x${height})")
        val intent = Intent()
            .setClassName(context, SESSION_ACTIVITY)
            .setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        context.startActivity(intent)
        return true
    }

    /** Renders the connection URI for logs with the password masked. */
    fun describeUri(uri: Uri): String {
        val masked = uri.buildUpon().clearQuery()
        uri.queryParameterNames
            .sorted()
            .forEach { name ->
                val value = uri.getQueryParameter(name)
                masked.appendQueryParameter(
                    name,
                    if (name == "p" && !value.isNullOrEmpty()) "***" else value
                )
            }
        return masked.build().toString()
    }

    fun isKeyboardPassthroughEnabled(context: Context): Boolean {
        val expected = ComponentName(context.packageName, KEYBOARD_SERVICE).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openKeyboardPassthroughSettings(context: Context) {
        val component = ComponentName(context.packageName, KEYBOARD_SERVICE)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra("android.extra.COMPONENT_NAME", component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

object UdpSupport {
    const val OPTION = "multitransport"

    fun isAvailable(): Boolean = false
}