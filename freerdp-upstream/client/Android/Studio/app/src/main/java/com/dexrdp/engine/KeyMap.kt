package com.dexrdp.engine

import android.view.KeyEvent

/** Minimal Android keycode -> PC (set 1) scancode mapping. */
object KeyMap {

    fun scancode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> 0x01
        KeyEvent.KEYCODE_1 -> 0x02
        KeyEvent.KEYCODE_2 -> 0x03
        KeyEvent.KEYCODE_3 -> 0x04
        KeyEvent.KEYCODE_4 -> 0x05
        KeyEvent.KEYCODE_5 -> 0x06
        KeyEvent.KEYCODE_6 -> 0x07
        KeyEvent.KEYCODE_7 -> 0x08
        KeyEvent.KEYCODE_8 -> 0x09
        KeyEvent.KEYCODE_9 -> 0x0A
        KeyEvent.KEYCODE_0 -> 0x0B
        KeyEvent.KEYCODE_MINUS -> 0x0C
        KeyEvent.KEYCODE_EQUALS -> 0x0D
        KeyEvent.KEYCODE_DEL -> 0x0E
        KeyEvent.KEYCODE_TAB -> 0x0F
        KeyEvent.KEYCODE_Q -> 0x10
        KeyEvent.KEYCODE_W -> 0x11
        KeyEvent.KEYCODE_E -> 0x12
        KeyEvent.KEYCODE_R -> 0x13
        KeyEvent.KEYCODE_T -> 0x14
        KeyEvent.KEYCODE_Y -> 0x15
        KeyEvent.KEYCODE_U -> 0x16
        KeyEvent.KEYCODE_I -> 0x17
        KeyEvent.KEYCODE_O -> 0x18
        KeyEvent.KEYCODE_P -> 0x19
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1A
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1B
        KeyEvent.KEYCODE_ENTER -> 0x1C
        KeyEvent.KEYCODE_CTRL_LEFT -> 0x1D
        KeyEvent.KEYCODE_A -> 0x1E
        KeyEvent.KEYCODE_S -> 0x1F
        KeyEvent.KEYCODE_D -> 0x20
        KeyEvent.KEYCODE_F -> 0x21
        KeyEvent.KEYCODE_G -> 0x22
        KeyEvent.KEYCODE_H -> 0x23
        KeyEvent.KEYCODE_J -> 0x24
        KeyEvent.KEYCODE_K -> 0x25
        KeyEvent.KEYCODE_L -> 0x26
        KeyEvent.KEYCODE_SEMICOLON -> 0x27
        KeyEvent.KEYCODE_APOSTROPHE -> 0x28
        KeyEvent.KEYCODE_GRAVE -> 0x29
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0x2A
        KeyEvent.KEYCODE_BACKSLASH -> 0x2B
        KeyEvent.KEYCODE_Z -> 0x2C
        KeyEvent.KEYCODE_X -> 0x2D
        KeyEvent.KEYCODE_C -> 0x2E
        KeyEvent.KEYCODE_V -> 0x2F
        KeyEvent.KEYCODE_B -> 0x30
        KeyEvent.KEYCODE_N -> 0x31
        KeyEvent.KEYCODE_M -> 0x32
        KeyEvent.KEYCODE_COMMA -> 0x33
        KeyEvent.KEYCODE_PERIOD -> 0x34
        KeyEvent.KEYCODE_SLASH -> 0x35
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x36
        KeyEvent.KEYCODE_ALT_LEFT -> 0x38
        KeyEvent.KEYCODE_SPACE -> 0x39
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x3A
        KeyEvent.KEYCODE_F1 -> 0x3B
        KeyEvent.KEYCODE_F2 -> 0x3C
        KeyEvent.KEYCODE_F3 -> 0x3D
        KeyEvent.KEYCODE_F4 -> 0x3E
        KeyEvent.KEYCODE_F5 -> 0x3F
        KeyEvent.KEYCODE_F6 -> 0x40
        KeyEvent.KEYCODE_F7 -> 0x41
        KeyEvent.KEYCODE_F8 -> 0x42
        KeyEvent.KEYCODE_F9 -> 0x43
        KeyEvent.KEYCODE_F10 -> 0x44
        KeyEvent.KEYCODE_NUM_LOCK -> 0x45
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x46
        KeyEvent.KEYCODE_F11 -> 0x57
        KeyEvent.KEYCODE_F12 -> 0x58
        KeyEvent.KEYCODE_META_LEFT -> 0x5B
        KeyEvent.KEYCODE_META_RIGHT -> 0x5C
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0x1D
        KeyEvent.KEYCODE_ALT_RIGHT -> 0x38
        KeyEvent.KEYCODE_MOVE_HOME -> 0x47
        KeyEvent.KEYCODE_DPAD_UP -> 0x48
        KeyEvent.KEYCODE_PAGE_UP -> 0x49
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x4B
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4D
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x50
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x51
        KeyEvent.KEYCODE_MOVE_END -> 0x4F
        KeyEvent.KEYCODE_INSERT -> 0x52
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x53
        else -> -1
    }

    /** True for keys the RDP protocol sends with the extended (0xE0) prefix. */
    fun isExtended(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_INSERT,
        KeyEvent.KEYCODE_FORWARD_DEL -> true
        else -> false
    }
}
