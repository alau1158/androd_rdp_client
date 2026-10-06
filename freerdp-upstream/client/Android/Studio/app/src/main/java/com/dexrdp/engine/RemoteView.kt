package com.dexrdp.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import java.nio.ByteBuffer

/** Draws the engine's ARGB framebuffer scaled to fill the view. */
class RemoteView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null

    fun updateFrame(buffer: ByteBuffer, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        var bmp = bitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = bmp
        }

        buffer.rewind()
        runCatching { bmp.copyPixelsFromBuffer(buffer) }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
    }
}
