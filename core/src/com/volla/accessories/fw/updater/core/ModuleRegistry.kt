package com.volla.accessories.fw.updater.core

class ModuleRegistry(modules: List<AccessoryModule>) {

    private val modules: List<AccessoryModule> = modules.filter { it.isAvailable() }

    fun all(): List<AccessoryModule> = modules

    fun find(moduleId: String): AccessoryModule? = modules.firstOrNull { it.id == moduleId }
}
