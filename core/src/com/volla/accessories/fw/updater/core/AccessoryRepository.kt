package com.volla.accessories.fw.updater.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class AccessoryRepository(private val registry: ModuleRegistry) {

    fun accessories(): Flow<List<ConnectedAccessory>> {
        val flows = registry.all().map { module ->
            module.accessories()
                .map { list -> list.map { ConnectedAccessory(it, module.capabilities(it)) } }
                .flowOn(Dispatchers.IO)
        }
        if (flows.isEmpty()) return flowOf(emptyList())
        return combine(flows) { lists -> lists.toList().flatten() }
    }
}
