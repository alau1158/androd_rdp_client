package com.dexrdp.engine

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.freerdp.freerdpcore.presentation.PhysicalKeyboardRouter
import java.io.File
import java.nio.ByteBuffer

/**
 * RDP session screen driven by the IronRDP engine, which negotiates the
 * RDP-UDP sideband for graphics when the server offers it.
 */
class RdpSessionActivity : Activity(), NativeRdp.Callback, PhysicalKeyboardRouter.Target {

    private var handle = 0L
    private var remoteView: RemoteView? = null
    private var frameBuffer: ByteBuffer? = null
    private var statusView: TextView? = null

    private var desktopWidth = 1920
    private var desktopHeight = 1080

    private var lastX = 0
    private var lastY = 0

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
        if (sendXButton(keyCode, true)) return true
        val scan = KeyMap.scancode(keyCode)
        if (scan >= 0) {
            NativeRdp.nativeSendKey(handle, scan, true, KeyMap.isExtended(keyCode))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handle == 0L) return super.onKeyUp(keyCode, event)
        if (sendXButton(keyCode, false)) return true
        val scan = KeyMap.scancode(keyCode)
        if (scan >= 0) {
            NativeRdp.nativeSendKey(handle, scan, false, KeyMap.isExtended(keyCode))
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        PhysicalKeyboardRouter.target = this
    }

    override fun onPause() {
        super.onPause()
        if (PhysicalKeyboardRouter.target === this) {
            PhysicalKeyboardRouter.target = null
        }
    }

    /**
     * Called from the accessibility service for hardware keys the system would
     * otherwise consume (notably the Windows/Super key). Returns false so keys
     * the engine cannot map still reach the system.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (handle == 0L) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (sendXButton(event.keyCode, down)) return true
        val scan = KeyMap.scancode(event.keyCode)
        if (scan < 0) return false
        NativeRdp.nativeSendKey(handle, scan, down, KeyMap.isExtended(event.keyCode))
        return true
    }

    /**
     * Sends the mouse side buttons as the RDP X1/X2 buttons. Android surfaces
     * them either as a key event (KEYCODE_BACK / KEYCODE_FORWARD, usually from a
     * mouse) or as MotionEvent BUTTON_BACK/BUTTON_FORWARD; both are routed here.
     * Returns false for keys that are not side buttons.
     */
    private fun sendXButton(keyCode: Int, down: Boolean): Boolean {
        val xflag = when (keyCode) {
            KeyEvent.KEYCODE_BACK -> MouseFlags.X_BUTTON1
            KeyEvent.KEYCODE_FORWARD -> MouseFlags.X_BUTTON2
            else -> return false
        }
        if (handle == 0L) return false
        sendMouseEx(lastX, lastY, xflag or if (down) MouseFlags.X_DOWN else 0)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L) return super.onTouchEvent(event)
        val (x, y) = toDesktop(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> sendMouse(x, y, MouseFlags.LEFT_BUTTON or MouseFlags.DOWN)
            MotionEvent.ACTION_UP -> sendMouse(x, y, MouseFlags.LEFT_BUTTON)
            MotionEvent.ACTION_MOVE ->
                sendMouse(x, y, MouseFlags.MOVE or buttonFlags(event.buttonState))
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (handle == 0L) return super.onGenericMotionEvent(event)
        val (x, y) = toDesktop(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val down = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                when (event.actionButton) {
                    MotionEvent.BUTTON_BACK ->
                        sendMouseEx(x, y, MouseFlags.X_BUTTON1 or if (down) MouseFlags.X_DOWN else 0)
                    MotionEvent.BUTTON_FORWARD ->
                        sendMouseEx(x, y, MouseFlags.X_BUTTON2 or if (down) MouseFlags.X_DOWN else 0)
                    MotionEvent.BUTTON_SECONDARY ->
                        sendMouse(x, y, MouseFlags.RIGHT_BUTTON or if (down) MouseFlags.DOWN else 0)
                    MotionEvent.BUTTON_TERTIARY ->
                        sendMouse(x, y, MouseFlags.MIDDLE_BUTTON or if (down) MouseFlags.DOWN else 0)
                    else -> return super.onGenericMotionEvent(event)
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (v != 0f) {
                    sendMouse(
                        x, y, MouseFlags.VERTICAL_WHEEL,
                        if (v > 0) -MouseFlags.WHEEL_DELTA else MouseFlags.WHEEL_DELTA
                    )
                }
                if (h != 0f) {
                    sendMouse(
                        x, y, MouseFlags.HORIZONTAL_WHEEL,
                        if (h > 0) MouseFlags.WHEEL_DELTA else -MouseFlags.WHEEL_DELTA
                    )
                }
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                sendMouse(x, y, MouseFlags.MOVE)
                return true
            }
            else -> return super.onGenericMotionEvent(event)
        }
    }

    private fun buttonFlags(buttonState: Int): Int {
        var flags = 0
        if (buttonState and MotionEvent.BUTTON_PRIMARY != 0) flags = flags or MouseFlags.LEFT_BUTTON
        if (buttonState and MotionEvent.BUTTON_SECONDARY != 0) flags = flags or MouseFlags.RIGHT_BUTTON
        if (buttonState and MotionEvent.BUTTON_TERTIARY != 0) flags = flags or MouseFlags.MIDDLE_BUTTON
        return flags
    }

    /** Maps view coordinates to desktop pixels (the view stretches to fill). */
    private fun toDesktop(x: Float, y: Float): Pair<Int, Int> {
        val view = remoteView
        val vw = view?.width ?: 0
        val vh = view?.height ?: 0
        val dx = if (vw > 0) (x * desktopWidth / vw).toInt() else x.toInt()
        val dy = if (vh > 0) (y * desktopHeight / vh).toInt() else y.toInt()
        val cx = dx.coerceIn(0, (desktopWidth - 1).coerceAtLeast(0))
        val cy = dy.coerceIn(0, (desktopHeight - 1).coerceAtLeast(0))
        return cx to cy
    }

    private fun sendMouse(x: Int, y: Int, flags: Int, wheelUnits: Int = 0) {
        if (handle == 0L) return
        lastX = x
        lastY = y
        NativeRdp.nativeSendMouse(handle, x, y, flags, wheelUnits)
    }

    private fun sendMouseEx(x: Int, y: Int, xflags: Int) {
        if (handle == 0L) return
        lastX = x
        lastY = y
        NativeRdp.nativeSendMouseEx(handle, x, y, xflags)
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
