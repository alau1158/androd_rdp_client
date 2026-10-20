package com.dexrdp.rdp

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The session view is focusable, fills the window and has no background. Android
 * therefore paints its default focus highlight (a translucent white fill) over the
 * whole view whenever the view leaves touch mode on a key press. On the remote
 * desktop that reads as a white glaze covering the entire app, and it stays until
 * the activity is recreated.
 *
 * The highlight must stay disabled: the remote surface already paints every pixel.
 */
class SessionFocusHighlightTest {

    private fun repoFile(path: String) = File(path).readText()

    @Test
    fun `session view disables android's default focus highlight`() {
        val layout = repoFile("../freeRDPCore/src/main/res/layout/session.xml")
        val sessionView = Regex(
            """<com\.freerdp\.freerdpcore\.presentation\.SessionView\b[\s\S]*?/>"""
        ).find(layout)?.value

        assertNotNull("session.xml must contain the SessionView", sessionView)
        assertTrue(
            "SessionView must set android:defaultFocusHighlightEnabled=\"false\"; " +
                "otherwise a key press paints a white focus glaze over the session",
            sessionView!!.contains("android:defaultFocusHighlightEnabled=\"false\"")
        )
    }

    @Test
    fun `session scroll container disables android's default focus highlight`() {
        val layout = repoFile("../freeRDPCore/src/main/res/layout/session.xml")
        val scrollView = Regex(
            """<com\.freerdp\.freerdpcore\.presentation\.ScrollView2D\b[\s\S]*?>"""
        ).find(layout)?.value

        assertNotNull("session.xml must contain the ScrollView2D", scrollView)
        assertTrue(
            "ScrollView2D is full-screen, focusable and has no background; it must also " +
                "set android:defaultFocusHighlightEnabled=\"false\"",
            scrollView!!.contains("android:defaultFocusHighlightEnabled=\"false\"")
        )
    }

    @Test
    fun `session view disables the highlight when constructed programmatically`() {
        val source = repoFile(
            "../freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionView.java"
        )

        assertTrue(
            "SessionView.initSessionView() must call setDefaultFocusHighlightEnabled(false) " +
                "as well, so programmatic construction cannot reintroduce the glaze",
            source.contains("setDefaultFocusHighlightEnabled(false)")
        )
    }
}
