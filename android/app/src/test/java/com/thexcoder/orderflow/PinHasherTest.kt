package com.thexcoder.orderflow

import com.thexcoder.orderflow.core.PinHasher
import org.junit.Assert.*
import org.junit.Test

class PinHasherTest {
    @Test fun pinVerifierAcceptsOnlyCorrectPin() {
        val verifier = PinHasher.create("123456")
        assertTrue(PinHasher.verify("123456", verifier))
        assertFalse(PinHasher.verify("654321", verifier))
        assertFalse(verifier.contains("123456"))
    }
    @Test fun saltMakesEqualPinsHaveDifferentVerifiers() {
        assertNotEquals(PinHasher.create("123456"), PinHasher.create("123456"))
    }
    @Test fun malformedVerifiersFailClosed() {
        assertFalse(PinHasher.verify("123456", "not-a-verifier"))
        assertFalse(PinHasher.verify("123456", "pbkdf2-sha256\$999999999\$bad\$bad"))
    }
    @Test fun tooShortOrNonDigitPinsAreRejected() {
        for (pin in listOf("1234", "abcdef", "1234567890123", " 123456")) {
            try { PinHasher.create(pin); fail("Invalid PIN accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
