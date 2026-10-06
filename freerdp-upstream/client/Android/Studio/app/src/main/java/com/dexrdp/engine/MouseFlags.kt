package com.dexrdp.engine

/**
 * RDP pointer flags. Values mirror MS-RDPBCGR 2.2.9.1.1.3.1.1 (PTRFLAGS_*) and
 * the extended-mouse flags from 2.2.9.1.1.3.1.2 (PTRXFLAGS_*), and match the
 * FreeRDP engine's `Mouse.java` so both engines behave identically.
 */
object MouseFlags {

    // PTRFLAGS_*
    const val WHEEL_NEGATIVE = 0x0100
    const val VERTICAL_WHEEL = 0x0200
    const val HORIZONTAL_WHEEL = 0x0400
    const val MOVE = 0x0800
    const val LEFT_BUTTON = 0x1000
    const val RIGHT_BUTTON = 0x2000
    const val MIDDLE_BUTTON = 0x4000
    const val DOWN = 0x8000

    // PTRXFLAGS_* (extended mouse event, carries the X1/X2 side buttons)
    const val X_DOWN = 0x8000
    const val X_BUTTON1 = 0x0001 // back
    const val X_BUTTON2 = 0x0002 // forward

    /** One wheel notch, in rotation units (WHEEL_DELTA). */
    const val WHEEL_DELTA = 120
}
