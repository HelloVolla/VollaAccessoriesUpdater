package com.volla.accessories.fw.updater.app

import android.content.Context
import com.volla.accessories.fw.updater.core.AccessoryModule
import com.volla.accessories.fw.updater.modules.usb.UsbAccessoryModule

fun defaultModules(context: Context): List<AccessoryModule> = listOf(
    UsbAccessoryModule(context.applicationContext),
)
