package com.volla.accessories.fw.updater.core

data class Accessory(
    val id: String,
    val moduleId: String,
    val productId: String,
    val displayName: String,
    val hardwareRevision: String,
)

data class ConnectedAccessory(
    val accessory: Accessory,
    val capabilities: List<Capability>,
)
