package com.volla.accessories.fw.updater.app.ui

import com.volla.accessories.fw.updater.core.Accessory
import com.volla.accessories.fw.updater.core.Capability
import com.volla.accessories.fw.updater.core.FirmwareCapability
import com.volla.accessories.fw.updater.core.FirmwareRelease
import com.volla.accessories.fw.updater.core.SettingsCapability
import com.volla.accessories.fw.updater.core.UpdateProgress

sealed interface Screen {
    data object List : Screen
    data class Detail(val accessory: Accessory) : Screen
}

data class FirmwareUiState(
    val capability: FirmwareCapability,
    val checking: Boolean = false,
    val available: FirmwareRelease? = null,
    val error: String? = null,
)

data class SettingsUiState(
    val capability: SettingsCapability,
    val values: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

data class AccessoryUiModel(
    val accessory: Accessory,
    val capabilities: List<Capability>,
    val firmware: FirmwareUiState?,
    val settings: SettingsUiState?,
)

data class InstallUiState(
    val accessoryId: String,
    val progress: UpdateProgress,
    val finished: Boolean,
)

data class DumpUiState(
    val accessoryId: String,
    val running: Boolean,
    val message: String? = null,
    val failed: Boolean = false,
)

data class ActionUiState(
    val accessoryId: String,
    val actionId: String,
    val running: Boolean,
    val message: String? = null,
    val failed: Boolean = false,
)

fun InstallUiState.matches(vararg candidates: Accessory): Boolean =
    candidates.any { it.id == accessoryId }

fun ActionUiState.matches(vararg candidates: Accessory): Boolean =
    candidates.any { it.id == accessoryId }

fun DumpUiState.matches(vararg candidates: Accessory): Boolean =
    candidates.any { it.id == accessoryId }

fun ManagerUiState.resolve(target: Accessory): AccessoryUiModel? =
    accessories.firstOrNull { it.accessory.id == target.id }
        ?: accessories.firstOrNull {
            it.accessory.moduleId == target.moduleId && it.accessory.productId == target.productId
        }

data class ManagerUiState(
    val loading: Boolean = true,
    val accessories: List<AccessoryUiModel> = emptyList(),
    val screen: Screen = Screen.List,
    val install: InstallUiState? = null,
    val action: ActionUiState? = null,
    val dump: DumpUiState? = null,
    val serviceUnlocked: Boolean = false,
)
