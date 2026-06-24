package app.bighead.vpn.core

import android.net.Uri

enum class Protocol(val label: String) {
    VLESS("VLESS"),
    HYSTERIA("Hysteria");

    fun defaultName(uri: String): String {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        val remark = parsed?.fragment?.takeIf { it.isNotBlank() }
        val host = parsed?.host?.takeIf { it.isNotBlank() }
        return remark ?: host ?: label
    }

    companion object {
        fun fromUri(uri: String): Protocol? {
            val lower = uri.trim().lowercase()
            return when {
                lower.startsWith("vless://") -> VLESS
                lower.startsWith("hysteria://") || lower.startsWith("hysteria2://") || lower.startsWith("hy2://") -> HYSTERIA
                else -> null
            }
        }
    }
}
