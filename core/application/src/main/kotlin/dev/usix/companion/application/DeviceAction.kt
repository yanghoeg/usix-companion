package dev.usix.companion.application

/** Preserve presence/type errors without allowing an invalid scope to become unscoped. */
sealed interface InputField<out T> {
    data object Missing : InputField<Nothing>
    data object Invalid : InputField<Nothing>
    data class Value<T>(val value: T) : InputField<T>
}

sealed interface DeviceAction {
    data class Tap(val x: Int?, val y: Int?) : DeviceAction
    data class Type(val text: String?, val packageName: InputField<String> = InputField.Missing) : DeviceAction
    data object Back : DeviceAction
    data class Open(val packageName: String?) : DeviceAction
    data class Scroll(val direction: String?, val packageName: InputField<String> = InputField.Missing) : DeviceAction
    data class OpenEmail(val packageName: InputField<String> = InputField.Missing) : DeviceAction
    data class ComposeEmail(
        val to: String?,
        val subject: InputField<String> = InputField.Missing,
        val body: InputField<String> = InputField.Missing,
        val packageName: InputField<String> = InputField.Missing,
    ) : DeviceAction
    data class Reply(val key: String?, val text: String?) : DeviceAction
}
