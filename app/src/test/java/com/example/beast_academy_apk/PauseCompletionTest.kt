package com.example.beast_academy_apk

import org.junit.Assert.*
import org.junit.Test

class PauseCompletionTest {
    @Test fun timeoutAndJavaScriptCallbackOnlyPauseOnce() {
        val completion = PauseCompletion()
        val ticket = completion.begin()
        assertTrue(completion.complete(ticket))
        assertFalse(completion.complete(ticket))
    }

    @Test fun resumeOrReplacementInvalidatesOldCleanup() {
        val completion = PauseCompletion()
        val beforeResume = completion.begin()
        completion.invalidate()
        assertFalse(completion.complete(beforeResume))
        val beforeReplacement = completion.begin()
        val current = completion.begin()
        assertFalse(completion.complete(beforeReplacement))
        assertTrue(completion.complete(current))
    }
}
