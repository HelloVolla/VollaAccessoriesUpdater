package com.volla.accessories.fw.updater.modules.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.volla.accessories.fw.updater.core.AccessoryAction
import com.volla.accessories.fw.updater.core.AccessorySetting
import com.volla.accessories.fw.updater.core.ChoiceSetting
import com.volla.accessories.fw.updater.core.ColorSetting
import com.volla.accessories.fw.updater.core.InfoRow
import com.volla.accessories.fw.updater.core.RangeSetting
import java.io.IOException

class VollaKeyboardProtocol(private val dfu: SinoWealthHidProtocol) : UsbDeviceProtocol by dfu {

    private var probedDevice: String? = null
    private var probedSupported = false

    override fun details(manager: UsbManager, device: UsbDevice): List<InfoRow> {
        val rows = dfu.details(manager, device).toMutableList()
        if (supported(manager, device)) rows += InfoRow("Backlight control", "Supported")
        return rows
    }

    override fun actions(manager: UsbManager, device: UsbDevice): List<AccessoryAction> {
        if (!supported(manager, device)) return emptyList()
        return buildList {
            for (slot in 0 until PROFILE_SLOTS) {
                add(
                    AccessoryAction(
                        id = "backlight.save.$slot",
                        title = "Save backlight to profile ${slot + 1}",
                        description = "Stores the current colour, brightness and timeout on the keyboard.",
                        perform = { profile(manager, device, OP_SAVE, slot) },
                    ),
                )
            }
            for (slot in 0 until PROFILE_SLOTS) {
                add(
                    AccessoryAction(
                        id = "backlight.load.$slot",
                        title = "Load backlight profile ${slot + 1}",
                        perform = { profile(manager, device, OP_LOAD, slot) },
                    ),
                )
            }
        }
    }

    override fun settings(manager: UsbManager, device: UsbDevice): List<AccessorySetting> =
        if (supported(manager, device)) SETTINGS else emptyList()

    override fun readSettings(manager: UsbManager, device: UsbDevice): Map<String, Int> =
        connect(manager, device) { connection ->
            val custom = read(connection, REPORT_CUSTOM, CUSTOM_LEN) ?: return@connect emptyMap()
            val profiles = read(connection, REPORT_PROFILES, PROFILES_LEN)
            buildMap {
                put(RGB, rgbOf(custom))
                put(PERIOD, word(custom, 5))
                if (profiles != null) put(TIMEOUT, timeoutIndex(word(profiles, 3)))
            }
        } ?: emptyMap()

    override fun writeSetting(manager: UsbManager, device: UsbDevice, id: String, value: Int) {
        connect(manager, device) { connection ->
            when (id) {
                RGB -> write(
                    connection,
                    report(REPORT_CUSTOM, MODE_CUSTOM, value shr 16, value shr 8, value, 0, 0, 0),
                )

                PERIOD -> {
                    val custom = read(connection, REPORT_CUSTOM, CUSTOM_LEN) ?: return@connect
                    write(
                        connection,
                        report(
                            REPORT_CUSTOM, custom[1].toInt(), custom[2].toInt(), custom[3].toInt(),
                            custom[4].toInt(), value, value shr 8, 0,
                        ),
                    )
                }

                TIMEOUT -> {
                    val seconds = TIMEOUT_SECONDS.getOrElse(value) { 0 }
                    write(connection, report(REPORT_PROFILES, OP_TIMEOUT, 0, seconds, seconds shr 8, 0))
                }
            }
        }
    }

    private fun profile(manager: UsbManager, device: UsbDevice, op: Int, slot: Int) {
        val done = connect(manager, device) { connection ->
            write(connection, report(REPORT_PROFILES, op, slot, 0, 0, 0))
        }
        if (done != true) throw IOException("The keyboard rejected the profile command")
    }

    private fun supported(manager: UsbManager, device: UsbDevice): Boolean {
        if (probedDevice == device.deviceName) return probedSupported
        val ok = connect(manager, device) { read(it, REPORT_INDEXED, INDEXED_LEN) != null } ?: false
        probedDevice = device.deviceName
        probedSupported = ok
        return ok
    }

