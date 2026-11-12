package io.github.jqssun.airplay.update

import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class UpdateAuthPolicyTest {
    @Test fun generatedCodeIsExactlyFourDecimalDigitsIncludingLeadingZeros() {
        assertEquals("0000", UpdateAuthPolicy.formatCode(0))
        assertEquals("0042", UpdateAuthPolicy.formatCode(42))
        assertEquals("9999", UpdateAuthPolicy.formatCode(9999))
        repeat(100) {
            val code = UpdateAuthPolicy.newFourDigitCode(SecureRandom())
            assertEquals(4, code.length)
            assertTrue(code.all(Char::isDigit))
        }
    }

    @Test fun codeModeRequiresExactFourDigitMatch() {
        assertEquals(UpdateAuthResult.ACCEPTED,
            UpdateAuthPolicy.authenticate(UpdateAuthMode.FOUR_DIGIT, "0042", "0042"))
        assertEquals(UpdateAuthResult.ACCEPTED,
            UpdateAuthPolicy.authenticate(UpdateAuthMode.FOUR_DIGIT, "0042", " 00 42 "))
        listOf(null, "", "42", "00042", "00a2", "9999").forEach {
            assertEquals(UpdateAuthResult.INVALID_CODE,
                UpdateAuthPolicy.authenticate(UpdateAuthMode.FOUR_DIGIT, "0042", it))
        }
    }

    @Test fun noCodeModeRequiresNoSecretButIsAnExplicitMode() {
        assertEquals(UpdateAuthResult.ACCEPTED,
            UpdateAuthPolicy.authenticate(UpdateAuthMode.NO_CODE, "", null))
        assertEquals(UpdateAuthResult.INVALID_CODE,
            UpdateAuthPolicy.authenticate(UpdateAuthMode.FOUR_DIGIT, "0042", null))
    }

    @Test fun fourDigitEntropyIsBoundedByFiveFailedAttemptsPerServerSession() {
        assertEquals(5, UpdateAuthPolicy.MAX_FAILED_CODES)
        // The server permanently closes this session on attempt five; it doesn't reset this count per connection.
        var failures = 0
        repeat(UpdateAuthPolicy.MAX_FAILED_CODES) {
            if (UpdateAuthPolicy.authenticate(UpdateAuthMode.FOUR_DIGIT, "0042", "0000") == UpdateAuthResult.INVALID_CODE) failures++
        }
        assertEquals(5, failures)
    }

    @Test fun codeFormattingRejectsOutOfRangeValues() {
        listOf(-1, 10000).forEach { value ->
            try {
                UpdateAuthPolicy.formatCode(value)
                fail("Expected $value to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
