package app.bighead.vpn.core

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("big_head_vpn", Context.MODE_PRIVATE)

    var activeProfile: VpnProfile?
        get() {
            val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
            return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACTIVE_PROFILE_ID) else putString(KEY_ACTIVE_PROFILE_ID, value.id)
            }.apply()
        }

    var profiles: List<VpnProfile>
        get() {
            val stored = prefs.getString(KEY_PROFILES, null)
            if (stored == null) {
                return legacyProfile()?.let(::listOf).orEmpty()
            }

            val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uri = item.optString("uri")
                    val protocol = Protocol.fromUri(uri) ?: continue
                    add(
                        VpnProfile(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name").ifBlank { protocol.defaultName(uri) },
                            uri = uri,
                            protocol = protocol,
                            subscriptionId = item.optString("subscriptionId").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { profile ->
                array.put(
                    JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("uri", profile.uri)
                        .apply { profile.subscriptionId?.let { put("subscriptionId", it) } }
                )
            }

            prefs.edit()
                .putString(KEY_PROFILES, array.toString())
                .apply {
                    val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
                    if (value.none { it.id == activeId }) {
                        if (value.isEmpty()) remove(KEY_ACTIVE_PROFILE_ID) else putString(KEY_ACTIVE_PROFILE_ID, value.first().id)
                    }
                }
                .apply()
        }

    fun addProfiles(imported: List<VpnProfile>): Int {
        if (imported.isEmpty()) return 0
        val existing = profiles
        val existingUris = existing.map { it.uri }.toSet()
        val fresh = imported.filterNot { it.uri in existingUris }
        if (fresh.isEmpty()) return 0
        profiles = existing + fresh
        if (activeProfile == null) activeProfile = fresh.first()
        return fresh.size
    }

    fun removeProfile(profileId: String) {
        profiles = profiles.filterNot { it.id == profileId }
    }

    fun removeProfiles(profileIds: Set<String>) {
        profiles = profiles.filterNot { it.id in profileIds }
    }

    fun restoreProfiles(removed: List<VpnProfile>, index: Int) {
        if (removed.isEmpty()) return
        val current = profiles.filterNot { profile -> removed.any { it.id == profile.id } }.toMutableList()
        current.addAll(index.coerceIn(0, current.size), removed)
        profiles = current
    }

    var subscriptionGroups: List<SubscriptionGroup>
        get() {
            val stored = prefs.getString(KEY_SUBSCRIPTIONS, "[]").orEmpty().ifBlank { "[]" }
            val array = runCatching { JSONArray(stored) }.getOrNull()
                ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val url = item.optString("url")
                    if (id.isBlank() || url.isBlank()) continue
                    add(
                        SubscriptionGroup(
                            id = id,
                            name = item.optString("name").ifBlank { "Подписка" },
                            url = url,
                            updatedAt = item.optLong("updatedAt"),
                        ),
                    )
                }
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { group ->
                array.put(
                    JSONObject()
                        .put("id", group.id)
                        .put("name", group.name)
                        .put("url", group.url)
                        .put("updatedAt", group.updatedAt),
                )
            }
            prefs.edit().putString(KEY_SUBSCRIPTIONS, array.toString()).apply()
        }

    fun subscriptionByUrl(url: String): SubscriptionGroup? =
        subscriptionGroups.firstOrNull { it.url == url }

    fun saveSubscription(group: SubscriptionGroup, imported: List<VpnProfile>) {
        val tagged = imported.map { it.copy(subscriptionId = group.id) }
        val importedUris = tagged.map { it.uri }.toSet()
        val currentProfiles = profiles
        val insertAt = currentProfiles.indexOfFirst { it.subscriptionId == group.id || it.uri in importedUris }
            .takeIf { it >= 0 } ?: currentProfiles.size
        val updatedProfiles = currentProfiles.filterNot { it.subscriptionId == group.id || it.uri in importedUris }.toMutableList()
        updatedProfiles.addAll(insertAt.coerceAtMost(updatedProfiles.size), tagged)
        profiles = updatedProfiles

        val currentGroups = subscriptionGroups
        subscriptionGroups = if (currentGroups.any { it.id == group.id }) {
            currentGroups.map { if (it.id == group.id) group else it }
        } else {
            currentGroups + group
        }
    }

    fun removeSubscription(groupId: String) {
        profiles = profiles.filterNot { it.subscriptionId == groupId }
        subscriptionGroups = subscriptionGroups.filterNot { it.id == groupId }
        collapsedSubscriptionIds = collapsedSubscriptionIds - groupId
    }

    var collapsedSubscriptionIds: Set<String>
        get() = prefs.getStringSet(KEY_COLLAPSED_SUBSCRIPTIONS, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_COLLAPSED_SUBSCRIPTIONS, value).apply()
        }

    fun toggleSubscriptionCollapsed(groupId: String) {
        collapsedSubscriptionIds = collapsedSubscriptionIds.toMutableSet().apply {
            if (!add(groupId)) remove(groupId)
        }
    }

    private fun legacyProfile(): VpnProfile? {
        val uri = prefs.getString(KEY_PROFILE_URI, null) ?: return null
        val protocol = Protocol.fromUri(uri) ?: return null
        val name = prefs.getString(KEY_PROFILE_NAME, null) ?: protocol.defaultName(uri)
        return VpnProfile(
            id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString(),
            name = name,
            uri = uri,
            protocol = protocol,
        )
    }

    fun saveProfile(profile: VpnProfile) {
        profiles = profiles.map { if (it.id == profile.id) profile else it }
    }

    fun setActiveProfile(profileId: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
    }

    fun importFromText(text: String): List<VpnProfile> {
        val sources = buildList {
            add(text)
            decodeBase64Subscription(text)?.let(::add)
        }

        return sources.asSequence()
            .flatMap { source -> PROFILE_URI_REGEX.findAll(source).map { it.value } }
            .map { it.trim().trimEnd(',', ';') }
            .filter { Protocol.fromUri(it) != null }
            .map { uri ->
                val protocol = Protocol.fromUri(uri)!!
                VpnProfile(
                    id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString(),
                    name = protocol.defaultName(uri),
                    uri = uri,
                    protocol = protocol,
                )
            }
            .distinctBy { it.uri }
            .toList()
    }

    private fun decodeBase64Subscription(text: String): String? {
        val compact = text.trim().replace("\n", "").replace("\r", "")
        if (compact.isBlank() || compact.startsWith("http", ignoreCase = true)) return null

        return runCatching {
            String(Base64.decode(compact, Base64.DEFAULT))
        }.getOrNull()?.takeIf { PROFILE_URI_REGEX.containsMatchIn(it) }
    }

    var selectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, value).apply()
        }

    var vpnRunning: Boolean
        get() = prefs.getBoolean(KEY_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RUNNING, value).apply()
        }

    var vpnConnecting: Boolean
        get() = prefs.getBoolean(KEY_CONNECTING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CONNECTING, value).apply()
        }

    var vpnStartedAt: Long
        get() = prefs.getLong(KEY_STARTED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_STARTED_AT, value).apply()
        }

    var lastVpnError: String?
        get() = prefs.getString(KEY_LAST_VPN_ERROR, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LAST_VPN_ERROR) else putString(KEY_LAST_VPN_ERROR, value)
            }.apply()
        }

    var profileLatencies: Map<String, Long>
        get() {
            val stored = prefs.getString(KEY_PROFILE_LATENCIES, null) ?: return emptyMap()
            val json = runCatching { JSONObject(stored) }.getOrNull() ?: return emptyMap()
            return buildMap {
                json.keys().forEach { key ->
                    val value = json.optLong(key, -1L)
                    if (value >= 0L) put(key, value)
                }
            }
        }
        set(value) {
            val json = JSONObject()
            value.forEach { (id, latency) -> json.put(id, latency) }
            prefs.edit().putString(KEY_PROFILE_LATENCIES, json.toString()).apply()
        }

    fun setProfileLatency(profileId: String, latencyMs: Long?) {
        val updated = profileLatencies.toMutableMap()
        if (latencyMs == null) updated.remove(profileId) else updated[profileId] = latencyMs
        profileLatencies = updated
    }

    fun latencyLabel(profileId: String): String {
        val latency = profileLatencies[profileId] ?: return "—"
        return String.format(Locale.US, "%d ms", latency)
    }

    companion object {
        private const val KEY_PROFILE_NAME = "profile_name"
        private const val KEY_PROFILE_URI = "profile_uri"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_RUNNING = "running"
        private const val KEY_CONNECTING = "connecting"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_LAST_VPN_ERROR = "last_vpn_error"
        private const val KEY_PROFILE_LATENCIES = "profile_latencies"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_COLLAPSED_SUBSCRIPTIONS = "collapsed_subscriptions"
        private val PROFILE_URI_REGEX = Regex("""(?i)\b(?:vless|hysteria2|hysteria|hy2)://[^\s"'<>]+""")
    }
}
