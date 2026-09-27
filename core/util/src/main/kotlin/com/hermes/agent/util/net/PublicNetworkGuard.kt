package com.hermes.agent.util.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Keeps agent-directed requests on the public internet.
 *
 * A URL the model chose (web_fetch, an image or audio link, a module's
 * `http.get`) can come from a web page written to steer the agent. Without this,
 * such a page could have the agent read the router admin page, a NAS, or a
 * service on the phone itself, then carry what it found out on the next fetch.
 *
 * The check sits on the socket: every TCP connect is refused before it starts
 * when its address is not public. That covers hostnames, IP-literal URLs (which
 * OkHttp never passes to [Dns]) and every redirect hop, and nothing reaches the
 * device, not even a TLS handshake. [Dns] also drops private answers, so a name
 * resolving to both a public and a private address only reaches the public one.
 * No proxy is used: through one, the socket would be the proxy's and the proxy
 * would resolve the name, so the check would see nothing.
 *
 * Only for URLs the model supplies. Destinations the user configured (MCP servers,
 * Home Assistant, a local model endpoint) use their own clients and stay reachable.
 */
@Singleton
class PublicNetworkGuard(private val allowed: (InetAddress) -> Boolean) {

    @Inject
    constructor() : this(Companion::isPublicAddress)

    /** Refused because the destination is on a private or local network. */
    class BlockedDestinationException(host: String, address: InetAddress) : IOException(
        (if (host == address.hostAddress) host else "$host resolves to ${address.hostAddress}, which") +
            " is a private or local network address. Only public internet addresses can be fetched.",
    )

    /** [client] with every connection limited to addresses this guard allows. */
    fun restrict(client: OkHttpClient): OkHttpClient {
        val upstream = client.dns
        return client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val all = upstream.lookup(hostname)
                    return all.filter(allowed).ifEmpty {
                        throw BlockedDestinationException(hostname, all.first())
                    }
                }
            })
            .socketFactory(GuardedSocketFactory())
            .build()
    }

    private fun check(endpoint: SocketAddress?) {
        val address = (endpoint as? InetSocketAddress)?.address ?: return
        if (!allowed(address)) {
            throw BlockedDestinationException(address.hostAddress ?: address.toString(), address)
        }
    }

    /** A plain socket that refuses to connect anywhere [allowed] rejects. */
    private inner class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            check(endpoint)
            super.connect(endpoint, timeout)
        }
    }

    private inner class GuardedSocketFactory : SocketFactory() {
        override fun createSocket(): Socket = GuardedSocket()

        override fun createSocket(host: String, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            GuardedSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            GuardedSocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }

    companion object {
        /**
         * True for globally routable unicast addresses. Refuses loopback, private,
         * link-local, carrier-grade NAT (which is also Tailscale's range),
         * multicast, reserved and documentation ranges, and IPv6 forms that
         * embed an IPv4 address (mapped, NAT64, 6to4) when that address is refused.
         */
        fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return false
            }
            val b = address.address.map { it.toInt() and 0xFF }
            return when (address) {
                is Inet4Address -> isPublicV4(b)
                is Inet6Address -> isPublicV6(b)
                else -> false
            }
        }

        private fun isPublicV4(b: List<Int>): Boolean = when {
            b[0] == 0 -> false // "this network"
            b[0] == 10 -> false
            b[0] == 100 && b[1] in 64..127 -> false // CGNAT, Tailscale
            b[0] == 127 -> false
            b[0] == 169 && b[1] == 254 -> false
            b[0] == 172 && b[1] in 16..31 -> false
            b[0] == 192 && b[1] == 0 && b[2] == 0 -> false // IETF protocol assignments
            b[0] == 192 && b[1] == 0 && b[2] == 2 -> false // TEST-NET-1
            b[0] == 192 && b[1] == 168 -> false
            b[0] == 198 && b[1] in 18..19 -> false // benchmarking
            b[0] == 198 && b[1] == 51 && b[2] == 100 -> false // TEST-NET-2
            b[0] == 203 && b[1] == 0 && b[2] == 113 -> false // TEST-NET-3
            b[0] >= 224 -> false // multicast, reserved, broadcast
            else -> true
        }

        private fun isPublicV6(b: List<Int>): Boolean {
            fun embeddedV4(from: Int) = isPublicV4(b.subList(from, from + 4))
            val firstTen = b.subList(0, 10).all { it == 0 }
            return when {
                // ::ffff:a.b.c.d (mapped) and ::a.b.c.d (deprecated compatible form)
                firstTen && ((b[10] == 0xFF && b[11] == 0xFF) || (b[10] == 0 && b[11] == 0)) -> embeddedV4(12)
                (b[0] and 0xFE) == 0xFC -> false // fc00::/7 unique local
                b[0] == 0xFE && (b[1] and 0xC0) == 0xC0 -> false // fec0::/10 site-local
                // 64:ff9b::/96 NAT64 translates to the embedded IPv4 address.
                b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B -> embeddedV4(12)
                b[0] == 0x20 && b[1] == 0x02 -> embeddedV4(2) // 2002::/16 6to4
                b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00 -> false // Teredo
                b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && b[3] == 0xB8 -> false // documentation
                else -> true
            }
        }
    }
}
