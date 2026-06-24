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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.bighead.vpn.core.InstalledApp
import app.bighead.vpn.core.InstalledAppRepository
import app.bighead.vpn.core.ProfileStore
import app.bighead.vpn.vpn.BigHeadVpnService
import app.bighead.vpn.vpn.DebugLog
import app.bighead.vpn.vpn.ProfileLatencyChecker
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var statusText: TextView
    private lateinit var profileText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var appsText: TextView
    private lateinit var connectButton: Button
    private lateinit var serversContainer: LinearLayout
    private lateinit var selectedAppsContainer: LinearLayout
    private lateinit var rootView: LinearLayout
    private var appSelectorOverlay: View? = null
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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(30), dp(22), dp(22))
        }
        rootView = root
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "Big Head VPN"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            includeFontPadding = false
        })

        root.addView(TextView(this).apply {
            text = "Серверы, приложения и быстрый импорт в одном экране"
            textSize = 16f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(24))
        })

        val statusPanel = panel()
        root.addView(statusPanel)
        statusText = label("Отключено", 22f, COLOR_INK, bold = true)
        statusPanel.addView(statusText)
        profileText = label("", 15f, COLOR_MUTED)
        profileText.setPadding(0, dp(8), 0, 0)
        statusPanel.addView(profileText)
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
            iconButton("↻").apply { setOnClickListener { checkAllPings() } },
            iconButton("↑").apply { setOnClickListener { sortServersByPing() } },
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
                dp(330),
            ).apply { bottomMargin = dp(10) }
        }
        serversPanel.addView(serversScroll)
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
        appsPanel.addView(selectedAppsContainer)
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
        return scroll
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
        val activeId = store.activeProfile?.id
        val latencies = store.profileLatencies

        if (profiles.isEmpty()) {
            serversContainer.addView(emptyText("Список серверов пуст"))
            return
        }

        profiles.forEach { profile ->
            val active = profile.id == activeId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = rounded(if (active) COLOR_SIGNAL_SOFT else COLOR_SURFACE, dp(8), COLOR_LINE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    store.setActiveProfile(profile.id)
                    renderState()
                }
            }

            row.addView(TextView(this).apply {
                text = if (active) "●" else "○"
                textSize = 18f
                setTextColor(if (active) COLOR_INK else COLOR_MUTED)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))

            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(profile.name, 16f, COLOR_INK, bold = true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(label(profile.protocol.label, 13f, COLOR_MUTED))
                addView(label(if (checkingPing) "Проверяю..." else "Пинг: ${store.latencyLabel(profile.id)}", 12f, COLOR_MUTED))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(label(latencies[profile.id]?.let { "${it}ms" } ?: "—", 13f, COLOR_MUTED, bold = true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })

            row.addView(deleteIconButton().apply {
                setOnClickListener {
                    store.removeProfile(profile.id)
                    renderState()
                }
            })

            serversContainer.addView(row)
        }
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
        Toast.makeText(this, "Проверяю пинг", Toast.LENGTH_SHORT).show()

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
                Toast.makeText(this, "Пинг проверен", Toast.LENGTH_SHORT).show()
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

        if (selected.isEmpty()) return

        val appNames = InstalledAppRepository(this).launcherApps()
            .filter { it.packageName in selected }
            .take(4)

        appNames.forEach { app ->
            selectedAppsContainer.addView(compactAppRow(app.label, app.packageName))
        }
        if (selected.size > appNames.size) {
            selectedAppsContainer.addView(emptyText("И ещё: ${selected.size - appNames.size}"))
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

    private fun importSubscription(link: String) {
        Toast.makeText(this, "Загружаю подписку", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { fetchSubscription(link) }
            runOnUiThread {
                result.onSuccess { body ->
                    val imported = store.importFromText(body)
                    val added = store.addProfiles(imported)
                    val message = when {
                        imported.isEmpty() -> "В подписке нет VLESS или Hysteria профилей"
                        added == 0 -> "Серверы из подписки уже добавлены"
                        added == 1 -> "Добавлен 1 сервер из подписки"
                        else -> "Добавлено серверов из подписки: $added"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    renderState()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "Не удалось загрузить подписку", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.WHITE, dp(8), COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(16) }
        }
    }

    private fun actionRow(left: View, right: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(6) })
            addView(right, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(6) })
        }
    }

    private fun sectionHeader(title: String, vararg actions: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.forEachIndexed { index, action ->
                addView(action, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    if (index > 0) leftMargin = dp(8)
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

    private fun iconButton(textValue: String): Button {
        return button(textValue, COLOR_INK, COLOR_SURFACE).apply {
            textSize = 22f
            minWidth = dp(42)
            minHeight = dp(42)
            setPadding(0, 0, 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
    }

    private fun deleteIconButton(): Button {
        return button("×", Color.WHITE, COLOR_DANGER).apply {
            textSize = 24f
            minWidth = dp(42)
            minHeight = dp(42)
            setPadding(0, 0, 0, dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
    }

    private fun button(textValue: String, textColor: Int, fillColor: Int): Button {
        return Button(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            minHeight = dp(54)
            background = rounded(fillColor, dp(8), COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58),
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun sectionTitle(textValue: String): TextView {
        return label(textValue, 20f, COLOR_INK, bold = true)
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
