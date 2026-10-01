package dev.usix.companion.feature.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.usix.companion.application.DeviceQuery
import dev.usix.companion.application.PairingCredentials
import dev.usix.companion.application.LocalExecutionControl
import dev.usix.companion.application.RemoteConnectionControl
import dev.usix.companion.domain.PairedRuntimeSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ControlState(
    val notificationAccess: Boolean = false,
    val listenerConnected: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val token: String = "",
    val runtimes: List<PairedRuntimeSummary> = emptyList(),
    val remoteStatus: String = "disabled",
)

class ControlViewModel(private val query: DeviceQuery, private val credentials: PairingCredentials,
    private val execution: LocalExecutionControl? = null, private val remote: RemoteConnectionControl? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(ControlState(token = credentials.current()))
    val state: StateFlow<ControlState> = mutableState.asStateFlow()

    fun refresh(notificationAccess: Boolean) {
        val health = query.health()
        mutableState.update { it.copy(notificationAccess = notificationAccess, listenerConnected = health.listenerConnected,
            accessibilityConnected = health.accessibilityConnected, token = credentials.current()) }
        refreshRuntimes()
    }

    fun regenerateToken() { mutableState.update { it.copy(token = credentials.regenerate()) } }
    fun refreshRuntimes() {
        if (execution == null) return
        viewModelScope.launch(Dispatchers.IO) { mutableState.update { it.copy(runtimes = execution.runtimeSummaries(), remoteStatus = remote?.status() ?: "disabled") } }
    }
    fun pauseAutomation() { viewModelScope.launch(Dispatchers.IO) { execution?.pause(); refreshRuntimes() } }
    fun revokeRuntime(sessionId: String) { viewModelScope.launch(Dispatchers.IO) { execution?.revoke(sessionId); refreshRuntimes() } }
    fun selectRuntime(sessionId: String) { viewModelScope.launch(Dispatchers.IO) { execution?.selectController(sessionId); refreshRuntimes() } }

    class Factory(private val query: DeviceQuery, private val credentials: PairingCredentials,
        private val execution: LocalExecutionControl? = null, private val remote: RemoteConnectionControl? = null) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ControlViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ControlViewModel(query, credentials, execution, remote) as T
        }
    }
}
