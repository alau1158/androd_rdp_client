package com.dexrdp.rdp

/**
 * Android 17 (API 37) enforcement of Local Network Protection: an app targeting
 * SDK 37+ that has not been granted ACCESS_LOCAL_NETWORK has every non-loopback
 * connection to a LAN address silently dropped in the kernel — connect() just
 * times out with no error, no log, and no packet leaving the device.
 *
 * On Android 16 (API 36) the same permission exists as an opt-in control, so we
 * request there too; below 36 plain INTERNET is enough.
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val MIN_SDK = 36

    fun shouldRequest(sdkInt: Int, granted: Boolean): Boolean =
        sdkInt >= MIN_SDK && !granted
}
