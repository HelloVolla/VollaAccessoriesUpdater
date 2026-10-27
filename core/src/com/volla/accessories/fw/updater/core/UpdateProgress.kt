package com.volla.accessories.fw.updater.core

sealed interface UpdateProgress {
    data object Preparing : UpdateProgress
    data class Transferring(val fraction: Float) : UpdateProgress
    data object Verifying : UpdateProgress
    data object Rebooting : UpdateProgress
    data object Done : UpdateProgress
    data class Failed(val reason: String) : UpdateProgress
}
