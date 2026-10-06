package com.dexrdp.engine

import java.nio.ByteBuffer

/**
 * JNI bridge to the IronRDP-based engine (libdexrdp_client.so).
 * This engine implements the RDP-UDP sideband (soft-sync + DVC tunnelling).
 */
object NativeRdp {

    init {
        System.loadLibrary("dexrdp_client")
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

    external fun nativeSendKey(handle: Long, scancode: Int, down: Boolean)
    external fun nativeSendMouse(handle: Long, x: Int, y: Int, flags: Int)
    external fun nativeDisconnect(handle: Long)
    external fun nativeFree(handle: Long)
}
