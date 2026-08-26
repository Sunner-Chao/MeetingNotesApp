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
 * The public A record is the cloud relay, while the AAAA record reaches the
 * Windows local model. A local-model request must therefore stay on IPv6;
 * IPv4-only networks should fail quickly and let the explicit cloud route take
 * over instead of presenting cloud traffic as a local session.
 */
internal class LocalSttDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val localPublicHost: String? = null
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val normalizedHostname = hostname.trim().trimEnd('.').lowercase()
        val configuredHost = localPublicHost?.trim()?.trimEnd('.')?.lowercase()
        if (configuredHost != normalizedHostname) return addresses
        return addresses.filterIsInstance<Inet6Address>().ifEmpty {
            throw UnknownHostException("Local STT IPv6 endpoint is unavailable")
        }
    }
}

internal val STT_LOCAL_DNS: Dns = LocalSttDns(
    localPublicHost = BuildConfig.DEFAULT_STT_RELAY_HOST
)
