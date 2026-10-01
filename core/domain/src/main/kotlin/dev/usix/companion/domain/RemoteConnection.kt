package dev.usix.companion.domain

data class RemoteEndpoint(val url: String, val deviceBearer: String, val trustedCertificatePem: String?)
