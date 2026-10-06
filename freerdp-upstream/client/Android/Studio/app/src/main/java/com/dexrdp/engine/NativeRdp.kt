package com.dexrdp.engine

import java.nio.ByteBuffer

/**
 * JNI bridge to the IronRDP-based engine (libdexrdp_client.so).
 * This engine implements the RDP-UDP sideband (soft-sync + DVC tunnelling).
 */
object NativeRdp {

    @Volatile
    private var loaded = false

    /** Loads libdexrdp_client.so on demand. Called explicitly so the failure (or
     *  crash) happens after the caller has written a breadcrumb to the log. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("dexrdp_client")
        loaded = true
    }

    interface Callback {
        fun onConnected()
        fun onFrame(buffer: ByteBuffer, width: Int, height: Int)
        fun onFailure(reason: String)
        fun onTerminated()
    }

    external fun nativeConnect(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        width: Int,
        height: Int,
        frameBuffer: ByteBuffer,
        callback: Callback
    ): Long

    external fun nativeSetLogPath(path: String)

    external fun nativeSendKey(handle: Long, scancode: Int, down: Boolean, extended: Boolean)
    external fun nativeSendMouse(handle: Long, x: Int, y: Int, flags: Int, wheelUnits: Int)
    external fun nativeSendMouseEx(handle: Long, x: Int, y: Int, xflags: Int)
    external fun nativeDisconnect(handle: Long)
    external fun nativeFree(handle: Long)
}
