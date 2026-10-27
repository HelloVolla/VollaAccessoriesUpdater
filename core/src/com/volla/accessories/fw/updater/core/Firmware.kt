package com.volla.accessories.fw.updater.core

data class FirmwareRelease(
    val version: FirmwareVersion,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val minCurrentVersion: FirmwareVersion,
    val notes: String,
    val releasedAt: String,
)

data class FirmwareManifest(
    val moduleId: String,
    val productId: String,
    val channel: String,
    val latest: FirmwareVersion,
    val releases: List<FirmwareRelease>,
)

class FirmwarePackage(
    val release: FirmwareRelease,
    val data: ByteArray,
)
