package com.hanfengruyue.pocketrdp.keepalive

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * OEM-specific background keep-alive helpers.
 *
 * On stock Android a foreground service is enough to survive backgrounding, but Chinese OEM ROMs
 * (Xiaomi/MIUI, Huawei/HarmonyOS, OPPO/OnePlus/ColorOS, vivo, Samsung, Meizu …) kill background
 * apps — and frequently foreground services too — regardless of AOSP rules. The ONLY reliable fix
 * is the user manually allow-listing the app (autostart / 自启动 + battery "no restriction" + lock in
 * recents). No API can set those for us, so we detect the manufacturer, deep-link to the right
 * settings screen where possible (always with a fallback), and show step-by-step guidance.
 */
object OemKeepAlive {

    enum class Oem { XIAOMI, HUAWEI, HONOR, OPPO, ONEPLUS, VIVO, SAMSUNG, MEIZU, OTHER }

    fun detect(): Oem = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> Oem.XIAOMI
        "huawei" -> Oem.HUAWEI
        "honor" -> Oem.HONOR
        "oppo", "realme" -> Oem.OPPO
        "oneplus" -> Oem.ONEPLUS
        "vivo" -> Oem.VIVO
        "samsung" -> Oem.SAMSUNG
        "meizu" -> Oem.MEIZU
        else -> Oem.OTHER
    }

    fun displayName(oem: Oem): String = when (oem) {
        Oem.XIAOMI -> "小米 / Redmi（MIUI / HyperOS）"
        Oem.HUAWEI -> "华为（EMUI / HarmonyOS）"
        Oem.HONOR -> "荣耀（MagicOS）"
        Oem.OPPO -> "OPPO / realme（ColorOS）"
        Oem.ONEPLUS -> "一加（ColorOS / OxygenOS）"
        Oem.VIVO -> "vivo（OriginOS / Funtouch）"
        Oem.SAMSUNG -> "三星（One UI）"
        Oem.MEIZU -> "魅族（Flyme）"
        Oem.OTHER -> "你的设备"
    }

    /** Per-OEM manual steps. These vary by ROM version — treat them as guidance, not exact paths. */
    fun steps(oem: Oem): List<String> = when (oem) {
        Oem.XIAOMI -> listOf(
            "设置 → 应用设置 → 应用管理 → PocketRDP → 自启动：开启",
            "设置 → 省电与电池 → 应用智能省电 → PocketRDP → 选择「无限制」",
            "在「最近任务」里下拉 PocketRDP 卡片并点锁图标，锁定后台",
        )
        Oem.HUAWEI, Oem.HONOR -> listOf(
            "设置 → 电池 → 应用启动管理 → PocketRDP → 关闭「自动管理」",
            "在弹出的手动管理里把「自启动 / 关联启动 / 后台活动」全部打开",
            "在「最近任务」里下拉 PocketRDP 卡片并点锁图标，锁定后台",
        )
        Oem.OPPO, Oem.ONEPLUS -> listOf(
            "设置 → 应用管理 → PocketRDP → 耗电管理 → 允许「后台运行 / 自启动」",
            "设置 → 电池 → 更多设置 → 允许 PocketRDP 后台高耗电",
            "在「最近任务」里下拉 PocketRDP 卡片并锁定后台",
        )
        Oem.VIVO -> listOf(
            "i 管家 → 应用管理 → 自启动管理 → 允许 PocketRDP 自启动",
            "设置 → 电池 → 后台高耗电 → 允许 PocketRDP",
            "在「最近任务」里下拉 PocketRDP 卡片并锁定后台",
        )
        Oem.SAMSUNG -> listOf(
            "设置 → 应用 → PocketRDP → 电池 → 选择「不受限制」",
            "设置 → 电池 → 后台使用限制 → 从「休眠 / 深度休眠应用」中移除 PocketRDP",
            "关闭「自动关闭未使用的应用」/ 自适应电池",
        )
        Oem.MEIZU -> listOf(
            "手机管家 → 权限管理 → 后台管理 → 允许 PocketRDP 后台运行",
            "设置 → 电量管理 → 待机耗电管理 → 允许 PocketRDP",
            "在「最近任务」里锁定 PocketRDP",
        )
        Oem.OTHER -> listOf(
            "设置 → 应用 → PocketRDP → 电池 → 允许后台活动 / 不受限制",
            "如果系统有「自启动」或「后台运行」开关，请为 PocketRDP 打开",
            "在「最近任务」里锁定 PocketRDP（如支持）以免被一键清理",
        )
    }

    private fun cn(pkg: String, cls: String) = ComponentName(pkg, cls)

    /** Candidate autostart/background-manager activities per OEM, tried in order. */
    private fun autostartTargets(oem: Oem): List<ComponentName> = when (oem) {
        Oem.XIAOMI -> listOf(
            cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Oem.HUAWEI, Oem.HONOR -> listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Oem.OPPO, Oem.ONEPLUS -> listOf(
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        Oem.VIVO -> listOf(
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Oem.SAMSUNG -> listOf(
            cn("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Oem.MEIZU -> listOf(
            cn("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        )
        Oem.OTHER -> emptyList()
    }

    /**
     * Try to open the OEM autostart / background-manager screen. Each ComponentName can throw
     * (SecurityException / ActivityNotFound) on a different ROM version, so we try them in turn and
     * fall back to the app's own details settings (which always exists).
     */
    fun openAutostartSettings(context: Context): Boolean {
        for (cn in autostartTargets(detect())) {
            val ok = runCatching {
                context.startActivity(
                    Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (ok) return true
        }
        return openAppDetailsSettings(context)
    }

    /** Open this app's system "App info" page — always available, useful as a universal fallback. */
    fun openAppDetailsSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The one-tap system dialog to add this app to the battery-optimization allow-list. */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val ok = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * Whether the most recent previous process exit looks like an OS/OEM kill (low memory, SIGKILL,
     * "other") rather than a normal user action or an app crash. Used together with
     * [SessionKeepAliveFlag][com.hanfengruyue.pocketrdp.core.rdp.SessionKeepAliveFlag] to decide
     * whether to surface the keep-alive guide. Best-effort; returns false on API < 30 or on error.
     */
    fun lastExitWasKill(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1) }
            .getOrNull()?.firstOrNull() ?: return false
        return info.reason in KILL_REASONS
    }

    private val KILL_REASONS = setOf(
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )
}
