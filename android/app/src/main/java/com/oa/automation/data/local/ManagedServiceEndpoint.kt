package com.oa.automation.data.local

import java.net.URI

/** Migrate only our historical managed routes; never rewrite a custom server. */
internal fun resolveManagedServiceEndpoint(saved: String?, configuredDefault: String): String {
    val candidate = saved?.trim()?.trimEnd('/').orEmpty()
    if (candidate.isBlank()) return configuredDefault
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return candidate
    // The VPS IP is the IPv4-only fallback for mobile carriers. Treat it as
    // managed too, so old domain-based profiles migrate consistently.
    val managedHost = uri.host?.lowercase() in setOf(
        "lstwin.space",
        "auth.synthapi.asia",
        "118.25.43.185"
    )
    val managedPath = uri.path?.trimEnd('/') in setOf("/api", "/api/agent", "/stt-cloud")
    return if (managedHost && managedPath && configuredDefault.isNotBlank()) {
        configuredDefault
    } else candidate
}
