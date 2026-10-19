package com.example.beast_academy_apk

import org.junit.Assert.*
import org.junit.Test

class SessionVisibilityTest {
    @Test fun lifecycleResumeCannotReopenCover() {
        val session = SessionVisibility()
        session.resume()
        assertTrue(session.closeCover())
        session.pause()
        session.resume()
        assertFalse(session.canRun)
        assertFalse(session.closeCover())
        session.screenOff()
        session.screenOn()
        assertTrue(session.canRun)
    }

    @Test fun screenOnCannotResumeBackgroundActivity() {
        val session = SessionVisibility()
        session.resume()
        session.screenOff()
        session.pause()
        session.screenOn()
        assertFalse(session.canRun)
        session.resume()
        assertTrue(session.canRun)
    }

    @Test fun screenOffWinsRegardlessOfPauseOrdering() {
        for (pauseFirst in listOf(true, false)) {
            val session = SessionVisibility()
            session.resume()
            if (pauseFirst) session.pause()
            session.screenOff()
            if (!pauseFirst) session.pause()
            session.resume()
            assertFalse(session.canRun)
            session.screenOn()
            assertTrue(session.canRun)
        }
    }

    @Test fun creationInBackgroundAndDestructionDoNotResume() {
        val session = SessionVisibility(screenInteractive = false)
        session.resume()
        assertFalse(session.canRun)
        session.screenOn()
        assertTrue(session.canRun)
        session.destroy()
        session.screenOn()
        session.resume()
        assertFalse(session.canRun)
        assertFalse(session.closeCover())
    }
}
