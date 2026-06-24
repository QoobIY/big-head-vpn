package app.bighead.vpn.vpn

import android.content.Context
import android.net.Uri
import android.os.Build
import app.bighead.vpn.core.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class XrayClient(
    private val context: Context,
    private val profile: VpnProfile,
) : Closeable {
    private val logs = AtomicReference("")
    private var process: Process? = null
    var port: Int = 0
        private set

    fun start() {
        val binary = xrayBinary()
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
        }
        if (!binary.canExecute()) {
            error("Xray core не исполняется на этом Android")
        }

        port = freePort()
        val config = writeConfig(port)
        val proc = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-config",
            config.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        process = proc
        drainLogs(proc)
        runCatching {
            waitUntilReady(proc, port)
            verifyOutbound(proc, port)
        }.onFailure {
            close()
            throw it
        }
    }

    override fun close() {
        val proc = process ?: return
        runCatching {
            proc.destroy()
            if (!proc.waitFor(1200L, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(800L, TimeUnit.MILLISECONDS)
            }
        }.onFailure {
            DebugLog.append(context, "xray stop failed", it)
        }
        process = null
    }

    private fun writeConfig(socksPort: Int): File {
        val uri = Uri.parse(profile.uri.substringBefore('#'))
        val host = requireNotNull(uri.host) { "VLESS host is empty" }
        val serverPort = uri.port.takeIf { it > 0 } ?: 443
        val uuid = uri.userInfo.orEmpty().substringBefore(':')
        val query = uri.queryParameterNames.associateWith { key -> uri.getQueryParameter(key).orEmpty() }
        val security = query["security"].orEmpty().lowercase(Locale.US)
        val network = query["type"].orEmpty().ifBlank { "tcp" }.lowercase(Locale.US)
        val flow = query["flow"].orEmpty()

        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", query["encryption"].orEmpty().ifBlank { "none" })
        if (flow.isNotBlank()) user.put("flow", flow)

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", serverPort)
                            .put("users", JSONArray().put(user)),
                    ),
                ),
            )
            .put("streamSettings", streamSettings(network, security, host, query))

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(socksInbound(socksPort)))
            .put("outbounds", JSONArray().put(outbound))
            .put(
                "routing",
                JSONObject()
                    .put("domainStrategy", "AsIs")
                    .put("rules", JSONArray()),
            )

        val file = File(context.cacheDir, "xray-client.json")
        file.writeText(config.toString())
        return file
    }

    private fun socksInbound(socksPort: Int): JSONObject {
        return JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject()
                    .put("auth", "noauth")
                    .put("udp", true),
            )
    }

    private fun streamSettings(
        network: String,
        security: String,
        host: String,
        query: Map<String, String>,
    ): JSONObject {
        val settings = JSONObject()
            .put("network", network)
            .put("security", security.ifBlank { "none" })

        when (security) {
            "tls" -> settings.put("tlsSettings", tlsSettings(host, query))
            "reality" -> settings.put("realitySettings", realitySettings(host, query))
            "", "none" -> Unit
            else -> settings.put("${security}Settings", JSONObject())
        }

        when (network) {
            "ws" -> settings.put("wsSettings", wsSettings(host, query))
            "grpc" -> settings.put("grpcSettings", grpcSettings(query))
            "httpupgrade" -> settings.put("httpupgradeSettings", httpUpgradeSettings(host, query))
            "xhttp", "splithttp" -> settings.put("xhttpSettings", xhttpSettings(host, query))
            "tcp", "raw" -> Unit
            "h2", "http" -> settings.put("httpSettings", httpSettings(host, query))
            else -> Unit
        }

        return settings
    }

    private fun tlsSettings(host: String, query: Map<String, String>): JSONObject {
        val serverName = query["sni"] ?: query["serverName"] ?: host
        val tls = JSONObject().put("serverName", serverName)
        if ((query["allowInsecure"] ?: query["insecure"]).isTruthy()) tls.put("allowInsecure", true)
        query["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
        query["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(',').map(String::trim))) }
        return tls
    }

    private fun realitySettings(host: String, query: Map<String, String>): JSONObject {
        return JSONObject()
            .put("serverName", query["sni"] ?: query["serverName"] ?: host)
            .put("fingerprint", query["fp"].orEmpty().ifBlank { "chrome" })
            .put("publicKey", query["pbk"] ?: query["publicKey"] ?: "")
            .put("shortId", query["sid"] ?: query["shortId"] ?: "")
            .put("spiderX", query["spx"] ?: query["spiderX"] ?: "/")
    }

    private fun wsSettings(host: String, query: Map<String, String>): JSONObject {
        val headers = JSONObject()
        (query["host"] ?: query["authority"])?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
        return JSONObject()
            .put("path", query["path"].orEmpty().ifBlank { "/" })
            .put("headers", headers)
    }

    private fun grpcSettings(query: Map<String, String>): JSONObject {
        val grpc = JSONObject()
            .put("serviceName", query["serviceName"] ?: query["path"] ?: "")
        (query["authority"] ?: query["host"])?.takeIf { it.isNotBlank() }?.let { grpc.put("authority", it) }
        return grpc
    }

    private fun httpUpgradeSettings(host: String, query: Map<String, String>): JSONObject {
        val headers = JSONObject()
        (query["host"] ?: query["authority"])?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
        return JSONObject()
            .put("path", query["path"].orEmpty().ifBlank { "/" })
            .put("host", query["host"] ?: host)
            .put("headers", headers)
    }

    private fun xhttpSettings(host: String, query: Map<String, String>): JSONObject {
        val xhttp = JSONObject()
            .put("path", query["path"].orEmpty().ifBlank { "/" })
            .put("host", query["host"] ?: host)
        query["mode"]?.takeIf { it.isNotBlank() }?.let { xhttp.put("mode", it) }
        query["extra"]?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()?.let { extra -> xhttp.put("extra", extra) }
        }
        return xhttp
    }

    private fun httpSettings(host: String, query: Map<String, String>): JSONObject {
        val hostValue = query["host"] ?: query["authority"] ?: host
        return JSONObject()
            .put("path", query["path"].orEmpty().ifBlank { "/" })
            .put("host", JSONArray(hostValue.split(',').map(String::trim)))
    }

    private fun xrayBinary(): File {
        val file = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!file.exists()) {
            error("Xray core не найден для ABI ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
        }
        return file
    }

    private fun waitUntilReady(proc: Process, port: Int) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                error("Xray не запустился: ${logs.get().ifBlank { "no output" }}")
            }
            if (canConnect(port)) return
            Thread.sleep(120)
        }
        error("Xray SOCKS не открылся: ${logs.get().ifBlank { "timeout" }}")
    }

    private fun verifyOutbound(proc: Process, port: Int) {
        runCatching {
            socksConnect(port, "connectivitycheck.gstatic.com", 80)
        }.onSuccess {
            DebugLog.append(context, "xray outbound check success")
        }.onFailure {
            if (!proc.isAlive) {
                error("Xray остановился при проверке VLESS: ${logs.get().ifBlank { "no output" }}")
            }
            val details = logs.get().lineSequence().toList().takeLast(8).joinToString("\n").ifBlank {
                it.message.orEmpty()
            }
            error("VLESS не прошёл проверку подключения: $details")
        }
    }

    private fun socksConnect(port: Int, host: String, targetPort: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.soTimeout = 8000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val auth = input.readExact(2)
            if (auth[0].toInt() != 0x05 || auth[1].toInt() != 0x00) {
                error("SOCKS auth failed")
            }

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            require(hostBytes.size <= 255) { "SOCKS host is too long" }
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
            output.write(hostBytes)
            output.write(byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
            output.flush()

            val head = input.readExact(4)
            if (head[0].toInt() != 0x05 || head[1].toInt() != 0x00) {
                error("SOCKS connect failed: ${head[1].toInt() and 0xff}")
            }
            val addressLength = when (head[3].toInt() and 0xff) {
                0x01 -> 4
                0x03 -> input.read()
                0x04 -> 16
                else -> error("SOCKS response has unknown address type")
            }
            if (addressLength < 0) error("SOCKS response is truncated")
            input.readExact(addressLength + 2)
        }
    }

    private fun drainLogs(proc: Process) {
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        logs.updateAndGet { old ->
                            val next = if (old.isBlank()) line else "$old\n$line"
                            if (next.length > 3000) next.takeLast(3000) else next
                        }
                    }
                }
            }.onFailure {
                if (proc.isAlive) DebugLog.append(context, "xray log reader failed", it)
            }
        }.apply {
            name = "xray-log"
            isDaemon = true
            start()
        }
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 200)
        }
    }.isSuccess

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun InputStream.readExact(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(buffer, offset, size - offset)
            if (read < 0) error("Stream closed")
            offset += read
        }
        return buffer
    }

    private fun String?.isTruthy(): Boolean {
        return this == "1" || this.equals("true", ignoreCase = true)
    }

    companion object {
        fun isAvailable(context: Context): Boolean {
            return File(context.applicationInfo.nativeLibraryDir, "libxray.so").exists()
        }
    }
}
