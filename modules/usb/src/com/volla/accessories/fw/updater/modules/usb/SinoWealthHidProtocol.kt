package com.volla.accessories.fw.updater.modules.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.volla.accessories.fw.updater.core.FirmwareVersion
import com.volla.accessories.fw.updater.core.InfoRow
import com.volla.accessories.fw.updater.core.UpdateProgress
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class SinoWealthHidProtocol : UsbDeviceProtocol {

    override fun currentVersion(manager: UsbManager, device: UsbDevice): FirmwareVersion {
        if (isBootloader(device)) return FirmwareVersion.NONE
        return device.version
            ?.takeIf { it.isNotBlank() }
            ?.let(::FirmwareVersion)
            ?: FirmwareVersion.NONE
    }

    override fun details(manager: UsbManager, device: UsbDevice): List<InfoRow> = listOf(
        InfoRow("Protocol", "SinoWealth HID"),
        InfoRow("Mode", if (isBootloader(device)) "Update mode" else "Normal"),
        InfoRow(
            "Application region",
            "%d KB in %d pages".format(APPLICATION_SIZE / 1024, APPLICATION_SIZE / PAGE_SIZE),
        ),
    )

    private fun ensurePermission(manager: UsbManager, device: UsbDevice) {
        if (manager.hasPermission(device)) return
        try {
            manager.grantPermission(device)
        } catch (_: Exception) {
            // openDevice() reports failure to the caller if permission could not be granted.
        }
    }

    override fun install(manager: UsbManager, device: UsbDevice, firmware: ByteArray): Flow<UpdateProgress> = flow {
        emit(UpdateProgress.Preparing)

        val image = IntelHex.toImage(firmware, PAGE_SIZE, APPLICATION_SIZE)
        if (image.isEmpty()) throw IOException("Firmware image is empty")
        if (image.size != APPLICATION_SIZE) throw IOException("Firmware image does not fill the application region")
        relocateResetVector(image)
        val flashSize = image.size
        val pages = flashSize / PAGE_SIZE
        Log.i(TAG, "image $flashSize bytes, $pages pages, head ${hex(image, 0, 8)}")

        val target = if (isBootloader(device)) {
            device
        } else {
            ensurePermission(manager, device)
            val commandAccepted = enterIspMode(manager, device)
            awaitBootloader(manager) ?: if (commandAccepted) {
                throw IOException("Keyboard bootloader not exposed by Android USB service")
            } else {
                throw IOException("Could not switch keyboard into update mode")
            }
        }
        ensurePermission(manager, target)
        val connection = manager.openDevice(target) ?: throw IOException("Could not open keyboard")
        try {
            if (target.interfaceCount <= FLASH_INTERFACE) throw IOException("Keyboard bootloader interface is missing")
            val iface = target.getInterface(FLASH_INTERFACE)
            if (!connection.claimInterface(iface, true)) throw IOException("Could not claim keyboard interface")

            Log.i(TAG, "erasing")
            erase(connection)
            delay(ERASE_DELAY_MS)

            var writeFailure: IOException? = null
            for (attempt in 0 until WRITE_ATTEMPTS) {
                try {
                    beginWrite(connection, flashSize)
                    for (page in 0 until pages) {
                        writePage(connection, image, page, zeroFirstByte = page == 0)
                        delay(PAGE_DELAY_MS)
                        emit(UpdateProgress.Transferring(page.toFloat() / pages * WRITE_SHARE))
                    }
                    beginWrite(connection, flashSize)
                    writePage(connection, image, 0, zeroFirstByte = false)
                    writeFailure = null
                    break
                } catch (error: IOException) {
                    Log.w(TAG, "write attempt $attempt failed: ${error.message}")
                    writeFailure = error
                }
            }
            writeFailure?.let { throw it }

            emit(UpdateProgress.Verifying)
            var failedPage: Int? = null
            var verificationFailure: IOException? = null
            for (attempt in 0 until VERIFY_ATTEMPTS) {
                failedPage = null
                verificationFailure = null
                try {
                    beginRead(connection, flashSize)
                    for (page in 0 until pages) {
                        if (!verifyPage(connection, image, page)) {
                            Log.w(TAG, "verify attempt $attempt mismatched at page $page")
                            failedPage = page
                            break
                        }
                        emit(UpdateProgress.Transferring(WRITE_SHARE + page.toFloat() / pages * VERIFY_SHARE))
                    }
                } catch (error: IOException) {
                    Log.w(TAG, "verify attempt $attempt failed: ${error.message}")
                    verificationFailure = error
                }
                if (failedPage == null && verificationFailure == null) break
            }
            verificationFailure?.let { throw it }
            failedPage?.let { throw IOException("Verification failed at page $it") }

            preserveIdentity(connection)
            finish(connection)

            emit(UpdateProgress.Rebooting)
            reset(connection)
        } finally {
            connection.close()
        }
        awaitReboot(manager, target)
        emit(UpdateProgress.Done)
    }.flowOn(Dispatchers.IO)

    override val supportsDump: Boolean = true

    override suspend fun dump(manager: UsbManager, device: UsbDevice): ByteArray {
        val target = if (isBootloader(device)) {
            device
        } else {
            ensurePermission(manager, device)
            val commandAccepted = enterIspMode(manager, device)
            awaitBootloader(manager) ?: if (commandAccepted) {
                throw IOException("Keyboard bootloader not exposed by Android USB service")
            } else {
                throw IOException("Could not switch keyboard into update mode")
            }
        }
        ensurePermission(manager, target)
        val connection = manager.openDevice(target) ?: throw IOException("Could not open keyboard")
        val image = ByteArray(APPLICATION_SIZE)
        try {
            if (target.interfaceCount <= FLASH_INTERFACE) throw IOException("Keyboard bootloader interface is missing")
            val iface = target.getInterface(FLASH_INTERFACE)
            if (!connection.claimInterface(iface, true)) throw IOException("Could not claim keyboard interface")

            Log.i(TAG, "reading back $APPLICATION_SIZE bytes")
            beginRead(connection, APPLICATION_SIZE)
            for (page in 0 until APPLICATION_SIZE / PAGE_SIZE) {
                readPage(connection, image, page)
                delay(PAGE_DELAY_MS)
            }
            reset(connection)
        } finally {
            connection.close()
        }
        awaitReboot(manager, target)
        return image
    }

    private fun readPage(connection: UsbDeviceConnection, image: ByteArray, page: Int) {
        val payload = ByteArray(LONG_REPORT)
        payload[0] = REPORT_ID_LONG
        payload[1] = CMD_READ_PAGE
        val read = getReport(connection, FLASH_INTERFACE, payload, LONG_REPORT, WVALUE_LONG)
        if (read != LONG_REPORT) throw IOException("Keyboard returned $read bytes for page $page")
        System.arraycopy(payload, 2, image, page * PAGE_SIZE, PAGE_SIZE)
    }

    private suspend fun awaitReboot(manager: UsbManager, target: UsbDevice) {
        repeat(REBOOT_POLL_ATTEMPTS) {
            if (manager.deviceList.values.none { it.deviceName == target.deviceName }) {
                Log.i(TAG, "accessory left update mode")
                return
            }
            delay(REBOOT_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "accessory still in update mode after reset")
    }

    private fun enterIspMode(manager: UsbManager, device: UsbDevice): Boolean {
        if (device.interfaceCount <= ISP_INTERFACE) return false
        val connection = manager.openDevice(device) ?: return false
        return try {
            if (!connection.claimInterface(device.getInterface(ISP_INTERFACE), true)) return false
            val command = ByteArray(SHORT_REPORT)
            command[0] = REPORT_ID_SHORT
            command[1] = CMD_ENTER_ISP
            setReport(connection, ISP_INTERFACE, command, SHORT_REPORT, WVALUE_SHORT) == SHORT_REPORT
        } finally {
            connection.close()
        }
    }

    private fun erase(connection: UsbDeviceConnection) {
        val command = ByteArray(SHORT_REPORT) { CMD_ERASE }
        command[0] = REPORT_ID_SHORT
        if (setReport(connection, FLASH_INTERFACE, command, SHORT_REPORT, WVALUE_SHORT) != SHORT_REPORT) {
            throw IOException("Keyboard rejected the erase command")
        }
    }

    private fun beginWrite(connection: UsbDeviceConnection, length: Int) {
        val command = ByteArray(SHORT_REPORT)
        command[0] = REPORT_ID_SHORT
        command[1] = CMD_WRITE
        command[4] = (length and 0xff).toByte()
        command[5] = ((length shr 8) and 0xff).toByte()
        if (setReport(connection, FLASH_INTERFACE, command, SHORT_REPORT, WVALUE_SHORT) != SHORT_REPORT) {
            throw IOException("Keyboard rejected the write command")
        }
    }

    private fun writePage(connection: UsbDeviceConnection, image: ByteArray, page: Int, zeroFirstByte: Boolean) {
        val payload = ByteArray(LONG_REPORT)
        payload[0] = REPORT_ID_LONG
        payload[1] = CMD_WRITE_PAGE
        System.arraycopy(image, page * PAGE_SIZE, payload, 2, PAGE_SIZE)
        if (zeroFirstByte) payload[2] = 0x00
        if (setReport(connection, FLASH_INTERFACE, payload, LONG_REPORT, WVALUE_LONG) != LONG_REPORT) {
            throw IOException("Keyboard rejected data for page $page")
        }
    }

    private fun beginRead(connection: UsbDeviceConnection, length: Int) {
        val command = ByteArray(SHORT_REPORT)
        command[0] = REPORT_ID_SHORT
        command[1] = CMD_READ
        command[4] = (length and 0xff).toByte()
        command[5] = ((length shr 8) and 0xff).toByte()
        if (setReport(connection, FLASH_INTERFACE, command, SHORT_REPORT, WVALUE_SHORT) != SHORT_REPORT) {
            throw IOException("Keyboard rejected the read command")
        }
    }

    private suspend fun verifyPage(connection: UsbDeviceConnection, image: ByteArray, page: Int): Boolean {
        val payload = ByteArray(LONG_REPORT)
        payload[0] = REPORT_ID_LONG
        payload[1] = CMD_READ_PAGE
        val read = getReport(connection, FLASH_INTERFACE, payload, LONG_REPORT, WVALUE_LONG)
        if (read != LONG_REPORT) {
            Log.w(TAG, "page $page read returned $read, expected $LONG_REPORT")
            return false
        }
        delay(PAGE_DELAY_MS)
        val base = page * PAGE_SIZE
        var mismatches = 0
        var first = -1
        for (i in 0 until PAGE_SIZE) {
            if (payload[2 + i] != image[base + i]) {
                if (first < 0) first = i
                mismatches++
            }
        }
        if (mismatches == 0) return true
        Log.w(
            TAG,
            "page $page differs in $mismatches/$PAGE_SIZE bytes from offset $first: " +
                "expected ${hex(image, base + first, 8)} got ${hex(payload, 2 + first, 8)}",
        )
        Log.w(TAG, "page $page head expected ${hex(image, base, 8)} got ${hex(payload, 2, 8)}")
        return false
    }

    private fun hex(data: ByteArray, offset: Int, count: Int): String =
        (offset until minOf(offset + count, data.size)).joinToString(" ") { "%02x".format(data[it]) }

    private fun preserveIdentity(connection: UsbDeviceConnection) {
        val data = ByteArray(SHORT_REPORT)
        data[0] = REPORT_ID_SHORT
        data[1] = CMD_READ
        data[2] = 0x80.toByte()
        data[3] = 0xff.toByte()
        data[4] = 0x08
        setReport(connection, FLASH_INTERFACE, data, SHORT_REPORT, WVALUE_SHORT)

        data.fill(0)
        data[0] = REPORT_ID_SHORT
        data[1] = CMD_READ_PAGE
        getReport(connection, FLASH_INTERFACE, data, SHORT_REPORT, WVALUE_SHORT)
        val vid = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        val pid = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        getReport(connection, FLASH_INTERFACE, data, SHORT_REPORT, WVALUE_SHORT)

        data.fill(0)
        data[0] = REPORT_ID_SHORT
        data[1] = CMD_ERASE_REGION
        data[2] = 0xff.toByte()
        setReport(connection, FLASH_INTERFACE, data, SHORT_REPORT, WVALUE_SHORT)
        Thread.sleep(REGION_DELAY_MS)

        data.fill(0)
        data[0] = REPORT_ID_SHORT
        data[1] = CMD_WRITE
        data[2] = 0x80.toByte()
        data[3] = 0xff.toByte()
        data[4] = 0x08
        setReport(connection, FLASH_INTERFACE, data, SHORT_REPORT, WVALUE_SHORT)

        data.fill(0)
        data[0] = REPORT_ID_SHORT
        data[1] = CMD_WRITE_PAGE
        data[2] = ((vid shr 8) and 0xff).toByte()
        data[3] = (vid and 0xff).toByte()
        data[4] = ((pid shr 8) and 0xff).toByte()
        data[5] = (pid and 0xff).toByte()
        setReport(connection, FLASH_INTERFACE, data, SHORT_REPORT, WVALUE_SHORT)
        Thread.sleep(IDENTITY_DELAY_MS)

        data[2] = 0x01
        data[3] = 0x00
        data[4] = ((BURN_COUNT shr 8) and 0xff).toByte()
        data[5] = (BURN_COUNT and 0xff).toByte()
        setReport(connection, FLASH_INTERFACE, data, SHORT_REPORT, WVALUE_SHORT)
    }

    private fun finish(connection: UsbDeviceConnection) {
        val command = ByteArray(SHORT_REPORT) { CMD_FINISH }
        command[0] = REPORT_ID_SHORT
        setReport(connection, FLASH_INTERFACE, command, SHORT_REPORT, WVALUE_SHORT)
    }

    private fun reset(connection: UsbDeviceConnection) {
        val command = ByteArray(SHORT_REPORT)
        command[0] = REPORT_ID_SHORT
        command[1] = CMD_RESET
        setReport(connection, FLASH_INTERFACE, command, SHORT_REPORT, WVALUE_SHORT)
    }

    private fun relocateResetVector(image: ByteArray) {
        if (image.size <= VECTOR_OFFSET + 2) return
        val bootloaderVectorHi = (APPLICATION_SIZE shr 8).toByte()
        if (image[1] == bootloaderVectorHi && image[2] == 0x00.toByte()) {
            image[0] = image[VECTOR_OFFSET]
            image[1] = image[VECTOR_OFFSET + 1]
            image[2] = image[VECTOR_OFFSET + 2]
            image[VECTOR_OFFSET] = 0x00
            image[VECTOR_OFFSET + 1] = 0x00
            image[VECTOR_OFFSET + 2] = 0x00
        }
    }

    private suspend fun awaitBootloader(manager: UsbManager): UsbDevice? {
        repeat(BOOTLOADER_POLL_ATTEMPTS) {
            manager.deviceList.values.firstOrNull(::isBootloader)?.let { return it }
            delay(BOOTLOADER_POLL_INTERVAL_MS)
        }
        return null
    }

    private fun setReport(connection: UsbDeviceConnection, iface: Int, data: ByteArray, length: Int, wValue: Int): Int =
        connection.controlTransfer(REQ_TYPE_SET, REQ_SET_REPORT, wValue, iface, data, length, TIMEOUT_MS)

    private fun getReport(connection: UsbDeviceConnection, iface: Int, data: ByteArray, length: Int, wValue: Int): Int =
        connection.controlTransfer(REQ_TYPE_GET, REQ_GET_REPORT, wValue, iface, data, length, TIMEOUT_MS)

    companion object {
        private const val TAG = "SinoWealthHid"
        private const val APPLICATION_SIZE = 0x6000
        private const val PAGE_SIZE = 2048
        private const val SHORT_REPORT = 6
        private const val LONG_REPORT = 0x802

        private const val FLASH_INTERFACE = 0
        private const val ISP_INTERFACE = 1

        private const val REQ_TYPE_SET = 0x21
        private const val REQ_TYPE_GET = 0xA1
        private const val REQ_SET_REPORT = 0x09
        private const val REQ_GET_REPORT = 0x01
        private const val WVALUE_SHORT = 0x0305
        private const val WVALUE_LONG = 0x0306

        private const val REPORT_ID_SHORT: Byte = 0x05
        private const val REPORT_ID_LONG: Byte = 0x06
        private const val CMD_ENTER_ISP: Byte = 0x75
        private const val CMD_ERASE: Byte = 0x45
        private const val CMD_ERASE_REGION: Byte = 0x65
        private const val CMD_WRITE: Byte = 0x57
        private const val CMD_WRITE_PAGE: Byte = 0x77
        private const val CMD_READ: Byte = 0x52
        private const val CMD_READ_PAGE: Byte = 0x72
        private const val CMD_FINISH: Byte = 0x55
        private const val CMD_RESET: Byte = 0x5A

        private const val VECTOR_OFFSET = APPLICATION_SIZE - 5
        private const val BURN_COUNT = 1
        private const val TIMEOUT_MS = 500

        private const val BOOTLOADER_VENDOR_ID = 0x0603
        private const val BOOTLOADER_PRODUCT_ID = 0x8231
        private const val BOOTLOADER_POLL_ATTEMPTS = 50
        private const val BOOTLOADER_POLL_INTERVAL_MS = 100L
        private const val REBOOT_POLL_ATTEMPTS = 100
        private const val REBOOT_POLL_INTERVAL_MS = 200L
        private const val ERASE_DELAY_MS = 2000L
        private const val PAGE_DELAY_MS = 10L
        private const val WRITE_ATTEMPTS = 5
        private const val VERIFY_ATTEMPTS = 10
        private const val REGION_DELAY_MS = 200L
        private const val IDENTITY_DELAY_MS = 100L

        private const val WRITE_SHARE = 0.9f
        private const val VERIFY_SHARE = 0.1f

        fun isBootloader(device: UsbDevice): Boolean =
            device.vendorId == BOOTLOADER_VENDOR_ID && device.productId == BOOTLOADER_PRODUCT_ID
    }
}
