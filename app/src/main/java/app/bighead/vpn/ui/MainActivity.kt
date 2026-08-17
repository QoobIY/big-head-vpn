package app.bighead.vpn.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.bighead.vpn.R
import app.bighead.vpn.core.InstalledApp
import app.bighead.vpn.core.InstalledAppRepository
import app.bighead.vpn.core.ProfileStore
import app.bighead.vpn.core.SubscriptionGroup
import app.bighead.vpn.core.VpnProfile
import app.bighead.vpn.vpn.BigHeadVpnService
import app.bighead.vpn.vpn.DebugLog
import app.bighead.vpn.vpn.ProfileLatencyChecker
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var statusText: TextView
    private lateinit var profileText: TextView
    private lateinit var errorText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var appsText: TextView
    private lateinit var connectButton: Button
    private lateinit var serversContainer: LinearLayout
    private lateinit var serversFrame: FrameLayout
    private lateinit var selectedAppsContainer: LinearLayout
    private lateinit var selectedAppsScroll: ScrollView
    private lateinit var rootView: LinearLayout
    private lateinit var contentRoot: FrameLayout
    private lateinit var pingSpinner: ProgressBar
    private var appSelectorOverlay: View? = null
    private var snackbarView: View? = null
    private var snackbarDismiss: Runnable? = null
    private var checkingPing = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uptimeTicker = object : Runnable {
        override fun run() {
            updateUptime()
            if (store.vpnRunning && store.vpnStartedAt > 0L) {
                uiHandler.postDelayed(this, 1000L)
            }
        }
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BigHeadVpnService.ACTION_STATE_CHANGED) {
                renderState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        registerVpnStateReceiver()
        renderState()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uptimeTicker)
        runCatching { unregisterReceiver(stateReceiver) }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (appSelectorOverlay != null) {
            closeAppSelector()
            return
        }
        super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            store.vpnConnecting = true
            store.vpnRunning = false
            renderState()
            startService(Intent(this, BigHeadVpnService::class.java))
        }
    }

    private fun buildContent(): View {
        val screen = FrameLayout(this).apply { setBackgroundColor(COLOR_CANVAS) }
        contentRoot = screen
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            isFillViewport = true
        }
        screen.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(16), dp(14), dp(14))
        }
        rootView = root
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "Big Head VPN"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            includeFontPadding = false
        })

        root.addView(TextView(this).apply {
            text = "VPN"
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        })

        val statusPanel = panel()
        root.addView(statusPanel)
        statusText = label("Отключено", 22f, COLOR_INK, bold = true)
        statusPanel.addView(statusText)
        profileText = label("", 15f, COLOR_MUTED)
        profileText.setPadding(0, dp(8), 0, 0)
        statusPanel.addView(profileText)
        errorText = label("", 13f, COLOR_DANGER, bold = true).apply {
            visibility = View.GONE
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, 0)
            setOnClickListener {
                store.lastVpnError?.let { error ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Ошибка VPN")
                        .setMessage(error)
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            }
        }
        statusPanel.addView(errorText)
        uptimeText = label("", 15f, COLOR_MUTED)
        uptimeText.setPadding(0, dp(6), 0, 0)
        statusPanel.addView(uptimeText)

        connectButton = primaryButton("Включить")
        connectButton.setOnClickListener { toggleVpn() }
        root.addView(connectButton)

        val serversPanel = panel()
        root.addView(serversPanel)
        serversPanel.addView(sectionHeader(
            "Серверы",
            iconActionButton(R.drawable.ic_latency, "Проверить задержку").apply {
                setOnClickListener { checkAllPings() }
            },
            iconActionButton(R.drawable.ic_sort_latency, "Сортировать по задержке").apply {
                setOnClickListener { sortServersByPing() }
            },
        ))
        val serversScroll = ScrollView(this).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            serversContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
            addView(serversContainer)
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL,
                )
                false
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        serversFrame = FrameLayout(this).apply {
            addView(serversScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            pingSpinner = ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                visibility = View.GONE
            }
            addView(pingSpinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(300),
            ).apply { bottomMargin = dp(8) }
        }
        serversPanel.addView(serversFrame)
        serversPanel.addView(actionRow(
            secondaryButton("Вставить из буфера").apply { setOnClickListener { pasteProfiles() } },
            secondaryButton("Ввести вручную").apply { setOnClickListener { showProfileDialog() } },
        ))

        val appsPanel = panel()
        root.addView(appsPanel)
        appsPanel.addView(sectionTitle("Приложения"))
        appsText = label("", 15f, COLOR_MUTED)
        appsText.setPadding(0, dp(8), 0, dp(12))
        appsPanel.addView(appsText)
        selectedAppsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        selectedAppsScroll = ScrollView(this).apply {
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(selectedAppsContainer)
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL,
                )
                false
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            )
        }
        appsPanel.addView(selectedAppsScroll)
        appsPanel.addView(secondaryButton("Выбрать приложения").apply {
            setOnClickListener { showAppsDialog() }
        })

        val note = label(
            "Если приложения не выбраны, VPN работает для всех. Если выбрать несколько, туннель включится только для них.",
            13f,
            COLOR_MUTED,
        )
        note.setPadding(0, dp(18), 0, 0)
        root.addView(note)

        root.addView(secondaryButton("Логи").apply {
            setOnClickListener { showDebugLogs() }
        })

        renderState()
        return screen
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun renderState() {
        val profile = store.activeProfile
        val running = store.vpnRunning
        val connecting = store.vpnConnecting
        statusText.text = when {
            connecting -> "Подключение..."
            running -> "Подключено"
            else -> "Отключено"
        }
        connectButton.text = when {
            connecting -> "Подключается..."
            running -> "Выключить"
            else -> "Включить"
        }
        connectButton.isEnabled = !connecting
        profileText.text = if (profile == null) {
            "Добавьте сервер из буфера или вручную"
        } else {
            "${profile.name} · ${profile.protocol.label}"
        }
        errorText.text = store.lastVpnError?.let { "Ошибка: $it" }.orEmpty()
        errorText.visibility = if (store.lastVpnError == null) View.GONE else View.VISIBLE

        renderServers()
        renderSelectedApps()
        updateUptime()
        scheduleUptimeTicker()
    }

    private fun updateUptime() {
        uptimeText.text = if (store.vpnRunning && store.vpnStartedAt > 0L) {
            "Время работы: ${formatDuration(System.currentTimeMillis() - store.vpnStartedAt)}"
        } else {
            ""
        }
    }

    private fun scheduleUptimeTicker() {
        uiHandler.removeCallbacks(uptimeTicker)
        if (store.vpnRunning && store.vpnStartedAt > 0L) {
            uiHandler.postDelayed(uptimeTicker, 1000L)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun renderServers() {
        serversContainer.removeAllViews()
        val profiles = store.profiles
        pingSpinner.visibility = if (checkingPing) View.VISIBLE else View.GONE

        if (profiles.isEmpty()) {
            serversFrame.layoutParams.height = dp(64)
            serversFrame.requestLayout()
            serversContainer.addView(emptyText("Список серверов пуст"))
            return
        }

        val groups = store.subscriptionGroups
        val collapsedIds = store.collapsedSubscriptionIds
        val visibleGroups = groups.filter { group -> profiles.any { it.subscriptionId == group.id } }
        val standalone = profiles.filter { it.subscriptionId == null || groups.none { group -> group.id == it.subscriptionId } }
        val rowCount = (visibleGroups.sumOf { group ->
            if (group.id in collapsedIds) 0 else {
                profiles.filter { it.subscriptionId == group.id }
                    .distinctBy { compactServerName(it.name).lowercase() }
                    .size
            }
        } + standalone.distinctBy { compactServerName(it.name).lowercase() }.size)
        val headerCount = visibleGroups.size + if (standalone.isNotEmpty() && groups.isNotEmpty()) 1 else 0
        serversFrame.layoutParams.height = dp((rowCount * 72 + headerCount * 52).coerceIn(72, 300))
        serversFrame.requestLayout()

        groups.forEach { group ->
            val groupProfiles = profiles.filter { it.subscriptionId == group.id }
            if (groupProfiles.isNotEmpty()) {
                val collapsed = group.id in collapsedIds
                serversContainer.addView(subscriptionHeader(group, groupProfiles.size, collapsed))
                if (!collapsed) renderServerRows(groupProfiles)
            }
        }

        if (standalone.isNotEmpty()) {
            if (groups.isNotEmpty()) serversContainer.addView(groupLabel("Добавленные вручную", standalone.size))
            renderServerRows(standalone)
        }
    }

    private fun renderServerRows(profiles: List<VpnProfile>) {
        profiles.groupBy { compactServerName(it.name).lowercase() }.values.forEach { variants ->
            val activeId = store.activeProfile?.id
            val active = variants.any { it.id == activeId }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(7), dp(8), dp(7))
                background = rounded(if (active) COLOR_SIGNAL_SOFT else COLOR_SURFACE, dp(8), COLOR_LINE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(5) }
            }

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(label(compactServerName(variants.first().name), 14f, COLOR_INK, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(deleteIconButton().apply {
                contentDescription = "Удалить сервер"
                setOnClickListener { removeServerRow(variants) }
            })
            row.addView(titleRow)

            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            variants.forEach { profile ->
                val selected = profile.id == activeId
                chips.addView(protocolChip(profile, selected))
            }
            row.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            serversContainer.addView(row)
        }
    }

    private fun compactServerName(name: String): String {
        val compact = name
            .replace(
                Regex("""(?i)\s+[0-9a-f]{8,32}\s+(?:h2|grpc|xtls|xhttp(?:-cdn)?|httpupgrade|ws|tcp)\s*$"""),
                " ",
            )
            .replace(Regex("""(?i)\s+(?:h2|grpc|xtls|xhttp(?:-cdn)?|httpupgrade|ws|tcp)\s*$"""), " ")
            .replace(Regex("""(?i)\b(?:vless|hysteria2?|hy2)\b"""), " ")
            .replace(Regex("""\(\s*\)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '|', '/', '·', '—', '-', '_')
        return compact.ifBlank { name }
    }

    private fun protocolChip(profile: VpnProfile, active: Boolean): Button {
        val ping = store.profileLatencies[profile.id]?.let { "$it ms" } ?: "—"
        return Button(this).apply {
            text = "${if (active) "● " else ""}${profileVariantLabel(profile)} · $ping"
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(COLOR_INK)
            background = rounded(if (active) COLOR_SIGNAL else Color.WHITE, dp(7), COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                rightMargin = dp(6)
                topMargin = dp(5)
            }
            setOnClickListener { selectProfile(profile) }
        }
    }

    private fun profileVariantLabel(profile: VpnProfile): String {
        if (profile.protocol == app.bighead.vpn.core.Protocol.HYSTERIA) return "Hysteria H2"
        val uri = runCatching { Uri.parse(profile.uri.substringBefore('#')) }.getOrNull()
        val transport = uri?.getQueryParameter("type").orEmpty().ifBlank { "tcp" }.lowercase()
        val flow = uri?.getQueryParameter("flow").orEmpty().lowercase()
        return when {
            "vision" in flow -> "VLESS XTLS"
            transport == "grpc" -> "VLESS gRPC"
            transport == "xhttp" || transport == "splithttp" -> "VLESS XHTTP"
            transport == "httpupgrade" -> "VLESS HTTPUpgrade"
            transport == "ws" -> "VLESS WS"
            else -> "VLESS ${transport.uppercase()}"
        }
    }

    private fun selectProfile(profile: VpnProfile) {
        if (store.activeProfile?.id == profile.id) return
        store.setActiveProfile(profile.id)
        if (store.vpnRunning || store.vpnConnecting) {
            store.vpnConnecting = true
            store.vpnRunning = false
            startService(
                Intent(this, BigHeadVpnService::class.java)
                    .setAction(BigHeadVpnService.ACTION_SWITCH_PROFILE),
            )
        }
        renderState()
    }

    private fun subscriptionHeader(group: SubscriptionGroup, count: Int, collapsed: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(5))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconActionButton(
                    if (collapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more,
                    if (collapsed) "Развернуть группу" else "Свернуть группу",
                ).apply {
                    setOnClickListener { toggleSubscriptionGroup(group.id) }
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(3) })
                addView(groupLabel(group.name, count).apply {
                    setPadding(0, dp(7), 0, dp(7))
                    setOnClickListener { toggleSubscriptionGroup(group.id) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(compactActionButton("Обновить").apply {
                    setOnClickListener { refreshSubscription(group) }
                })
                addView(compactDangerButton("Удалить").apply {
                    setOnClickListener { confirmDeleteSubscription(group) }
                })
            })
            if (!collapsed) {
                addView(label(group.url, 11f, COLOR_MUTED).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })
            }
        }
    }

    private fun toggleSubscriptionGroup(groupId: String) {
        store.toggleSubscriptionCollapsed(groupId)
        renderServers()
    }

    private fun groupLabel(name: String, count: Int): TextView =
        label("$name · $count", 13f, COLOR_MUTED, bold = true)

    private fun removeServerRow(variants: List<VpnProfile>) {
        val allProfiles = store.profiles
        val removedIds = variants.map { it.id }.toSet()
        val firstIndex = allProfiles.indexOfFirst { it.id in removedIds }.coerceAtLeast(0)
        val oldActive = store.activeProfile
        val vpnWasActive = store.vpnRunning || store.vpnConnecting
        store.removeProfiles(removedIds)
        reconcileVpnAfterRemoval(oldActive?.id in removedIds)
        renderState()
        showUndoMessage(
            if (variants.size == 1) "Сервер удалён" else "Сервер и ${variants.size} протокола удалены",
        ) {
            store.restoreProfiles(variants, firstIndex)
            if (!vpnWasActive) oldActive?.takeIf { it.id in removedIds }?.let { store.activeProfile = it }
            renderState()
        }
    }

    private fun confirmDeleteSubscription(group: SubscriptionGroup) {
        val count = store.profiles.count { it.subscriptionId == group.id }
        AlertDialog.Builder(this)
            .setTitle("Удалить группу «${group.name}»?")
            .setMessage("Будут удалены все профили группы: $count. Это действие нельзя отменить.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                val activeRemoved = store.activeProfile?.subscriptionId == group.id
                store.removeSubscription(group.id)
                reconcileVpnAfterRemoval(activeRemoved)
                renderState()
            }
            .show()
    }

    private fun reconcileVpnAfterRemoval(activeRemoved: Boolean) {
        if (!activeRemoved || (!store.vpnRunning && !store.vpnConnecting)) return
        val action = if (store.activeProfile == null) {
            BigHeadVpnService.ACTION_STOP
        } else {
            store.vpnConnecting = true
            store.vpnRunning = false
            BigHeadVpnService.ACTION_SWITCH_PROFILE
        }
        startService(Intent(this, BigHeadVpnService::class.java).setAction(action))
    }

    private fun showUndoMessage(message: String, undo: () -> Unit) {
        snackbarDismiss?.let(uiHandler::removeCallbacks)
        snackbarView?.let(contentRoot::removeView)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(6), dp(6))
            background = rounded(COLOR_INK, dp(9), COLOR_INK)
            elevation = dp(8).toFloat()
            addView(label(message, 14f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(compactActionButton("Отменить").apply {
                setTextColor(COLOR_SIGNAL)
                background = rounded(Color.TRANSPARENT, dp(7), Color.TRANSPARENT)
                setOnClickListener {
                    undo()
                    snackbarDismiss?.let(uiHandler::removeCallbacks)
                    snackbarView?.let(contentRoot::removeView)
                    snackbarView = null
                }
            })
        }
        snackbarView = bar
        contentRoot.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
            Gravity.BOTTOM,
        ).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(14)
        })
        val dismiss = Runnable {
            contentRoot.removeView(bar)
            if (snackbarView === bar) snackbarView = null
        }
        snackbarDismiss = dismiss
        uiHandler.postDelayed(dismiss, 5000L)
    }

    private fun checkAllPings() {
        val profiles = store.profiles
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте серверы", Toast.LENGTH_LONG).show()
            return
        }
        if (checkingPing) return

        checkingPing = true
        renderServers()

        Thread {
            val checker = ProfileLatencyChecker(this)
            profiles.forEach { profile ->
                val latency = runCatching { checker.check(profile) }.getOrNull()
                store.setProfileLatency(profile.id, latency)
                runOnUiThread { renderServers() }
            }
            runOnUiThread {
                checkingPing = false
                sortServersByPing(showToast = false)
                Toast.makeText(this, "Проверка завершена", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun sortServersByPing(showToast: Boolean = true) {
        val latencies = store.profileLatencies
        if (latencies.isEmpty()) {
            if (showToast) Toast.makeText(this, "Сначала проверьте пинг", Toast.LENGTH_LONG).show()
            return
        }
        store.profiles = store.profiles.sortedWith(
            compareBy(
                { profile -> latencies[profile.id] ?: Long.MAX_VALUE },
                { profile -> profile.name.lowercase() },
            )
        )
        renderState()
        if (showToast) Toast.makeText(this, "Серверы отсортированы по пингу", Toast.LENGTH_SHORT).show()
    }

    private fun renderSelectedApps() {
        selectedAppsContainer.removeAllViews()
        val selected = store.selectedPackages
        appsText.text = when (selected.size) {
            0 -> "VPN будет работать для всех приложений"
            1 -> "VPN будет работать для 1 выбранного приложения"
            else -> "VPN будет работать для приложений: ${selected.size}"
        }

        if (selected.isEmpty()) {
            selectedAppsScroll.layoutParams.height = 0
            selectedAppsScroll.requestLayout()
            return
        }

        val appNames = InstalledAppRepository(this).launcherApps()
            .filter { it.packageName in selected }

        selectedAppsScroll.layoutParams.height = dp((appNames.size * 58).coerceIn(58, 230))
        selectedAppsScroll.requestLayout()

        appNames.forEach { app ->
            selectedAppsContainer.addView(compactAppRow(app.label, app.packageName))
        }
        val unavailableCount = selected.size - appNames.size
        if (unavailableCount > 0) {
            selectedAppsContainer.addView(emptyText("Недоступных приложений: $unavailableCount"))
        }
    }

    private fun toggleVpn() {
        if (store.vpnConnecting) return
        if (store.vpnRunning) {
            startService(Intent(this, BigHeadVpnService::class.java).setAction(BigHeadVpnService.ACTION_STOP))
            store.vpnConnecting = false
            store.vpnRunning = false
            renderState()
            return
        }

        if (store.activeProfile == null) {
            Toast.makeText(this, "Сначала добавьте сервер", Toast.LENGTH_LONG).show()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST)
        } else {
            store.vpnConnecting = true
            store.vpnRunning = false
            renderState()
            startService(Intent(this, BigHeadVpnService::class.java))
        }
    }

    private fun registerVpnStateReceiver() {
        val filter = IntentFilter(BigHeadVpnService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    private fun pasteProfiles() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()

        if (isSubscriptionLink(text)) {
            importSubscription(text.trim())
            return
        }

        val imported = store.importFromText(text)
        val added = store.addProfiles(imported)
        val message = when {
            text.isBlank() -> "Буфер обмена пуст"
            imported.isEmpty() -> "В буфере нет VLESS или Hysteria профилей"
            added == 0 -> "Эти серверы уже добавлены"
            added == 1 -> "Добавлен 1 сервер"
            else -> "Добавлено серверов: $added"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        renderState()
    }

    private fun importSubscription(link: String, existingGroup: SubscriptionGroup? = store.subscriptionByUrl(link)) {
        Toast.makeText(this, if (existingGroup == null) "Добавляю подписку" else "Обновляю подписку", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { fetchSubscription(link) }
            runOnUiThread {
                result.onSuccess { body ->
                    val imported = store.importFromText(body)
                    if (imported.isEmpty()) {
                        Toast.makeText(this, "В подписке нет VLESS или Hysteria профилей", Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                    val oldActive = store.activeProfile
                    val group = (existingGroup ?: SubscriptionGroup(
                        id = UUID.randomUUID().toString(),
                        name = runCatching { URL(link).host.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { "Подписка" },
                        url = link,
                        updatedAt = 0L,
                    )).copy(updatedAt = System.currentTimeMillis())
                    store.saveSubscription(group, imported)
                    val activeRemoved = oldActive?.subscriptionId == group.id && store.profiles.none { it.id == oldActive.id }
                    reconcileVpnAfterRemoval(activeRemoved)
                    val message = if (existingGroup == null) {
                        "Подписка добавлена · профилей: ${imported.size}"
                    } else {
                        "Подписка обновлена · профилей: ${imported.size}"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    renderState()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "Не удалось загрузить подписку", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun refreshSubscription(group: SubscriptionGroup) {
        importSubscription(group.url, group)
    }

    private fun fetchSubscription(link: String): String {
        val connection = (URL(link).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("User-Agent", "BigHeadVPN/0.1")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Подписка ответила ошибкой HTTP $code")
            }
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun showProfileDialog() {
        val input = EditText(this).apply {
            hint = "vless://... или hysteria2://..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 4
            setSingleLine(false)
        }

        AlertDialog.Builder(this)
            .setTitle("Добавить сервер")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val text = input.text.toString().trim()
                if (isSubscriptionLink(text)) {
                    importSubscription(text)
                    return@setPositiveButton
                }

                val imported = store.importFromText(text)
                val added = store.addProfiles(imported)
                if (added == 0) {
                    Toast.makeText(this, "Профиль не найден или уже добавлен", Toast.LENGTH_LONG).show()
                }
                renderState()
            }
            .show()
    }

    private fun isSubscriptionLink(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)
    }

    private fun showDebugLogs() {
        val logText = DebugLog.read(this).ifBlank { "Лог пока пуст" }
        val textView = label(logText, 12f, COLOR_INK).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply {
            background = rounded(COLOR_SURFACE, dp(8), COLOR_LINE)
            addView(textView)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Логи VPN")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Очистить") { _, _ ->
                DebugLog.clear(this)
                Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAppsDialog() {
        val apps = InstalledAppRepository(this).launcherApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "Не удалось найти приложения", Toast.LENGTH_LONG).show()
            return
        }

        val selected = store.selectedPackages.toMutableSet()
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = rounded(COLOR_CANVAS, 0, COLOR_CANVAS)
        }
        appSelectorOverlay = overlay

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(sectionTitle("Выбор приложений"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(smallButton("Закрыть").apply {
            setOnClickListener { closeAppSelector() }
        })
        overlay.addView(titleRow)

        val counter = label("", 14f, COLOR_MUTED).apply {
            setPadding(0, dp(8), 0, dp(12))
        }
        overlay.addView(counter)

        val search = EditText(this).apply {
            hint = "Поиск по названию или пакету"
            setSingleLine(true)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.WHITE, dp(8), COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54),
            ).apply { bottomMargin = dp(12) }
        }
        overlay.addView(search)

        val appsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chips.addView(smallButton("Все").apply {
            setOnClickListener {
                selected.addAll(apps.map { it.packageName })
                renderAppSelectorRows(apps, selected, search.text.toString(), appsList, counter)
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        chips.addView(smallButton("Очистить").apply {
            setOnClickListener {
                selected.clear()
                renderAppSelectorRows(apps, selected, search.text.toString(), appsList, counter)
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        chips.addView(primaryButton("Готово").apply {
            setOnClickListener {
                store.selectedPackages = selected
                closeAppSelector()
                renderState()
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        overlay.addView(chips)

        val scroll = ScrollView(this).apply {
            addView(appsList)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        overlay.addView(scroll)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderAppSelectorRows(apps, selected, s?.toString().orEmpty(), appsList, counter)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderAppSelectorRows(apps, selected, "", appsList, counter)
        setContentView(overlay)
    }

    private fun renderAppSelectorRows(
        apps: List<InstalledApp>,
        selected: MutableSet<String>,
        query: String,
        container: LinearLayout,
        counter: TextView,
    ) {
        container.removeAllViews()
        counter.text = if (selected.isEmpty()) {
            "Выбрано 0 · VPN будет работать для всех приложений"
        } else {
            "Выбрано: ${selected.size}"
        }

        val normalizedQuery = query.trim().lowercase()
        val filtered = apps.filter { app ->
            normalizedQuery.isBlank() ||
                app.label.lowercase().contains(normalizedQuery) ||
                app.packageName.lowercase().contains(normalizedQuery)
        }

        if (filtered.isEmpty()) {
            container.addView(emptyText("Ничего не найдено"))
            return
        }

        filtered.forEach { app ->
            val active = app.packageName in selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(if (active) COLOR_SIGNAL_SOFT else Color.WHITE, dp(8), COLOR_LINE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    if (app.packageName in selected) selected.remove(app.packageName) else selected.add(app.packageName)
                    renderAppSelectorRows(apps, selected, query, container, counter)
                }
            }

            row.addView(TextView(this).apply {
                text = if (active) "✓" else "+"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (active) COLOR_INK else COLOR_MUTED)
            }, LinearLayout.LayoutParams(dp(34), dp(38)).apply { rightMargin = dp(8) })

            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(app.label, 16f, COLOR_INK, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(label(app.packageName, 12f, COLOR_MUTED).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            container.addView(row)
        }
    }

    private fun closeAppSelector() {
        appSelectorOverlay = null
        setContentView(buildContent())
    }

    private fun compactAppRow(label: String, packageName: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(COLOR_SURFACE, dp(8), COLOR_LINE)
            addView(label(label, 15f, COLOR_INK, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(label(packageName, 12f, COLOR_MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(Color.WHITE, dp(8), COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
    }

    private fun actionRow(left: View, right: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
            addView(right, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        }
    }

    private fun sectionHeader(title: String, vararg actions: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.forEachIndexed { index, action ->
                addView(action, LinearLayout.LayoutParams(dp(40), dp(36)).apply {
                    if (index > 0) leftMargin = dp(5)
                })
            }
        }
    }

    private fun primaryButton(textValue: String): Button {
        return button(textValue, COLOR_INK, COLOR_SIGNAL)
    }

    private fun secondaryButton(textValue: String): Button {
        return button(textValue, COLOR_INK, COLOR_SURFACE)
    }

    private fun smallButton(textValue: String): Button {
        return button(textValue, COLOR_MUTED, Color.TRANSPARENT).apply {
            textSize = 12f
            minHeight = dp(40)
            minWidth = dp(78)
            layoutParams = LinearLayout.LayoutParams(dp(86), dp(42))
        }
    }

    private fun compactActionButton(textValue: String): Button {
        return button(textValue, COLOR_INK, COLOR_SURFACE).apply {
            textSize = 11f
            minWidth = 0
            minHeight = 0
            setPadding(dp(7), 0, dp(7), 0)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(34)).apply { leftMargin = dp(5) }
        }
    }

    private fun iconActionButton(drawableRes: Int, description: String): ImageButton {
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            contentDescription = description
            tooltipText = description
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rounded(COLOR_SURFACE, dp(8), COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
        }
    }

    private fun compactDangerButton(textValue: String): Button {
        return compactActionButton(textValue).apply {
            setTextColor(COLOR_DANGER)
        }
    }

    private fun deleteIconButton(): Button {
        return button("×", Color.WHITE, COLOR_DANGER).apply {
            textSize = 18f
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
    }

    private fun button(textValue: String, textColor: Int, fillColor: Int): Button {
        return Button(this).apply {
            text = textValue
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            minHeight = dp(46)
            background = rounded(fillColor, dp(8), COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun sectionTitle(textValue: String): TextView {
        return label(textValue, 18f, COLOR_INK, bold = true)
    }

    private fun emptyText(textValue: String): TextView {
        return label(textValue, 14f, COLOR_MUTED).apply {
            setPadding(0, dp(8), 0, dp(12))
        }
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun rounded(fillColor: Int, radius: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val VPN_PERMISSION_REQUEST = 42
        private const val NOTIFICATION_PERMISSION_REQUEST = 43
        private val COLOR_CANVAS = Color.rgb(248, 246, 241)
        private val COLOR_SURFACE = Color.rgb(235, 240, 236)
        private val COLOR_SIGNAL = Color.rgb(232, 247, 101)
        private val COLOR_SIGNAL_SOFT = Color.rgb(245, 250, 205)
        private val COLOR_INK = Color.rgb(16, 20, 24)
        private val COLOR_MUTED = Color.rgb(92, 99, 103)
        private val COLOR_LINE = Color.rgb(216, 218, 210)
        private val COLOR_DANGER = Color.rgb(211, 68, 68)
    }
}
