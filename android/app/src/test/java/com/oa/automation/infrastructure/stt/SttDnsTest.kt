package com.oa.automation.infrastructure.stt

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttDnsTest {
    @Test
    fun `dual stack lookup keeps STT on IPv4 relay`() {
        val ipv6 = InetAddress.getByName("2001:db8::10")
        val ipv4 = InetAddress.getByName("192.0.2.10")
        val dns = Ipv4RelayDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(ipv6, ipv4)
        })

        val result = dns.lookup("lstwin.space")

        assertTrue(result.first() is Inet4Address)
        assertEquals(1, result.size)
    }

    @Test
    fun `IPv6 only lookup remains usable`() {
        val first = InetAddress.getByName("2001:db8::10")
        val second = InetAddress.getByName("2001:db8::11")
        val dns = Ipv4RelayDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(first, second)
        })

        assertEquals(listOf(first, second), dns.lookup("lstwin.space"))
    }

    @Test
    fun `configured relay address overrides DNS only for the STT host`() {
        val dns = Ipv4RelayDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(
                    InetAddress.getByName("2001:db8::10")
                )
            },
            fixedRelayHost = "lstwin.space",
            fixedIpv4Address = "192.0.2.44"
        )

        val relay = dns.lookup("LSTWIN.SPACE.")

        assertEquals(listOf(InetAddress.getByName("192.0.2.44")), relay)
    }

    @Test
    fun `configured relay address does not rewrite unrelated hosts`() {
        val original = InetAddress.getByName("2001:db8::11")
        val dns = Ipv4RelayDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(original)
            },
            fixedRelayHost = "lstwin.space",
            fixedIpv4Address = "192.0.2.44"
        )

        assertEquals(listOf(original), dns.lookup("example.com"))
    }

    @Test
    fun `local public host keeps only IPv6 address`() {
        val ipv6 = InetAddress.getByName("2001:db8::10")
        val ipv4 = InetAddress.getByName("192.0.2.10")
        val dns = LocalSttDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(ipv4, ipv6)
            },
            localPublicHost = "lstwin.space"
        )

        val result = dns.lookup("LSTWIN.SPACE.")

        assertEquals(1, result.size)
        assertTrue(result.single() is Inet6Address)
    }

    @Test(expected = UnknownHostException::class)
    fun `local public host fails instead of leaking onto cloud IPv4`() {
        val dns = LocalSttDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(
                    InetAddress.getByName("192.0.2.10")
                )
            },
            localPublicHost = "lstwin.space"
        )

        dns.lookup("lstwin.space")
    }

    @Test
    fun `AVD host IPv4 remains available for local debug service`() {
        val avdHost = InetAddress.getByName("10.0.2.2")
        val dns = LocalSttDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(avdHost)
            },
            localPublicHost = "10.0.2.2"
        )

        assertEquals(listOf(avdHost), dns.lookup("10.0.2.2"))
    }
}
