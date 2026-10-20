package com.dexrdp.rdp

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The floating six-dot toolbar is not used on DeX: the session is always driven
 * with a physical keyboard and mouse. It must not be inflated or wired up.
 */
class SessionFloatingToolbarTest {

    private fun repoFile(path: String) = File(path).readText()

    @Test
    fun `session layout does not inflate the floating toolbar`() {
        val layout = repoFile("../freeRDPCore/src/main/res/layout/session.xml")

        assertFalse(
            "session.xml must not include @layout/floating_toolbar",
            layout.contains("@layout/floating_toolbar")
        )
    }

    @Test
    fun `session activity does not wire a floating toolbar`() {
        val source = repoFile(
            "../freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java"
        )

        assertFalse(
            "SessionActivity must not create or position a FloatingToolbar",
            source.contains("FloatingToolbar")
        )
    }
}
