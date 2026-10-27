package com.volla.accessories.fw.updater.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class UpdateCoordinator(private val firmware: FirmwareService) {

    suspend fun latestUpdate(
        accessory: Accessory,
        capability: FirmwareCapability,
        channel: String = "stable",
    ): FirmwareRelease? {
        val current = capability.currentVersion
        val newer = firmware.manifest(accessory, channel).releases.filter { it.version > current }
        val compatible = newer
            .filter { current >= it.minCurrentVersion }
            .maxByOrNull { it.version }
        if (compatible != null || newer.isEmpty()) return compatible

        val required = newer.minByOrNull { it.minCurrentVersion }!!.minCurrentVersion
        throw IllegalStateException("Available firmware requires current version $required or newer")
    }

    fun install(capability: FirmwareCapability, release: FirmwareRelease): Flow<UpdateProgress> = flow {
        emit(UpdateProgress.Preparing)
        emitAll(capability.install(firmware.download(release)))
    }.catch { emit(UpdateProgress.Failed(it.message ?: "Update failed")) }

    fun install(capability: FirmwareCapability, pkg: FirmwarePackage): Flow<UpdateProgress> = flow {
        emit(UpdateProgress.Preparing)
        emitAll(capability.install(pkg))
    }.catch { emit(UpdateProgress.Failed(it.message ?: "Update failed")) }
}
