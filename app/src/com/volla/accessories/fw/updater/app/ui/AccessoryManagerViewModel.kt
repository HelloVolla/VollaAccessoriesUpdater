package com.volla.accessories.fw.updater.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.volla.accessories.fw.updater.app.AccessoriesApplication
import com.volla.accessories.fw.updater.core.Accessory
import com.volla.accessories.fw.updater.core.AccessoryAction
import com.volla.accessories.fw.updater.core.FirmwareCapability
import com.volla.accessories.fw.updater.core.FirmwarePackage
import com.volla.accessories.fw.updater.core.FirmwareRelease
import com.volla.accessories.fw.updater.core.FirmwareVersion
import com.volla.accessories.fw.updater.core.SettingsCapability
import com.volla.accessories.fw.updater.core.UpdateProgress
import com.volla.accessories.fw.updater.core.firmware
import com.volla.accessories.fw.updater.core.settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccessoryManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AccessoriesApplication).container

    private val checks = MutableStateFlow<Map<String, CheckState>>(emptyMap())
    private val settingsStates = MutableStateFlow<Map<String, SettingsState>>(emptyMap())
    private val screen = MutableStateFlow<Screen>(Screen.List)
    private val installState = MutableStateFlow<InstallUiState?>(null)
    private val actionState = MutableStateFlow<ActionUiState?>(null)
    private val serviceUnlock = MutableStateFlow(false)
    private val dumpState = MutableStateFlow<DumpUiState?>(null)
    private val foreground = MutableStateFlow(false)

    private val transient = combine(
        installState,
        actionState,
        dumpState,
        serviceUnlock,
    ) { install, action, dump, unlocked ->
        Transient(install, action, dump, unlocked)
    }

    val uiState: StateFlow<ManagerUiState> = combine(
        container.accessories.accessories(),
        checks,
        settingsStates,
        screen,
        transient,
    ) { accessories, checkState, settingState, currentScreen, state ->
        val (install, action, dump, unlocked) = state
        ManagerUiState(
            loading = false,
            accessories = accessories.map { connected ->
                val firmware = connected.capabilities.firmware()
                val settings = connected.capabilities.settings()
                val check = checkState[connected.accessory.id]
                val values = settingState[connected.accessory.id]
                AccessoryUiModel(
                    accessory = connected.accessory,
                    capabilities = connected.capabilities,
                    firmware = firmware?.let {
                        FirmwareUiState(
                            capability = it,
                            checking = check?.checking ?: false,
                            available = check?.release,
                            error = check?.error,
                        )
                    },
                    settings = settings?.let {
                        SettingsUiState(
                            capability = it,
                            values = values?.values ?: emptyMap(),
                            loading = values?.loading ?: true,
                            busy = values?.busy ?: false,
                            error = values?.error,
                        )
                    },
                )
            },
            screen = currentScreen,
            install = install,
            action = action,
            dump = dump,
            serviceUnlocked = unlocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ManagerUiState())

    init {
        viewModelScope.launch {
            combine(screen, foreground) { current, visible ->
                (current as? Screen.Detail)?.accessory?.takeIf { visible }
            }.distinctUntilChanged().collectLatest { accessory ->
                if (accessory == null) return@collectLatest
                while (true) {
                    delay(SETTINGS_POLL_MS)
                    val model = uiState.value.resolve(accessory) ?: continue
                    model.settings?.let { refreshSettings(model.accessory, it.capability) }
                }
            }
        }
    }

    fun setForeground(visible: Boolean) {
        foreground.value = visible
    }

    fun open(model: AccessoryUiModel) {
        screen.value = Screen.Detail(model.accessory)
        model.firmware?.let { check(model.accessory, it.capability) }
        model.settings?.let { loadSettings(model.accessory, it.capability) }
    }

    fun back() {
        screen.value = Screen.List
        installState.value = null
        actionState.value = null
    }

    fun check(accessory: Accessory, capability: FirmwareCapability) {
        viewModelScope.launch {
            checks.update {
                it + (accessory.id to CheckState(checking = true, release = null, error = null))
            }
            val result = runCatching { container.updates.latestUpdate(accessory, capability) }
            checks.update {
                it + (accessory.id to CheckState(
                    checking = false,
                    release = result.getOrNull(),
                    error = result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName,
                ))
            }
        }
    }

    fun loadSettings(accessory: Accessory, capability: SettingsCapability) {
        viewModelScope.launch {
            settingsStates.update { states ->
                val current = states[accessory.id] ?: SettingsState()
                states + (accessory.id to current.copy(loading = true, error = null))
            }
            val result = runCatching { withContext(Dispatchers.IO) { capability.read() } }
            settingsStates.update { states ->
                states + (accessory.id to SettingsState(
                    values = result.getOrNull() ?: states[accessory.id]?.values ?: emptyMap(),
                    loading = false,
                    busy = false,
                    error = result.exceptionOrNull()?.message,
                ))
            }
        }
    }

    fun refreshSettings(accessory: Accessory, capability: SettingsCapability) {
        if (installState.value?.finished == false) return
        if (dumpState.value?.running == true) return
        if (settingsStates.value[accessory.id]?.busy == true) return
        viewModelScope.launch {
            val values = runCatching { withContext(Dispatchers.IO) { capability.read() } }.getOrNull()
            if (values.isNullOrEmpty()) return@launch
            settingsStates.update { states ->
                val current = states[accessory.id] ?: SettingsState()
                if (current.busy) states else states + (accessory.id to current.copy(values = values))
            }
        }
    }

    fun setSetting(accessory: Accessory, capability: SettingsCapability, id: String, value: Int) {
        viewModelScope.launch {
            settingsStates.update { states ->
                val current = states[accessory.id] ?: SettingsState()
                states + (accessory.id to current.copy(
                    values = current.values + (id to value),
                    busy = true,
                    error = null,
                ))
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    capability.write(id, value)
                    capability.read()
                }
            }
            settingsStates.update { states ->
                val current = states[accessory.id] ?: SettingsState()
                states + (accessory.id to current.copy(
                    values = result.getOrNull()?.takeIf { it.isNotEmpty() } ?: current.values,
                    busy = false,
                    error = result.exceptionOrNull()?.message,
                ))
            }
        }
    }

    fun runAction(accessory: Accessory, action: AccessoryAction) {
        viewModelScope.launch {
            actionState.value = ActionUiState(accessory.id, action.id, running = true)
            val result = runCatching { withContext(Dispatchers.IO) { action.perform() } }
            uiState.value.resolve(accessory)?.let { model ->
                model.settings?.let { loadSettings(model.accessory, it.capability) }
            }
            actionState.value = ActionUiState(
                accessoryId = accessory.id,
                actionId = action.id,
                running = false,
                message = result.exceptionOrNull()?.message
                    ?: result.exceptionOrNull()?.javaClass?.simpleName
                    ?: "${action.title} done",
                failed = result.isFailure,
            )
        }
    }

    fun dismissAction() {
        actionState.value = null
    }

    fun dismissInstall() {
        installState.value = null
    }

    fun dismissDump() {
        dumpState.value = null
    }

    fun dumpFirmware(accessory: Accessory, capability: FirmwareCapability, target: Uri) {
        val read = capability.dump ?: return
        viewModelScope.launch {
            dumpState.value = DumpUiState(accessory.id, running = true)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val image = read()
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(target, "wt")?.use { it.write(image) }
                        ?: throw IllegalStateException("Could not write to the chosen file")
                    image.size
                }
            }
            dumpState.value = DumpUiState(
                accessoryId = accessory.id,
                running = false,
                message = result.exceptionOrNull()?.message
                    ?: result.exceptionOrNull()?.javaClass?.simpleName
                    ?: "Saved ${result.getOrDefault(0) / 1024} KB",
                failed = result.isFailure,
            )
        }
    }

    fun dumpName(accessory: Accessory, capability: FirmwareCapability): String {
        val version = capability.currentVersion
            .takeIf { it != FirmwareVersion.NONE }
            ?.value
            ?.replace(NOT_IN_NAME, "-")
        return listOfNotNull(accessory.productId, version).joinToString("-") + ".hex"
    }

    fun unlockService() {
        serviceUnlock.value = true
    }

    /** Installs a firmware file the user picked, bypassing the online manifest entirely. */
    fun installFile(accessory: Accessory, capability: FirmwareCapability, uri: Uri) {
        viewModelScope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { read(uri) } }
            val firmware = loaded.getOrNull()
            if (firmware == null) {
                installState.value = InstallUiState(
                    accessoryId = accessory.id,
                    progress = UpdateProgress.Failed(
                        loaded.exceptionOrNull()?.message ?: "Could not read firmware file",
                    ),
                    finished = true,
                )
                return@launch
            }
            collectInstall(accessory, container.updates.install(capability, firmware))
        }
    }

    private fun read(uri: Uri): FirmwarePackage {
        val resolver = getApplication<Application>().contentResolver
        val data = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open firmware file")
        if (data.isEmpty()) throw IllegalStateException("Firmware file is empty")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "firmware"
        return FirmwarePackage(
            release = FirmwareRelease(
                version = versionOf(name),
                url = uri.toString(),
                sizeBytes = data.size.toLong(),
                sha256 = "",
                minCurrentVersion = FirmwareVersion.NONE,
                notes = name,
                releasedAt = "",
            ),
            data = data,
        )
    }

    private fun versionOf(name: String): FirmwareVersion =
        VERSION_IN_NAME.find(name)?.value?.let(::FirmwareVersion) ?: FirmwareVersion(name)

    fun install(accessory: Accessory, capability: FirmwareCapability, release: FirmwareRelease) {
        viewModelScope.launch {
            collectInstall(accessory, container.updates.install(capability, release))
        }
    }

    private suspend fun collectInstall(accessory: Accessory, progressFlow: Flow<UpdateProgress>) {
        progressFlow.collect { progress ->
            installState.value = InstallUiState(
                accessoryId = accessory.id,
                progress = progress,
                finished = progress is UpdateProgress.Done || progress is UpdateProgress.Failed,
            )
        }
        if (installState.value?.progress is UpdateProgress.Done) {
            checks.update { it - accessory.id }
        }
    }

    private data class Transient(
        val install: InstallUiState?,
        val action: ActionUiState?,
        val dump: DumpUiState?,
        val unlocked: Boolean,
    )

    private data class SettingsState(
        val values: Map<String, Int> = emptyMap(),
        val loading: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private data class CheckState(
        val checking: Boolean,
        val release: FirmwareRelease?,
        val error: String?,
    )

    private companion object {
        const val SETTINGS_POLL_MS = 2000L
        val NOT_IN_NAME = Regex("[^A-Za-z0-9._-]+")
        val VERSION_IN_NAME = Regex("""\d+(\.\d+)+""")
    }
}
