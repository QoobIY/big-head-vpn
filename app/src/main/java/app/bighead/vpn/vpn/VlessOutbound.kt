package app.bighead.vpn.vpn

import android.net.Uri
import app.bighead.vpn.core.VpnProfile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class VlessOutbound(
    profile: VpnProfile,
    private val protect: (Socket) -> Boolean,
) {
    private val uri = Uri.parse(profile.uri)
    private val query = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
    private val server = requireNotNull(uri.host) { "VLESS host is empty" }
    private val serverPort = uri.port.takeIf { it > 0 } ?: 443
    private val uuid = uuidBytes(UUID.fromString(uri.userInfo.orEmpty()))
    private val security = query["security"].orEmpty().lowercase(Locale.US)
    private val transport = query["type"].orEmpty().ifBlank { "tcp" }.lowercase(Locale.US)
    private val tlsServerName = query["sni"] ?: query["serverName"] ?: server

    init {
        validateSupportedMode()
    }

    fun open(targetHost: String, targetPort: Int): CloseableStreams {
        val socket = Socket()
        protect(socket)
        socket.connect(InetSocketAddress(server, serverPort), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true

        val secured = if (security == "tls") wrapTls(socket) else socket
        return when (transport) {
            "tcp", "raw" -> openTcp(secured, targetHost, targetPort)
            "ws" -> openWebSocket(secured, targetHost, targetPort)
            else -> error("VLESS transport '$transport' пока не поддержан")
        }
    }

    private fun validateSupportedMode() {
        when (security) {
            "", "none", "tls" -> Unit
            "reality" -> error("VLESS Reality пока не поддержан в лёгком engine")
            else -> error("VLESS security '$security' пока не поддержан")
        }
        when (transport) {
            "tcp", "raw", "ws" -> Unit
            "grpc" -> error("VLESS gRPC пока не поддержан в лёгком engine")
            "xhttp", "httpupgrade", "splithttp" -> error("VLESS transport '$transport' пока не поддержан")
            else -> error("VLESS transport '$transport' пока не поддержан")
        }
    }

    private fun wrapTls(socket: Socket): Socket {
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = sslFactory
            .createSocket(socket, tlsServerName, serverPort, true) as SSLSocket
        ssl.sslParameters = SSLParameters().apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(tlsServerName))
        }
        ssl.startHandshake()
        return ssl
    }

    private fun openTcp(socket: Socket, targetHost: String, targetPort: Int): CloseableStreams {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(vlessRequest(targetHost, targetPort))
        output.flush()
        stripVlessResponseHeader(input)
        return CloseableStreams(input, output) { socket.close() }
    }

    private fun openWebSocket(socket: Socket, targetHost: String, targetPort: Int): CloseableStreams {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        webSocketHandshake(input, output)
        val wsInput = WebSocketInputStream(input)
        val wsOutput = WebSocketOutputStream(output)
        wsOutput.write(vlessRequest(targetHost, targetPort))
        wsOutput.flush()
        stripVlessResponseHeader(wsInput)
        return CloseableStreams(wsInput, wsOutput) { socket.close() }
    }

    private fun webSocketHandshake(input: InputStream, output: OutputStream) {
        val path = (query["path"] ?: "/").let { if (it.startsWith("/")) it else "/$it" }
        val hostHeader = query["host"] ?: server
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        output.write(request.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val response = readHttpHeader(input)
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            error("WebSocket upgrade failed")
        }
    }

    private fun vlessRequest(targetHost: String, targetPort: Int): ByteArray {
        val addressBytes = encodeAddress(targetHost)
        return ByteArrayOutputStream().apply {
            write(0)
            write(uuid)
            write(0)
            write(1)
            write((targetPort shr 8) and 0xff)
            write(targetPort and 0xff)
            write(addressBytes)
        }.toByteArray()
    }

    private fun encodeAddress(host: String): ByteArray {
        val parsed = when {
            IPV4_REGEX.matches(host) || host.contains(":") -> runCatching { InetAddress.getByName(host) }.getOrNull()
            else -> null
        }
        return when (parsed) {
            is Inet4Address -> byteArrayOf(1) + parsed.address
            is Inet6Address -> byteArrayOf(3) + parsed.address
            else -> {
                val name = host.toByteArray(Charsets.UTF_8)
                byteArrayOf(2, name.size.toByte()) + name
            }
        }
    }

    private fun stripVlessResponseHeader(input: InputStream) {
        val version = input.read()
        if (version < 0) error("VLESS response is empty")
        val addonLength = input.read()
        if (addonLength < 0) error("VLESS response is truncated")
        if (addonLength > 0) readExact(input, addonLength)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12000
        private val IPV4_REGEX = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    }
}

class CloseableStreams(
    val input: InputStream,
    val output: OutputStream,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() {
        closeAction()
    }
}

private fun uuidBytes(uuid: UUID): ByteArray {
    val buffer = ByteArray(16)
    var most = uuid.mostSignificantBits
    var least = uuid.leastSignificantBits
    for (index in 7 downTo 0) {
        buffer[index] = most.toByte()
        most = most shr 8
    }
    for (index in 15 downTo 8) {
        buffer[index] = least.toByte()
        least = least shr 8
    }
    return buffer
}

private fun readHttpHeader(input: InputStream): String {
    val buffer = ByteArrayOutputStream()
    var matched = 0
    val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    while (buffer.size() < 16 * 1024) {
        val byte = input.read()
        if (byte < 0) break
        buffer.write(byte)
        matched = if (byte.toByte() == end[matched]) matched + 1 else 0
        if (matched == end.size) break
    }
    return buffer.toString(Charsets.ISO_8859_1.name())
}

private class WebSocketOutputStream(private val output: OutputStream) : OutputStream() {
    private val random = SecureRandom()

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(0x82)
        when {
            len < 126 -> header.write(0x80 or len)
            len <= 0xffff -> {
                header.write(0x80 or 126)
                header.write((len shr 8) and 0xff)
                header.write(len and 0xff)
            }
            else -> error("WebSocket frame is too large")
        }
        header.write(mask)
        val payload = ByteArray(len)
        for (index in 0 until len) {
            payload[index] = (b[off + index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        output.write(header.toByteArray())
        output.write(payload)
    }

    override fun flush() {
        output.flush()
    }
}

private class WebSocketInputStream(private val input: InputStream) : InputStream() {
    private var frame = ByteArray(0)
    private var offset = 0

    override fun read(): Int {
        val byte = ByteArray(1)
        val read = read(byte, 0, 1)
        return if (read < 0) -1 else byte[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (offset >= frame.size) {
            frame = nextFrame() ?: return -1
            offset = 0
        }
        val count = minOf(len, frame.size - offset)
        frame.copyInto(b, off, offset, offset + count)
        offset += count
        return count
    }

    private fun nextFrame(): ByteArray? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) return null
            val opcode = first and 0x0f
            val masked = (second and 0x80) != 0
            var length = second and 0x7f
            if (length == 126) {
                val ext = readExact(input, 2)
                length = ((ext[0].toInt() and 0xff) shl 8) or (ext[1].toInt() and 0xff)
            } else if (length == 127) {
                val ext = readExact(input, 8)
                val longLength = ext.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
                if (longLength > Int.MAX_VALUE) error("WebSocket frame is too large")
                length = longLength.toInt()
            }
            val mask = if (masked) readExact(input, 4) else null
            val payload = readExact(input, length)
            if (mask != null) {
                for (index in payload.indices) {
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }
            when (opcode) {
                0x0, 0x2 -> return payload
                0x8 -> return null
                0x9, 0xA -> continue
                else -> continue
            }
        }
    }
}
