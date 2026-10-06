package com.dexrdp.engine

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer

/**
 * RDP session screen driven by the IronRDP engine, which negotiates the
 * RDP-UDP sideband for graphics when the server offers it.
 */
class RdpSessionActivity : Activity(), NativeRdp.Callback {

    private var handle = 0L
    private var remoteView: RemoteView? = null
    private var frameBuffer: ByteBuffer? = null
    private var statusView: TextView? = null

    private var desktopWidth = 1920
    private var desktopHeight = 1080

    override fun onCreate(savedInstanceState: Bundle?) {
        val logFile = File(File(filesDir, "logs").apply { mkdirs() }, "freerdp.log")
        fun crumb(msg: String) = runCatching { logFile.appendText("engine: $msg\n") }
        crumb("onCreate entered")

        super.onCreate(savedInstanceState)
        crumb("super.onCreate done")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val host = intent.getStringExtra("host").orEmpty()
        val port = intent.getIntExtra("port", 3389)
        val user = intent.getStringExtra("user").orEmpty()
        val pass = intent.getStringExtra("pass").orEmpty()
        val domain = intent.getStringExtra("domain").orEmpty()
        desktopWidth = intent.getIntExtra("width", 1920)
        desktopHeight = intent.getIntExtra("height", 1080)

        crumb("building views")
        val root = FrameLayout(this)
        val view = RemoteView(this)
        remoteView = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val status = TextView(this).apply {
            text = "Connecting to $host:$port ..."
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        statusView = status
        root.addView(status, FrameLayout.LayoutParams(-2, -2))
        setContentView(root)
        crumb("content view set")
        hideSystemBars()
        crumb("system bars hidden")

        crumb("before ensureLoaded")
        try {
            NativeRdp.ensureLoaded()
            crumb("ensureLoaded ok")
        } catch (t: Throwable) {
            crumb("ensureLoaded threw: $t\n${t.stackTraceToString()}")
        }

        crumb("before setLogPath")
        try {
            NativeRdp.nativeSetLogPath(logFile.absolutePath)
            crumb("setLogPath ok")
        } catch (t: Throwable) {
            crumb("setLogPath threw: $t\n${t.stackTraceToString()}")
        }

        val buffer = ByteBuffer.allocateDirect(desktopWidth * desktopHeight * 4)
        frameBuffer = buffer

        crumb("calling nativeConnect")
        try {
            handle = NativeRdp.nativeConnect(
                host, port, user, pass, domain,
                desktopWidth, desktopHeight,
                buffer, this
            )
        } catch (t: Throwable) {
            crumb("nativeConnect threw: $t\n${t.stackTraceToString()}")
            status.text = "Engine error: $t"
        }
        crumb("nativeConnect returned handle=$handle")

        if (handle == 0L) {
            Toast.makeText(this, "Engine refused to start (see log)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConnected() {
        runOnUiThread { statusView?.visibility = View.GONE }
    }

    override fun onFrame(buffer: ByteBuffer, width: Int, height: Int) {
        remoteView?.updateFrame(buffer, width, height)
    }

    override fun onFailure(reason: String) {
        runOnUiThread {
            statusView?.text = "Connection failed:\n$reason"
            statusView?.visibility = View.VISIBLE
        }
    }

    override fun onTerminated() {
        runOnUiThread { finish() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handle == 0L) return super.onKeyDown(keyCode, event)
        val scan = KeyMap.scancode(keyCode)
        if (scan >= 0) {
            NativeRdp.nativeSendKey(handle, scan, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handle == 0L) return super.onKeyUp(keyCode, event)
        val scan = KeyMap.scancode(keyCode)
        if (scan >= 0) {
            NativeRdp.nativeSendKey(handle, scan, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> sendMouse(x, y, 0x1000 or 0x8000)
            MotionEvent.ACTION_UP -> sendMouse(x, y, 0x1000)
            MotionEvent.ACTION_MOVE -> sendMouse(x, y, 0x0800)
            else -> return false
        }
        return true
    }

    private fun sendMouse(x: Int, y: Int, flags: Int) {
        if (handle == 0L) return
        NativeRdp.nativeSendMouse(handle, x, y, flags)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (handle != 0L) {
            NativeRdp.nativeDisconnect(handle)
            NativeRdp.nativeFree(handle)
            handle = 0L
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }
}
