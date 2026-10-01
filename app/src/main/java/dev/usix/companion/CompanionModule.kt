package dev.usix.companion

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.usix.companion.adapters.android.AndroidAppLauncher
import dev.usix.companion.adapters.android.AndroidExecutionReadiness
import dev.usix.companion.adapters.android.AndroidNotificationAdapter
import dev.usix.companion.adapters.android.ConnectedAccessibilityAdapter
import dev.usix.companion.adapters.persistence.BridgeCredentialStore
import dev.usix.companion.adapters.persistence.ExecutionDatabase
import dev.usix.companion.adapters.persistence.RoomExecutionRepository
import dev.usix.companion.adapters.persistence.RemoteEndpointPreferences
import dev.usix.companion.adapters.transport.LegacyDeviceRouter
import dev.usix.companion.adapters.transport.LoopbackBridgeServer
import dev.usix.companion.adapters.transport.DeviceV2Router
import dev.usix.companion.adapters.transport.ExecutionCrypto
import dev.usix.companion.adapters.transport.OutboundDeviceConnection
import dev.usix.companion.application.DeviceApplication
import dev.usix.companion.application.DeviceQuery
import dev.usix.companion.application.DeviceExecution
import dev.usix.companion.application.ExecuteDeviceAction
import dev.usix.companion.application.DeviceAction
import dev.usix.companion.application.ExecutionClock
import dev.usix.companion.application.ExecutionIds
import dev.usix.companion.application.PairingCredentials
import dev.usix.companion.feature.control.ControlViewModel
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/** The only production assembly point; core classes contain no DI annotations. */
@Module
@InstallIn(SingletonComponent::class)
object CompanionModule {
    @Provides @Singleton
    fun accessibility() = ConnectedAccessibilityAdapter(Dispatchers.Main.immediate)
    @Provides @Singleton
    fun notifications(@ApplicationContext context: Context) = AndroidNotificationAdapter(context, Dispatchers.Main.immediate)
    @Provides @Singleton
    fun launcher(@ApplicationContext context: Context) = AndroidAppLauncher(context, Dispatchers.Main.immediate)
    @Provides @Singleton
    fun credentials(@ApplicationContext context: Context) = BridgeCredentialStore(context)
    @Provides @Singleton
    fun application(accessibility: ConnectedAccessibilityAdapter, notifications: AndroidNotificationAdapter, launcher: AndroidAppLauncher) =
        DeviceApplication(accessibility, accessibility, accessibility, launcher, notifications)
    @Provides fun queries(application: DeviceApplication): DeviceQuery = application
    @Provides fun pairing(credentials: BridgeCredentialStore): PairingCredentials = credentials
    @Provides @Singleton
    fun journal(@ApplicationContext context: Context) = RoomExecutionRepository(ExecutionDatabase.open(context))
    @Provides @Singleton
    fun readiness(@ApplicationContext context: Context) = AndroidExecutionReadiness(context)
    @Provides @Singleton
    fun execution(journal: RoomExecutionRepository, readiness: AndroidExecutionReadiness, application: DeviceApplication) =
        DeviceExecution(journal, ExecutionClock { System.currentTimeMillis() }, ExecutionIds { java.util.UUID.randomUUID().toString() }, ExecutionCrypto(), readiness, application)
    @Provides @Singleton
    fun v2Router(execution: DeviceExecution, application: DeviceApplication, credentials: BridgeCredentialStore, readiness: AndroidExecutionReadiness) =
        DeviceV2Router(execution, application, credentials, readiness) { System.currentTimeMillis() }
    @Provides @Singleton
    fun remoteSettings(@ApplicationContext context: Context) = RemoteEndpointPreferences(context)
    @Provides @Singleton
    fun remote(settings: RemoteEndpointPreferences, router: DeviceV2Router, execution: DeviceExecution) =
        OutboundDeviceConnection(settings, router, { execution.deviceId })
    @Provides @Singleton
    fun bridge(application: DeviceApplication, credentials: BridgeCredentialStore, execution: DeviceExecution, v2Router: DeviceV2Router) =
        LoopbackBridgeServer(LegacyDeviceRouter(application, object : ExecuteDeviceAction {
            override suspend fun execute(action: DeviceAction) = execution.executeLegacy(action)
        }), credentials, v2 = v2Router)
    @Provides fun viewModelFactory(query: DeviceQuery, credentials: PairingCredentials, execution: DeviceExecution, remote: OutboundDeviceConnection) =
        ControlViewModel.Factory(query, credentials, execution, remote)
}
