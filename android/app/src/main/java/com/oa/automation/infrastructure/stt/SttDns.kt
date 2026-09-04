package com.oa.automation.infrastructure.stt

import com.oa.automation.BuildConfig
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * Keeps STT on a configured IPv4 relay while preserving the request hostname.
 * The hostname remains in the URL, so OkHttp still sends the correct Host/SNI
 * and certificate validation is unchanged. The fixed address is build-time
 * configuration and is only applied to the configured STT endpoint host.
 */
internal class Ipv4RelayDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val fixedRelayHost: String? = null,
    private val fixedIpv4Address: String? = null
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val normalizedHostname = hostname.trim().trimEnd('.').lowercase()
        val relayHost = fixedRelayHost?.trim()?.trimEnd('.')?.lowercase()
        val relayAddress = fixedIpv4Address?.trim().orEmpty()
        if (relayAddress.isNotBlank() && relayHost == normalizedHostname) {
            val fixedAddress = InetAddress.getByName(relayAddress)
            require(fixedAddress is Inet4Address) {
                "STT IPv4 relay address must resolve to an IPv4 address"
            }
            return listOf(fixedAddress)
        }

        val addresses = delegate.lookup(hostname)
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        if (ipv4.isEmpty()) return addresses
        return ipv4
    }
}

internal val STT_IPV4_RELAY_DNS: Dns = Ipv4RelayDns(
    fixedRelayHost = BuildConfig.DEFAULT_STT_RELAY_HOST,
    fixedIpv4Address = BuildConfig.DEFAULT_STT_IPV4_RELAY_ADDRESS
)

/**
 * Address policy for the local (Windows) model on the dual-stack public host.
 *
 * The AAAA record reaches the Windows host directly; the A record reaches the
 * VPS. Historically an IPv4 request to the root path would have landed on the
 * VPS's *cloud* STT and been mislabelled as local, so IPv4 was refused.
 *
 * With the VPS now proxying `/stt-local/` over WireGuard to the Windows host,
 * IPv4 is a legitimate route *for that path*. So: prefer IPv6 (direct, lowest
 * latency); fall back to IPv4 only when [ipv4RelayAllowed] — i.e. the endpoint
 * carries the `/stt-local` prefix. Any other local endpoint keeps the old
 * IPv6-only guarantee, so cloud traffic can never masquerade as local.
 */
internal class LocalSttDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val localPublicHost: String? = null,
    private val ipv4RelayAllowed: Boolean = false
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val normalizedHostname = hostname.trim().trimEnd('.').lowercase()
        val configuredHost = localPublicHost?.trim()?.trimEnd('.')?.lowercase()
        // Debug builds use 10.0.2.2 to reach the Windows host from an AVD.
        // Address pinning only applies to the dual-stack public hostname.
        if (configuredHost != normalizedHostname || normalizedHostname.isIpv4Literal()) {
            return addresses
        }
        val ipv6 = addresses.filterIsInstance<Inet6Address>()
        if (ipv6.isNotEmpty()) return ipv6
        if (ipv4RelayAllowed) {
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            if (ipv4.isNotEmpty()) return ipv4
        }
        throw UnknownHostException(LOCAL_STT_IPV6_UNAVAILABLE_REASON)
    }
}

/** Path prefix the VPS proxies over WireGuard to the Windows local model. */
internal const val LOCAL_STT_RELAY_PATH = "/stt-local"

/** True when [endpoint] reaches the local model through the IPv4-capable relay path. */
internal fun String.isLocalSttRelayEndpoint(): Boolean {
    val path = runCatching { java.net.URI(trim()).path.orEmpty() }.getOrDefault("")
    return path.trimEnd('/').equals(LOCAL_STT_RELAY_PATH, ignoreCase = true)
}

private fun String.isIpv4Literal(): Boolean {
    val parts = split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) &&
            part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

/**
 * Reason surfaced to the user when the local model cannot be reached. The
 * local service is published only over IPv6, so an IPv4-only network — many
 * mobile carriers — cannot reach it at all; the wording says so instead of
 * reading like a transient glitch.
 */
internal const val LOCAL_STT_IPV6_UNAVAILABLE_REASON =
    "无法连接智悟本地模型：当前网络没有 IPv6，且本地中继不可用"

internal val STT_LOCAL_DNS: Dns = LocalSttDns(
    localPublicHost = BuildConfig.DEFAULT_STT_RELAY_HOST,
    ipv4RelayAllowed = BuildConfig.DEFAULT_STT_ENDPOINT.isLocalSttRelayEndpoint()
)
