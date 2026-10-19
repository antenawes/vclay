package app.virtualclay

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle

const val ACTION_CMD = "app.virtualclay.CMD"

/** Администратор рабочего профиля: именно профиль и есть «клон-пространство» (отдельные данные, отдельный VPN/DNS). */
class ClayAdmin : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(ctx: Context, intent: Intent) {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(ctx, ClayAdmin::class.java)
        dpm.setProfileName(admin, "Virtual Clay")
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, ProxyActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        val f = IntentFilter(ACTION_CMD).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addCrossProfileIntentFilter(admin, f, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        dpm.setProfileEnabled(admin)
    }
}

class ProvisionModeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setResult(RESULT_OK, Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE))
        finish()
    }
}

class ComplianceActivity : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setResult(RESULT_OK); finish() }
}

/**
 * Работает ВНУТРИ профиля. Основное приложение шлёт сюда команды:
 * add / launch / wipe / remove / dns.
 */
class ProxyActivity : Activity() {
    private val reqVpn = 51

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isProfileOwnerApp(packageName)) { finish(); return }
        val admin = ComponentName(this, ClayAdmin::class.java)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val sp = getSharedPreferences("vc", MODE_PRIVATE)
        when (intent.getStringExtra("cmd")) {
            "add" -> { dpm.installExistingPackage(admin, pkg); dpm.setApplicationHidden(admin, pkg, false) }
            "launch" -> packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
            }
            "wipe" -> dpm.clearApplicationUserData(admin, pkg, mainExecutor) { _, _ -> }
            "remove" -> {
                dpm.setApplicationHidden(admin, pkg, true)
                sp.edit().putStringSet("dns_pkgs", (sp.getStringSet("dns_pkgs", emptySet()) ?: emptySet()) - pkg).apply()
                applyVpn(); return
            }
            "dns" -> {
                val set = (sp.getStringSet("dns_pkgs", emptySet()) ?: emptySet()).toMutableSet()
                if (intent.getBooleanExtra("on", false)) set.add(pkg) else set.remove(pkg)
                sp.edit().putStringSet("dns_pkgs", set).putString("upstream", intent.getStringExtra("upstream") ?: "udp:1.1.1.1").apply()
                applyVpn(); return
            }
            "upstream" -> {
                sp.edit().putString("upstream", intent.getStringExtra("upstream") ?: "udp:1.1.1.1").apply()
                applyVpn(); return
            }
        }
        finish()
    }

    private fun applyVpn() {
        val sp = getSharedPreferences("vc", MODE_PRIVATE)
        if ((sp.getStringSet("dns_pkgs", emptySet()) ?: emptySet()).isEmpty()) {
            startService(Intent(this, ClayDnsService::class.java).putExtra("stop", true)); finish(); return
        }
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, reqVpn) else { startService(Intent(this, ClayDnsService::class.java)); finish() }
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        if (rc == reqVpn && res == RESULT_OK) startService(Intent(this, ClayDnsService::class.java))
        finish()
    }
}
