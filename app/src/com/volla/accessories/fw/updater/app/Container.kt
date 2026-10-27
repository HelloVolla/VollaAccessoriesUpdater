package com.volla.accessories.fw.updater.app

import android.content.Context
import com.volla.accessories.fw.updater.core.AccessoryRepository
import com.volla.accessories.fw.updater.core.FirmwareService
import com.volla.accessories.fw.updater.core.ModuleRegistry
import com.volla.accessories.fw.updater.core.UpdateCoordinator

class Container(context: Context) {

    private val registry = ModuleRegistry(defaultModules(context))

    val firmwareService = FirmwareService()

    val accessories = AccessoryRepository(registry)

    val updates = UpdateCoordinator(firmwareService)
}
