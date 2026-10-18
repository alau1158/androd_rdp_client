package com.dexrdp.rdp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android 17 (API 37) blocks TCP/UDP to local-network addresses unless the app
 * declares ACCESS_LOCAL_NETWORK in the manifest and holds the runtime grant.
 * Non-loopback connections are silently dropped (connect timeout) otherwise —
 * exactly the symptom that broke LAN sessions.
 */
class LocalNetworkAccessTest {

    @Test
    fun `manifest declares the local network permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must declare android.permission.ACCESS_LOCAL_NETWORK",
            manifest.contains("android.permission.ACCESS_LOCAL_NETWORK")
        )
    }

    @Test
    fun `ungranted app on enforced versions must request the permission`() {
        assertTrue(LocalNetworkAccess.shouldRequest(sdkInt = 37, granted = false))
    }

    @Test
    fun `opt-in android 16 devices must request it too`() {
        assertTrue(LocalNetworkAccess.shouldRequest(sdkInt = 36, granted = false))
    }

    @Test
    fun `granted app never re-requests`() {
        assertFalse(LocalNetworkAccess.shouldRequest(sdkInt = 37, granted = true))
    }

    @Test
    fun `versions below the feature never request`() {
        assertFalse(LocalNetworkAccess.shouldRequest(sdkInt = 35, granted = false))
    }
}
