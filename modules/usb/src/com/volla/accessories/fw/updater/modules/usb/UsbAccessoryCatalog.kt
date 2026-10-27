package com.volla.accessories.fw.updater.modules.usb

data class UsbAccessoryType(
    val vendorId: Int,
    val productId: Int,
    val product: String,
    val displayName: String,
    val protocol: UsbDeviceProtocol,
)

object UsbAccessoryCatalog {

    private val sinoWealthHid = SinoWealthHidProtocol()

    private val vollaKeyboard = VollaKeyboardProtocol(sinoWealthHid)

    val supported: List<UsbAccessoryType> = listOf(
        UsbAccessoryType(
            vendorId = 0x258e,
            productId = 0x0066,
            product = "volla-tab-kb",
            displayName = "Volla Tablet Keyboard",
            protocol = vollaKeyboard,
        ),
        UsbAccessoryType(
            vendorId = 0x0603,
            productId = 0x8231,
            product = "volla-tab-kb",
            displayName = "Volla Tablet Keyboard (recovery mode)",
            protocol = sinoWealthHid,
        ),
    )

    fun match(vendorId: Int, productId: Int): UsbAccessoryType? =
        supported.firstOrNull { it.vendorId == vendorId && it.productId == productId }
}
