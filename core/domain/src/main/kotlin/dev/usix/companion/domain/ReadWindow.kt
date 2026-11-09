package dev.usix.companion.domain

/** Short-lived navigation reference; it never authorizes input, clicks or sending. */
data class ReadWindow(
    val ref: String?, val capturedAtMillis: Long, val expiresAtMillis: Long,
    val screen: ObservedScreen, val compact: Boolean,
)
