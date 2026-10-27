package com.volla.accessories.fw.updater.core

import kotlinx.coroutines.flow.Flow

sealed interface Capability

class InfoCapability(val rows: List<InfoRow>) : Capability

data class InfoRow(val label: String, val value: String)

class ActionsCapability(val actions: List<AccessoryAction>) : Capability

data class AccessoryAction(
    val id: String,
    val title: String,
    val description: String = "",
    val confirmation: String? = null,
    val destructive: Boolean = false,
    val perform: suspend () -> Unit,
)

class SettingsCapability(
    val settings: List<AccessorySetting>,
    val read: suspend () -> Map<String, Int>,
    val write: suspend (String, Int) -> Unit,
) : Capability

sealed interface AccessorySetting {
    val id: String
    val title: String
    val description: String
    val advanced: Boolean
}

data class ChoiceSetting(
    override val id: String,
    override val title: String,
    override val description: String = "",
    override val advanced: Boolean = false,
    val options: List<String>,
) : AccessorySetting

data class RangeSetting(
    override val id: String,
    override val title: String,
    override val description: String = "",
    override val advanced: Boolean = false,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    val unit: String = "",
) : AccessorySetting

data class ColorSetting(
    override val id: String,
    override val title: String,
    override val description: String = "",
    override val advanced: Boolean = false,
) : AccessorySetting

class FirmwareCapability(
    val currentVersion: FirmwareVersion,
    val install: (FirmwarePackage) -> Flow<UpdateProgress>,
    val dump: (suspend () -> ByteArray)? = null,
) : Capability

fun List<Capability>.info(): InfoCapability? = filterIsInstance<InfoCapability>().firstOrNull()

fun List<Capability>.actions(): ActionsCapability? = filterIsInstance<ActionsCapability>().firstOrNull()

fun List<Capability>.settings(): SettingsCapability? = filterIsInstance<SettingsCapability>().firstOrNull()

fun List<Capability>.firmware(): FirmwareCapability? = filterIsInstance<FirmwareCapability>().firstOrNull()
