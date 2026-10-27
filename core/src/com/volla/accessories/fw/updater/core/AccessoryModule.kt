package com.volla.accessories.fw.updater.core

import kotlinx.coroutines.flow.Flow

interface AccessoryModule {

    val id: String

    val displayName: String

    fun isAvailable(): Boolean

    fun accessories(): Flow<List<Accessory>>

    fun capabilities(accessory: Accessory): List<Capability>
}
