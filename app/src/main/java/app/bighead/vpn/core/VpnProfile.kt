package app.bighead.vpn.core

data class VpnProfile(
    val id: String,
    val name: String,
    val uri: String,
    val protocol: Protocol,
)
