package app.bighead.vpn.vpn

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class Socks5Server(
    private val outbound: VlessOutbound,
) : Closeable {
    private val executor = Executors.newCachedThreadPool()
    private lateinit var server: ServerSocket
    @Volatile private var closed = false

    val port: Int
        get() = server.localPort

    fun start() {
        server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        executor.execute {
            while (!closed) {
                runCatching { server.accept() }
                    .onSuccess { client -> executor.execute { handle(client) } }
                    .onFailure { if (!closed) it.printStackTrace() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            val input = it.getInputStream()
            val output = it.getOutputStream()
            negotiate(input, output)
            val request = readRequest(input)
            if (request.command != COMMAND_CONNECT) {
                writeReply(output, REPLY_COMMAND_NOT_SUPPORTED)
                return
            }

            runCatching {
                outbound.open(request.host, request.port)
            }.onSuccess { remote ->
                writeReply(output, REPLY_SUCCESS)
                bridge(it, remote)
            }.onFailure {
                writeReply(output, REPLY_CONNECTION_REFUSED)
            }
        }
    }

    private fun negotiate(input: InputStream, output: OutputStream) {
        if (input.read() != SOCKS_VERSION) error("Unsupported SOCKS version")
        val methodCount = input.read()
        repeat(methodCount) { input.read() }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()
    }

    private fun readRequest(input: InputStream): SocksRequest {
        if (input.read() != SOCKS_VERSION) error("Unsupported SOCKS request")
        val command = input.read()
        input.read()
        val addressType = input.read()
        val host = when (addressType) {
            ATYP_IPV4 -> readExact(input, 4).joinToString(".") { (it.toInt() and 0xff).toString() }
            ATYP_DOMAIN -> String(readExact(input, input.read()))
            ATYP_IPV6 -> InetAddress.getByAddress(readExact(input, 16)).hostAddress.orEmpty()
            else -> error("Unsupported SOCKS address type")
        }
        val portBytes = readExact(input, 2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        return SocksRequest(command, host, port)
    }

    private fun writeReply(output: OutputStream, reply: Int) {
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), reply.toByte(), 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun bridge(client: Socket, remote: CloseableStreams) {
        remote.use {
            val upstream = Thread { copy(client.getInputStream(), remote.output) }
            val downstream = Thread { copy(remote.input, client.getOutputStream()) }
            upstream.start()
            downstream.start()
            upstream.join()
            downstream.join()
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        runCatching {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        executor.shutdownNow()
    }

    private data class SocksRequest(val command: Int, val host: String, val port: Int)

    companion object {
        private const val SOCKS_VERSION = 5
        private const val METHOD_NO_AUTH = 0
        private const val COMMAND_CONNECT = 1
        private const val ATYP_IPV4 = 1
        private const val ATYP_DOMAIN = 3
        private const val ATYP_IPV6 = 4
        private const val REPLY_SUCCESS = 0
        private const val REPLY_CONNECTION_REFUSED = 5
        private const val REPLY_COMMAND_NOT_SUPPORTED = 7
    }
}

fun readExact(input: InputStream, size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = input.read(bytes, offset, size - offset)
        if (read < 0) error("Unexpected end of stream")
        offset += read
    }
    return bytes
}
