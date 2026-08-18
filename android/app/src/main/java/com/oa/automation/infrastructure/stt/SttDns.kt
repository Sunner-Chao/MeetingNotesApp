package com.oa.automation.infrastructure.stt

import com.oa.automation.BuildConfig
import java.net.Inet4Address
import java.net.InetAddress
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
