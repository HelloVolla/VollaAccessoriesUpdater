package com.volla.accessories.fw.updater.core

data class FirmwareVersion(val value: String) : Comparable<FirmwareVersion> {

    private val parts: List<Int> = value
        .trim()
        .split('.', '-', '+')
        .mapNotNull { it.toIntOrNull() }

    override fun compareTo(other: FirmwareVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (index in 0 until size) {
            val diff = parts.getOrElse(index) { 0 } - other.parts.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString(): String = value

    companion object {
        val NONE = FirmwareVersion("0.0.0")
    }
}
