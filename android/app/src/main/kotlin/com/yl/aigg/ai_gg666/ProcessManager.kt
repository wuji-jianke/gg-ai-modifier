package com.yl.aigg.ai_gg666

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * 进程管理器
 * 默认只返回用户安装的第三方应用进程；启用 includeSystem 后再放开系统/预装应用。
 */
object ProcessManager {

    private data class AppMeta(
        val appInfo: ApplicationInfo?,
        val appName: String,
        val isSystem: Boolean,
        val isPreinstalled: Boolean,
        val hasLauncherEntry: Boolean
    ) {
        val isThirdPartyUserApp: Boolean
            get() = appInfo != null && !isSystem && !isPreinstalled && hasLauncherEntry
    }

    private var appInfoCache: Map<String, ApplicationInfo> = emptyMap()
    private val appMetaCache = mutableMapOf<String, AppMeta>()
    private val iconPathCache = mutableMapOf<String, String?>()
    private var launcherPackageCache: Set<String> = emptySet()

    fun getProcessList(
        context: android.content.Context,
        includeSystem: Boolean = false
    ): List<Map<String, Any>> {
        val processes = mutableListOf<Map<String, Any>>()

        try {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            appInfoCache = installedApps.associateBy { it.packageName }
            appMetaCache.clear()
            launcherPackageCache = queryLauncherPackages(pm)

            val psResult = RootManager.executeRootCommand("ps -A -o PID,NAME")
            if (psResult != null) {
                for (line in psResult.lines()) {
                    if (line.isBlank() || line.startsWith("PID")) continue
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size < 2) continue

                    val pid = parts[0].toIntOrNull() ?: continue
                    val processName = parts[1].trim()
                    if (processName.isEmpty() || !processName.contains(".")) continue

                    buildProcessEntry(
                        context = context,
                        pm = pm,
                        pid = pid,
                        rawProcessName = processName,
                        includeSystem = includeSystem
                    )?.let(processes::add)
                }
            }

            if (processes.isEmpty()) {
                return getProcessListFallback(context, includeSystem)
            }
        } catch (e: Exception) {
            return getProcessListFallback(context, includeSystem)
        }

