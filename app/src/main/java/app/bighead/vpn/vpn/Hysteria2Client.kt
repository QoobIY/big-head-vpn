package app.bighead.vpn.vpn

import android.content.Context
import android.os.Build
import app.bighead.vpn.core.VpnProfile
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Hysteria2Client(
    private val context: Context,
    private val profile: VpnProfile,
) : Closeable {
    private val logs = AtomicReference("")
    private var process: Process? = null
    var port: Int = 0
        private set

    fun start() {
        val binary = hysteriaBinary()
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
        }
        if (!binary.canExecute()) {
            error("Hysteria2 core не исполняется на этом Android")
        }

        port = freePort()
        val config = writeConfig(port)
        val proc = ProcessBuilder(
            binary.absolutePath,
            "-c",
            config.absolutePath,
            "--log-level",
            "error",
            "--disable-update-check",
        )
            .redirectErrorStream(true)
            .start()
        process = proc
        drainLogs(proc)
        waitUntilReady(proc, port)
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
            DebugLog.append(context, "hysteria2 stop failed", it)
        }
        process = null
    }

    private fun writeConfig(socksPort: Int): File {
        val uri = URI(profile.uri.substringBefore('#'))
        val server = uri.rawAuthority
            ?.substringAfterLast('@')
            ?.ifBlank { null }
            ?: error("Hysteria2 server is empty")
        val auth = uri.rawUserInfo?.decodeUriComponent().orEmpty()
        val query = parseQuery(uri.rawQuery.orEmpty())
        val sni = query["sni"]
        val insecure = query["insecure"]?.let { it == "1" || it.equals("true", ignoreCase = true) } == true
        val pinSha256 = query["pinSHA256"] ?: query["pinsha256"]
        val obfs = query["obfs"]
        val obfsPassword = query["obfs-password"] ?: query["obfs_password"]

        val config = buildString {
            appendLine("server: ${server.yamlQuote()}")
            if (auth.isNotBlank()) appendLine("auth: ${auth.yamlQuote()}")
            appendLine("tls:")
            if (!sni.isNullOrBlank()) appendLine("  sni: ${sni.yamlQuote()}")
            appendLine("  insecure: $insecure")
            if (!pinSha256.isNullOrBlank()) appendLine("  pinSHA256: ${pinSha256.yamlQuote()}")
            if (!obfs.isNullOrBlank()) {
                appendLine("obfs:")
                appendLine("  type: ${obfs.yamlQuote()}")
                if (!obfsPassword.isNullOrBlank()) {
                    appendLine("  ${obfs.lowercase()}:")
                    appendLine("    password: ${obfsPassword.yamlQuote()}")
                }
            }
            appendLine("socks5:")
            appendLine("  listen: ${"127.0.0.1:$socksPort".yamlQuote()}")
            appendLine("  disableUDP: false")
        }

        val file = File(context.cacheDir, "hysteria2-client.yml")
        file.writeText(config)
        return file
    }

    private fun hysteriaBinary(): File {
        val file = File(context.applicationInfo.nativeLibraryDir, "libhysteria2.so")
        if (!file.exists()) {
            error("Hysteria2 core не найден для ABI ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
        }
        return file
    }

    private fun waitUntilReady(proc: Process, port: Int) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                error("Hysteria2 не запустился: ${logs.get().ifBlank { "no output" }}")
            }
            if (canConnect(port)) return
            Thread.sleep(120)
        }
        error("Hysteria2 SOCKS не открылся: ${logs.get().ifBlank { "timeout" }}")
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
                if (proc.isAlive) DebugLog.append(context, "hysteria2 log reader failed", it)
            }
        }.apply {
            name = "hysteria2-log"
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

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = part.substringAfter('=', "")
                key.decodeUriComponent() to value.decodeUriComponent()
            }
            .toMap()
    }

    private fun String.decodeUriComponent(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private fun String.yamlQuote(): String = "'${replace("'", "''")}'"
}
