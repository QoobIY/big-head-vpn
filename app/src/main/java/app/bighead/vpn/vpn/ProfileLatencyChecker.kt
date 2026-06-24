package app.bighead.vpn.vpn

import android.content.Context
import app.bighead.vpn.core.Protocol
import app.bighead.vpn.core.VpnProfile
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToLong

class ProfileLatencyChecker(private val context: Context) {
    fun check(profile: VpnProfile): Long {
        return when (profile.protocol) {
            Protocol.VLESS -> tcpConnectLatency(profile)
            Protocol.HYSTERIA -> hysteriaPing(profile)
        }
    }

    private fun tcpConnectLatency(profile: VpnProfile): Long {
        val uri = URI(profile.uri.substringBefore('#'))
        val host = uri.host ?: error("Host не найден")
        val port = if (uri.port > 0) uri.port else 443
        val start = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        }
        return elapsedMs(start)
    }

    private fun hysteriaPing(profile: VpnProfile): Long {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libhysteria2.so")
        if (!binary.exists()) error("Hysteria2 core не найден")
        if (!binary.canExecute()) binary.setExecutable(true, false)

        val config = writeHysteriaConfig(profile)
        val start = System.nanoTime()
        val process = ProcessBuilder(
            binary.absolutePath,
            "-c",
            config.absolutePath,
            "--disable-update-check",
            "ping",
            "1.1.1.1:80",
        )
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> output.appendLine(line) }
                }
            }.onFailure {
                if (process.isAlive) DebugLog.append(context, "hysteria ping log reader failed", it)
            }
        }.apply { start() }

        val finished = process.waitFor(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Таймаут ping")
        }
        reader.join(300)
        if (process.exitValue() != 0) {
            error(output.toString().lineSequence().lastOrNull()?.ifBlank { null } ?: "Hysteria ping failed")
        }
        return parseHysteriaTime(output.toString()) ?: elapsedMs(start)
    }

    private fun writeHysteriaConfig(profile: VpnProfile): File {
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
                    appendLine("  ${obfs.lowercase(Locale.US)}:")
                    appendLine("    password: ${obfsPassword.yamlQuote()}")
                }
            }
        }

        val file = File(context.cacheDir, "hysteria2-ping-${freePort()}.yml")
        file.writeText(config)
        return file
    }

    private fun parseHysteriaTime(output: String): Long? {
        val value = Regex("""time[=:"\s]+([0-9.]+)(ms|s|µs|us)""")
            .find(output)
            ?.groupValues
            ?: return null
        val amount = value[1].toDoubleOrNull() ?: return null
        return when (value[2]) {
            "s" -> (amount * 1000).roundToLong()
            "µs", "us" -> (amount / 1000).roundToLong().coerceAtLeast(1)
            else -> amount.roundToLong()
        }
    }

    private fun elapsedMs(startNanos: Long): Long =
        ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1)

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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val TIMEOUT_MS = 5000
    }
}
