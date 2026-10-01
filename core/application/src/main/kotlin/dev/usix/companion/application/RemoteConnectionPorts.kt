package dev.usix.companion.application

import dev.usix.companion.domain.RemoteEndpoint

interface RemoteEndpointStore {
    suspend fun load(): RemoteEndpoint?
    suspend fun save(endpoint: RemoteEndpoint?)
}
interface RemoteConnectionControl {
    suspend fun configure(endpoint: RemoteEndpoint?)
    fun status(): String
}
