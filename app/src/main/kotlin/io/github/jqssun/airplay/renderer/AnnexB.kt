package io.github.jqssun.airplay.renderer

/** Parameter sets can configure a decoder, but do not replace an IDR/IRAP reference picture. */
object AnnexB {
    fun canBootstrap(data: ByteArray, h265: Boolean): Boolean = scan(data, h265, bootstrap = true)
    fun hasRandomAccessPicture(data: ByteArray, h265: Boolean): Boolean = scan(data, h265, bootstrap = false)

    private fun scan(data: ByteArray, h265: Boolean, bootstrap: Boolean): Boolean {
        var index = 0
        while (index + 3 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                val header = when {
                    data[index + 2] == 1.toByte() -> index + 3
                    index + 4 < data.size && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> index + 4
                    else -> -1
                }
                if (header >= 0) {
                    val type = if (h265) (data[header].toInt() ushr 1) and 0x3f else data[header].toInt() and 0x1f
                    if (h265 && (type in 16..21 || (bootstrap && type in 32..33))) return true
                    if (!h265 && (type == 5 || (bootstrap && type == 7))) return true
                    // A normal VCL picture cannot become random access through later slices in this access unit.
                    if (!bootstrap && ((h265 && type in 0..15) || (!h265 && type in 1..4))) return false
                    index = header
                }
            }
            index++
        }
        return false
    }
}
