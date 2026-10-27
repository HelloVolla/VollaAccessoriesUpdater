package com.volla.accessories.fw.updater.modules.usb

object IntelHex {

    private const val RECORD_DATA = 0x00
    private const val RECORD_EOF = 0x01
    private const val EMPTY: Byte = 0x00
    private const val RECORD_BYTES = 16

    fun toHex(image: ByteArray): String = buildString {
        for (offset in image.indices step RECORD_BYTES) {
            val length = minOf(RECORD_BYTES, image.size - offset)
            var checksum = length + ((offset shr 8) and 0xff) + (offset and 0xff)
            append(":%02X%04X00".format(length, offset))
            for (i in 0 until length) {
                val byte = image[offset + i].toInt() and 0xff
                checksum += byte
                append("%02X".format(byte))
            }
            append("%02X".format((-checksum) and 0xff))
            append('\n')
        }
        append(":00000001FF\n")
    }

    fun toImage(source: ByteArray, pageSize: Int, maxSize: Int): ByteArray {
        val scratch = ByteArray(maxSize) { EMPTY }
        var end = 0
        source.inputStream().bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.length < 11 || line[0] != ':') continue
                val length = line.substring(1, 3).toInt(16)
                val address = line.substring(3, 7).toInt(16)
                when (line.substring(7, 9).toInt(16)) {
                    RECORD_EOF -> break
                    RECORD_DATA -> for (i in 0 until length) {
                        val target = address + i
                        if (target < maxSize) {
                            scratch[target] = line.substring(9 + i * 2, 11 + i * 2).toInt(16).toByte()
                            if (target + 1 > end) end = target + 1
                        }
                    }
                }
            }
        }
        val pages = (end + pageSize - 1) / pageSize
        return scratch.copyOf(pages * pageSize)
    }
}
