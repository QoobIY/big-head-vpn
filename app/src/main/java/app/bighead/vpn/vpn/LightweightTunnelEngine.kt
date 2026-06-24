package app.bighead.vpn.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.bighead.vpn.core.Protocol
import app.bighead.vpn.core.VpnProfile
import java.io.File

class LightweightTunnelEngine(
    private val service: VpnService,
    private val selectedPackages: Set<String>,
) {
    private var tun: ParcelFileDescriptor? = null
    private var socksServer: Socks5Server? = null
    private var xrayClient: XrayClient? = null
    private var hysteria2Client: Hysteria2Client? = null
    private var configFile: File? = null

    fun start(profile: VpnProfile) {
        if (VpnService.prepare(service) != null) error("Нет разрешения Android VPN")

        val socksPort = when (profile.protocol) {
            Protocol.VLESS -> {
                if (XrayClient.isAvailable(service)) {
                    DebugLog.append(service, "starting vless via xray")
                    val client = XrayClient(service, profile).also { it.start() }
                    xrayClient = client
                    client.port
                } else {
                    DebugLog.append(service, "starting vless via kotlin fallback")
                    val socks = Socks5Server(VlessOutbound(profile) { socket -> service.protect(socket) }).also { it.start() }
                    socksServer = socks
                    socks.port
                }
            }
            Protocol.HYSTERIA -> {
                DebugLog.append(service, "starting hysteria2")
                val client = Hysteria2Client(service, profile).also { it.start() }
                hysteria2Client = client
                client.port
            }
        }

        val pfd = openTun()
        tun = pfd

        val config = writeHevConfig(socksPort)
        configFile = config
        DebugLog.append(service, "starting hev tunnel on socks port $socksPort")
        HevSocks5Tunnel.TProxyStartService(config.absolutePath, pfd.fd)
    }

    fun stop() {
        DebugLog.append(service, "engine stop begin")
        runCatching { HevSocks5Tunnel.TProxyStopService() }
            .onFailure { DebugLog.append(service, "hev stop failed", it) }
        runCatching { tun?.close() }
            .onFailure { DebugLog.append(service, "tun close failed", it) }
        tun = null
        runCatching { socksServer?.close() }
            .onFailure { DebugLog.append(service, "fallback socks close failed", it) }
        socksServer = null
        runCatching { xrayClient?.close() }
            .onFailure { DebugLog.append(service, "xray close failed", it) }
        xrayClient = null
        runCatching { hysteria2Client?.close() }
            .onFailure { DebugLog.append(service, "hysteria2 close failed", it) }
        hysteria2Client = null
        DebugLog.append(service, "engine stop end")
    }

    private fun openTun(): ParcelFileDescriptor {
        val builder = service.Builder()
            .setSession("Big Head VPN")
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (selectedPackages.isNotEmpty()) {
            selectedPackages.forEach { packageName ->
                runCatching { builder.addAllowedApplication(packageName) }
            }
        } else {
            runCatching { builder.addDisallowedApplication(service.packageName) }
        }

        return builder.establish() ?: error("Android VPN permission was revoked")
    }

    private fun writeHevConfig(socksPort: Int): File {
        val config = """
            tunnel:
              mtu: $TUN_MTU
              ipv4: $TUN_ADDRESS
            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: 'tcp'
            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              connect-timeout: 12000
              log-level: error
        """.trimIndent()

        val file = File(service.cacheDir, "hev-socks5.yml")
        file.writeText(config)
        return file
    }

    companion object {
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val TUN_MTU = 8500
    }
}