        return sortProcesses(processes)
    }

    private fun getProcessListFallback(
        context: android.content.Context,
        includeSystem: Boolean = false
    ): List<Map<String, Any>> {
        val processes = mutableListOf<Map<String, Any>>()

        try {
            val pm = context.packageManager
            launcherPackageCache = queryLauncherPackages(pm)

            val procResult = RootManager.executeRootCommand(
                "for pid in /proc/[0-9]*; do " +
                    "p=\${pid##*/}; " +
                    "c=\$(cat /proc/\$p/cmdline 2>/dev/null | tr '\\0' ' ' | sed 's/ *$//'); " +
                    "[ -n \"\$c\" ] && echo \"\$p|\$c\"; " +
                    "done"
            )

            if (procResult != null) {
                for (line in procResult.lines()) {
                    if (line.isBlank() || !line.contains("|")) continue
                    val parts = line.split("|", limit = 2)
                    if (parts.size < 2) continue

                    val pid = parts[0].trim().toIntOrNull() ?: continue
                    val processName = parts[1].trim()
                    if (processName.isEmpty() || !processName.contains(".")) continue

                    buildProcessEntry(
                        context = context,
                        pm = pm,
                        pid = pid,
                        rawProcessName = processName,
                        includeSystem = includeSystem
                    )?.let(processes::add)
                }
            }
        } catch (_: Exception) {
        }

        return sortProcesses(processes)
    }

    private fun buildProcessEntry(
        context: android.content.Context,
        pm: PackageManager,
        pid: Int,
        rawProcessName: String,
        includeSystem: Boolean
    ): Map<String, Any>? {
        val appPackage = normalizePackageName(rawProcessName)
        val meta = getAppMeta(pm, appPackage)
        val isMainProcess = rawProcessName == appPackage

        if (!includeSystem && (meta == null || !meta.isThirdPartyUserApp)) {
            return null
        }
        if (!includeSystem && !isMainProcess) {
            return null
        }

        val displayName = meta?.appName?.takeIf { it.isNotBlank() } ?: appPackage
        val iconPath = getAppIconPath(context, pm, appPackage)
        val isSystemLike = meta?.isSystem == true || meta?.isPreinstalled == true

        return mapOf(
            "pid" to pid,
            "packageName" to rawProcessName,
            "processName" to displayName,
            "uid" to (meta?.appInfo?.uid ?: 0),
            "isSystem" to isSystemLike,
            "isPreinstalled" to (meta?.isPreinstalled ?: false),
            "isUserApp" to (meta?.isThirdPartyUserApp ?: false),
            "hasLauncherEntry" to (meta?.hasLauncherEntry ?: false),
            "isMainProcess" to isMainProcess,
            "iconPath" to (iconPath ?: "")
        )
    }

    private fun sortProcesses(processes: List<Map<String, Any>>): List<Map<String, Any>> {
        return processes
            .distinctBy { "${it["packageName"]}_${it["pid"]}" }
            .sortedWith(
                compareBy<Map<String, Any>>(
                    { !(it["isUserApp"] as? Boolean ?: false) },
                    { !(it["isMainProcess"] as? Boolean ?: false) },
                    {
                        val name = (it["processName"] as? String).orEmpty()
                        if (name.isNotEmpty() && name[0].code > 127) 0 else 1
                    },
                    { (it["processName"] as? String).orEmpty().lowercase() },
                    { (it["packageName"] as? String).orEmpty().lowercase() },
                    { (it["pid"] as? Int) ?: Int.MAX_VALUE }
                )
            )
    }

    private fun getAppName(pm: PackageManager, packageName: String): String {
        return try {
            val appInfo = appInfoCache[packageName]
            if (appInfo != null) {
                pm.getApplicationLabel(appInfo).toString()
            } else {
                packageName
            }
        } catch (e: Exception) {
            packageName
        }
    }

    fun getAppIconDrawable(context: android.content.Context, packageName: String): Drawable? {
        val pm = context.packageManager
        val appPackage = normalizePackageName(packageName)
        return try {
            val appInfo = appInfoCache[appPackage] ?: pm.getApplicationInfo(appPackage, 0)
            appInfo.loadIcon(pm)
        } catch (e: Exception) {
            null
        }
    }

    private fun getAppIconPath(
        context: android.content.Context,
        pm: PackageManager,
        packageName: String
    ): String? {
        iconPathCache[packageName]?.let { return it }

        val path = try {
            val appInfo = appInfoCache[packageName] ?: return null
            val icon = appInfo.loadIcon(pm)
            val iconDir = File(context.cacheDir, "process_icons").apply { mkdirs() }
            val iconFile = File(iconDir, "${packageName.replace(':', '_')}.png")
            if (!iconFile.exists() || iconFile.length() == 0L) {
                val bitmap = drawableToBitmap(icon)
                FileOutputStream(iconFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            iconFile.absolutePath
        } catch (e: Exception) {
            null
        }

        iconPathCache[packageName] = path
        return path
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun normalizePackageName(packageName: String): String {
        return packageName.substringBefore(":").trim()
    }

    private fun getAppMeta(pm: PackageManager, packageName: String): AppMeta? {
        appMetaCache[packageName]?.let { return it }

        val appInfo = appInfoCache[packageName] ?: return null
        val flags = appInfo.flags
        val sourceDir = appInfo.publicSourceDir ?: appInfo.sourceDir
        val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val isPreinstalled = isUpdatedSystem || isPreinstalledPath(sourceDir)
        val hasLauncherEntry = launcherPackageCache.contains(packageName)

        val meta = AppMeta(
            appInfo = appInfo,
            appName = getAppName(pm, packageName),
            isSystem = isSystem,
            isPreinstalled = isPreinstalled,
            hasLauncherEntry = hasLauncherEntry
        )
        appMetaCache[packageName] = meta
        return meta
    }

    private fun queryLauncherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return activities.mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    private fun isPreinstalledPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = path.lowercase()
        return normalized.startsWith("/system/") ||
            normalized.startsWith("/product/") ||
            normalized.startsWith("/vendor/") ||
            normalized.startsWith("/system_ext/") ||
            normalized.startsWith("/odm/") ||
            normalized.startsWith("/oem/") ||
            normalized.startsWith("/apex/") ||
            normalized.startsWith("/mi_ext/") ||
            normalized.startsWith("/hw_product/") ||
            normalized.startsWith("/cust/")
    }
}
