package com.example.proxytester.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class Socks5Exception(message: String) : Exception(message)

object Socks5ReplyCodes {
    fun describe(code: Int): String = when (code) {
        0x01 -> "General SOCKS server failure"
        0x02 -> "Connection not allowed by ruleset (likely blocked by proxy/ISP)"
        0x03 -> "Network unreachable"
        0x04 -> "Host unreachable"
        0x05 -> "Connection refused by target"
        0x06 -> "TTL expired"
        0x07 -> "Command not supported"
        0x08 -> "Address type not supported"
        else -> "Unknown SOCKS5 error ($code)"
    }
}

/**
 * Minimal but real RFC 1928 SOCKS5 client: greeting, no-auth negotiation,
 * and a CONNECT command. This is NOT a fake "open a socket and pretend" –
 * it speaks the actual SOCKS5 wire protocol against the proxy, which is
 * what makes it possible to tell "port is open" apart from "SOCKS5 CONNECT
 * to Telegram actually succeeds", which is the whole point of this project.
 *
 * On success it returns the raw [Socket]; its streams are now tunneled to
 * the target host/port through the proxy, so callers can write/read the
 * real application-layer data (see Socks5Checker).
 */
class Socks5Client(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val connectTimeoutMs: Int = 8000
) {
    fun connect(targetHost: String, targetPort: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
        socket.soTimeout = connectTimeoutMs

        val out = DataOutputStream(socket.getOutputStream())
        val inp = DataInputStream(socket.getInputStream())

        // Greeting: SOCKS5, 1 method offered, no-auth (0x00)
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()

        val greetingReply = ByteArray(2)
        inp.readFully(greetingReply)
        if (greetingReply[0] != 0x05.toByte()) {
            socket.close()
            throw Socks5Exception("Not a SOCKS5 proxy (bad version byte in greeting reply)")
        }
        if (greetingReply[1] != 0x00.toByte()) {
            socket.close()
            throw Socks5Exception("Proxy requires authentication we don't support")
        }

        // CONNECT request, ATYP = domain name so we don't need to resolve DNS ourselves
        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val request = ArrayList<Byte>(7 + hostBytes.size)
        request.add(0x05)
        request.add(0x01) // CONNECT
        request.add(0x00) // reserved
        request.add(0x03) // ATYP = domain
        request.add(hostBytes.size.toByte())
        request.addAll(hostBytes.toList())
        request.add((targetPort shr 8 and 0xFF).toByte())
        request.add((targetPort and 0xFF).toByte())
        out.write(request.toByteArray())
        out.flush()

        val replyHeader = ByteArray(4)
        inp.readFully(replyHeader)
        if (replyHeader[0] != 0x05.toByte()) {
            socket.close()
            throw Socks5Exception("Malformed CONNECT reply")
        }
        val replyCode = replyHeader[1].toInt() and 0xFF
        if (replyCode != 0x00) {
            socket.close()
            throw Socks5Exception(Socks5ReplyCodes.describe(replyCode))
        }

        // Consume bound address/port so the stream is positioned right at
        // the start of the tunneled application data.
        when (replyHeader[3].toInt() and 0xFF) {
            0x01 -> inp.readFully(ByteArray(4 + 2))
            0x03 -> {
                val len = inp.readUnsignedByte()
                inp.readFully(ByteArray(len + 2))
            }
            0x04 -> inp.readFully(ByteArray(16 + 2))
            else -> {
                socket.close()
                throw Socks5Exception("Unknown address type in CONNECT reply")
            }
        }

        return socket
    }
}
