package io.github.jqssun.airplay.update

import java.security.MessageDigest
import java.security.SecureRandom

enum class UpdateAuthMode { FOUR_DIGIT, NO_CODE }
enum class UpdateAuthResult { ACCEPTED, INVALID_CODE }

object UpdateAuthPolicy {
    const val MAX_FAILED_CODES = 5

    fun newFourDigitCode(random: SecureRandom = SecureRandom()): String = formatCode(random.nextInt(10_000))

    fun formatCode(value: Int): String {
        require(value in 0..9_999)
        return value.toString().padStart(4, '0')
    }

    fun authenticate(mode: UpdateAuthMode, expected: String, received: String?): UpdateAuthResult = when (mode) {
        UpdateAuthMode.NO_CODE -> UpdateAuthResult.ACCEPTED
        UpdateAuthMode.FOUR_DIGIT -> {
            val supplied = received.orEmpty().filterNot(Char::isWhitespace).toByteArray(Charsets.US_ASCII)
            val actual = expected.toByteArray(Charsets.US_ASCII)
            if (supplied.size == 4 && MessageDigest.isEqual(actual, supplied)) UpdateAuthResult.ACCEPTED
            else UpdateAuthResult.INVALID_CODE
        }
    }
}