    private fun <T> connect(manager: UsbManager, device: UsbDevice, block: (UsbDeviceConnection) -> T): T? {
        if (device.interfaceCount <= CONFIG_INTERFACE) return null
        if (!manager.hasPermission(device)) {
            try {
                manager.grantPermission(device)
            } catch (_: Exception) {
                return null
            }
        }
        val connection = manager.openDevice(device) ?: return null
        val iface = device.getInterface(CONFIG_INTERFACE)
        return try {
            if (!connection.claimInterface(iface, true)) null else block(connection)
        } finally {
            connection.releaseInterface(iface)
            connection.close()
        }
    }

    private fun read(connection: UsbDeviceConnection, reportId: Int, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        buffer[0] = reportId.toByte()
        val transferred = connection.controlTransfer(
            REQ_TYPE_GET, REQ_GET_REPORT, WVALUE_FEATURE or reportId, CONFIG_INTERFACE,
            buffer, length, TIMEOUT_MS,
        )
        return if (transferred == length) buffer else null
    }

    private fun write(connection: UsbDeviceConnection, payload: ByteArray): Boolean =
        connection.controlTransfer(
            REQ_TYPE_SET, REQ_SET_REPORT, WVALUE_FEATURE or (payload[0].toInt() and 0xff), CONFIG_INTERFACE,
            payload, payload.size, TIMEOUT_MS,
        ) == payload.size

    private fun report(vararg values: Int): ByteArray =
        ByteArray(values.size) { (values[it] and 0xff).toByte() }

    private fun word(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun rgbOf(custom: ByteArray): Int =
        ((custom[2].toInt() and 0xff) shl 16) or
            ((custom[3].toInt() and 0xff) shl 8) or
            (custom[4].toInt() and 0xff)

    private fun timeoutIndex(seconds: Int): Int {
        val exact = TIMEOUT_SECONDS.indexOf(seconds)
        if (exact >= 0) return exact
        return TIMEOUT_SECONDS.indices.minByOrNull { kotlin.math.abs(TIMEOUT_SECONDS[it] - seconds) } ?: 0
    }

    companion object {
        const val RGB = "backlight.rgb"
        const val PERIOD = "backlight.period"
        const val TIMEOUT = "backlight.timeout"

        private const val CONFIG_INTERFACE = 0

        private const val REPORT_INDEXED = 0x10
        private const val REPORT_CUSTOM = 0x11
        private const val REPORT_PROFILES = 0x12
        private const val INDEXED_LEN = 4
        private const val CUSTOM_LEN = 8
        private const val PROFILES_LEN = 6

        private const val MODE_CUSTOM = 1
        private const val OP_LOAD = 0
        private const val OP_SAVE = 1
        private const val OP_TIMEOUT = 2
        private const val PROFILE_SLOTS = 3

        private const val REQ_TYPE_SET = 0x21
        private const val REQ_TYPE_GET = 0xA1
        private const val REQ_SET_REPORT = 0x09
        private const val REQ_GET_REPORT = 0x01
        private const val WVALUE_FEATURE = 0x0300
        private const val TIMEOUT_MS = 500

        private const val PERIOD_MIN = 0x20
        private const val PERIOD_MAX = 0x0FFF
        private const val PERIOD_DEFAULT = 0xAA

        private val TIMEOUT_SECONDS = listOf(0, 15, 30, 60, 120, 300, 600)

        private val SETTINGS: List<AccessorySetting> = listOf(
            ColorSetting(
                id = RGB,
                title = "Backlight colour",
                description = "Darker colours are dimmer; black turns the backlight off.",
            ),
            ChoiceSetting(
                id = TIMEOUT,
                title = "Dim after",
                description = "The backlight returns on the next keypress.",
                options = listOf("Never", "15 s", "30 s", "1 min", "2 min", "5 min", "10 min"),
            ),
            RangeSetting(
                id = PERIOD,
                title = "PWM period",
                description = "Finer colour steps at a slower carrier. The stock $PERIOD_DEFAULT is " +
                    "flicker-free on this panel; raising it far past that is not.",
                advanced = true,
                min = PERIOD_MIN,
                max = PERIOD_MAX,
                step = 16,
            ),
        )
    }
}
