package app.bighead.vpn.core

import android.content.Context
import android.content.Intent

data class InstalledApp(
    val label: String,
    val packageName: String,
)

class InstalledAppRepository(private val context: Context) {
    fun launcherApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                InstalledApp(
                    label = info.loadLabel(context.packageManager).toString(),
                    packageName = activityInfo.packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
