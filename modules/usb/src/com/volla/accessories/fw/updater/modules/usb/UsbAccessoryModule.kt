package com.volla.accessories.fw.updater.modules.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.IOException
import com.volla.accessories.fw.updater.core.Accessory
import com.volla.accessories.fw.updater.core.AccessoryModule
import com.volla.accessories.fw.updater.core.ActionsCapability
import com.volla.accessories.fw.updater.core.Capability
import com.volla.accessories.fw.updater.core.FirmwareCapability
import com.volla.accessories.fw.updater.core.InfoCapability
import com.volla.accessories.fw.updater.core.InfoRow
import com.volla.accessories.fw.updater.core.SettingsCapability
import com.volla.accessories.fw.updater.core.UpdateProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class UsbAccessoryModule(private val context: Context) : AccessoryModule {

    override val id: String = "usb"

    override val displayName: String = "USB"

    private val manager: UsbManager? = context.getSystemService(UsbManager::class.java)

    override fun isAvailable(): Boolean =
        manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    override fun accessories(): Flow<List<Accessory>> = callbackFlow {
        val usb = manager ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                launch(Dispatchers.IO) { trySend(scan(usb)) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter)
        launch(Dispatchers.IO) { trySend(scan(usb)) }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    override fun capabilities(accessory: Accessory): List<Capability> {
        val usb = manager ?: return emptyList()
        val device = device(usb, accessory) ?: return emptyList()
        val type = UsbAccessoryCatalog.match(device.vendorId, device.productId) ?: return emptyList()
        val protocol = type.protocol

        val rows = buildList {
            add(InfoRow("Connection", "USB"))
            add(InfoRow("Product", type.product))
            add(InfoRow("USB ID", "%04x:%04x".format(device.vendorId, device.productId)))
            device.serialNumber?.takeIf { it.isNotBlank() }?.let { add(InfoRow("Serial", it)) }
            addAll(protocol.details(usb, device))
        }
        val actions = protocol.actions(usb, device)
        val settings = protocol.settings(usb, device)

        return buildList {
            add(InfoCapability(rows))
            if (settings.isNotEmpty()) {
                add(
                    SettingsCapability(
                        settings = settings,
                        read = { device(usb, accessory)?.let { protocol.readSettings(usb, it) } ?: emptyMap() },
                        write = { id, value ->
                            val current = device(usb, accessory) ?: throw IOException("Accessory disconnected")
                            protocol.writeSetting(usb, current, id, value)
                        },
                    ),
                )
            }
            if (actions.isNotEmpty()) add(ActionsCapability(actions))
            add(
                FirmwareCapability(
                    currentVersion = protocol.currentVersion(usb, device),
                    install = { firmware ->
                        val current = device(usb, accessory)
                        if (current == null) {
                            flowOf(UpdateProgress.Failed("Accessory disconnected"))
                        } else {
                            protocol.install(usb, current, firmware.data)
                        }
                    },
                    dump = if (!protocol.supportsDump) {
                        null
                    } else {
                        {
                            val current = device(usb, accessory) ?: throw IOException("Accessory disconnected")
                            IntelHex.toHex(protocol.dump(usb, current)).toByteArray()
                        }
                    },
                ),
            )
        }
    }

    private fun device(usb: UsbManager, accessory: Accessory): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.deviceName == accessory.id }

    private fun scan(usb: UsbManager): List<Accessory> =
        usb.deviceList.values.mapNotNull { device ->
            val type = UsbAccessoryCatalog.match(device.vendorId, device.productId) ?: return@mapNotNull null
            Accessory(
                id = device.deviceName,
                moduleId = id,
                productId = type.product,
                displayName = type.displayName,
                hardwareRevision = "",
            )
        }
}
