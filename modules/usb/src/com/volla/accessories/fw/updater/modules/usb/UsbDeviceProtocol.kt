package com.volla.accessories.fw.updater.modules.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.volla.accessories.fw.updater.core.AccessoryAction
import com.volla.accessories.fw.updater.core.AccessorySetting
import com.volla.accessories.fw.updater.core.FirmwareVersion
import com.volla.accessories.fw.updater.core.InfoRow
import com.volla.accessories.fw.updater.core.UpdateProgress
import kotlinx.coroutines.flow.Flow

interface UsbDeviceProtocol {

    fun currentVersion(manager: UsbManager, device: UsbDevice): FirmwareVersion

    fun details(manager: UsbManager, device: UsbDevice): List<InfoRow> = emptyList()

    fun actions(manager: UsbManager, device: UsbDevice): List<AccessoryAction> = emptyList()

    fun settings(manager: UsbManager, device: UsbDevice): List<AccessorySetting> = emptyList()

    fun readSettings(manager: UsbManager, device: UsbDevice): Map<String, Int> = emptyMap()

    fun writeSetting(manager: UsbManager, device: UsbDevice, id: String, value: Int) = Unit

    fun install(manager: UsbManager, device: UsbDevice, firmware: ByteArray): Flow<UpdateProgress>

    val supportsDump: Boolean get() = false

    suspend fun dump(manager: UsbManager, device: UsbDevice): ByteArray =
        throw UnsupportedOperationException("$this cannot read firmware back")
}
