package dev.usix.companion

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.usix.companion.adapters.android.AndroidNotificationAdapter
import dev.usix.companion.adapters.android.ConnectedAccessibilityAdapter
import dev.usix.companion.adapters.transport.LoopbackBridgeServer
import dev.usix.companion.application.DeviceQuery
import dev.usix.companion.application.PairingCredentials

// Debug-only inspection of the generated graph; never packaged in release builds.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CompositionEntryPoint {
    fun query(): DeviceQuery
    fun credentials(): PairingCredentials
    fun notifications(): AndroidNotificationAdapter
    fun accessibility(): ConnectedAccessibilityAdapter
    fun bridge(): LoopbackBridgeServer
}
