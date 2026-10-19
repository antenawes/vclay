package app.virtualclay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*

private const val TG = "https://t.me/ClayAway"
private val DNS_PRESETS = listOf(
    "Cloudflare  1.1.1.1" to "udp:1.1.1.1",
    "Google  8.8.8.8" to "udp:8.8.8.8",
    "Quad9  9.9.9.9" to "udp:9.9.9.9",
    "AdGuard (без рекламы)" to "udp:94.140.14.14",
    "Cloudflare DoH (шифрованный)" to "doh:https://cloudflare-dns.com/dns-query",
    "Google DoH (шифрованный)" to "doh:https://dns.google/dns-query",
    "AdGuard DoH (шифрованный)" to "doh:https://dns.adguard-dns.com/dns-query",
)

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private val sp by lazy { getSharedPreferences("main", MODE_PRIVATE) }
    private var list: LinearLayout? = null
    private var status: TextView? = null
    private var fails = 0
    private var lockedUntil = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        setContentView(root)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isProfileOwnerApp(packageName)) showProfileSide() else showSplash()
    }

    // ---------- helpers ----------
    private fun show(v: View) {
        root.removeAllViews(); root.addView(v, ViewGroup.LayoutParams(-1, -1))
        v.alpha = 0f; v.animate().alpha(1f).setDuration(350).start()
    }
    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun openTg() = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TG))) } catch (_: Exception) { toast("t.me/ClayAway") }
    private fun brand(size: Float) = TextView(this).apply {
        val s = SpannableString("VIRTUAL CLAY")
        s.setSpan(ForegroundColorSpan(C.RED), 8, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = s; textSize = size; setTextColor(C.WHITE); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.2f
    }
    private fun installTime() = packageManager.getPackageInfo(packageName, 0).firstInstallTime
    private fun unlocked() = sp.getString("tok", "") == Secret.token(installTime())
    private fun envReady() = getSystemService(LauncherApps::class.java).profiles.size > 1
    private fun clones(): MutableSet<String> = (sp.getStringSet("clones", emptySet()) ?: emptySet()).toMutableSet()
    private fun dnsOn(): MutableSet<String> = (sp.getStringSet("dns_on", emptySet()) ?: emptySet()).toMutableSet()
    private fun upstream() = sp.getString("upstream", "udp:1.1.1.1") ?: "udp:1.1.1.1"

    // ---------- сплэш ----------
    private fun showSplash() {
        val col = vbox(this).apply { gravity = Gravity.CENTER }
        val mask = MaskView(this)
        col.addView(mask, lp(dp(230), dp(270)))
        val title = brand(26f).apply { alpha = 0f; translationY = dp(24).toFloat() }
        col.addView(title, lp(-2, -2, 6))
        val sub = label("клонируй · изолируй · управляй", 13f, C.GRAY, center = true).apply { alpha = 0f }
        col.addView(sub, lp(-2, -2, 8))
        val bar = BarView(this)
        col.addView(bar, lp(dp(190), dp(4), 34))
        show(col)
        mask.pop()
        title.animate().alpha(1f).translationY(0f).setStartDelay(500).setDuration(650).start()
        sub.animate().alpha(1f).setStartDelay(950).setDuration(600).start()
        ValueAnimator.ofFloat(0f, 1f).setDuration(2100).apply { addUpdateListener { bar.progress = it.animatedValue as Float } }.start()
        h.postDelayed({ if (unlocked()) showHome() else showLock() }, 2400)
    }

    // ---------- экран кода ----------
    private fun showLock() {
        val col = vbox(this).apply { gravity = Gravity.CENTER; setPadding(dp(34), dp(24), dp(34), dp(24)) }
        val mask = MaskView(this)
        col.addView(mask, lp(dp(170), dp(200)))
        col.addView(label("Добро пожаловать", 24f, C.WHITE, true, true), lp(-2, -2, 8))
        col.addView(label("Введите код доступа", 14f, C.GRAY, center = true), lp(-2, -2, 6))
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 22f; gravity = Gravity.CENTER; setTextColor(C.WHITE); letterSpacing = 0.3f
            hint = "• • •"; setHintTextColor(0xFF555555.toInt()); maxLines = 1; isSingleLine = true
            background = rounded(C.CARD, dp(14).toFloat(), C.RED_D, dp(1)); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(et, lp(-1, -2, 26))
        val err = label("", 13f, C.RED, center = true)
        col.addView(err, lp(-2, -2, 8))
        val attempt = {
            val pw = et.text.toString().trim()
            val now = System.currentTimeMillis()
            if (now < lockedUntil) err.text = "Подождите ${(lockedUntil - now) / 1000 + 1} с"
            else if (pw.isNotEmpty()) Thread {
                val ok = Secret.check(pw)
                runOnUiThread {
                    if (ok) {
                        sp.edit().putString("tok", Secret.token(installTime())).apply()
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(et.windowToken, 0)
                        err.text = ""; mask.pop(); h.postDelayed({ showHome() }, 650)
                    } else {
                        if (++fails >= 5) { lockedUntil = System.currentTimeMillis() + 15000; fails = 0 }
                        err.text = "Неверный код"; mask.shake()
                        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }.start()
            Unit
        }
        col.addView(redButton("ВОЙТИ") { attempt() }, lp(-1, -2, 8))
        et.setOnEditorActionListener { _, _, _ -> attempt(); true }
        val tg = TextView(this).apply {
            val s = SpannableString("Нет пароля? Заходи в ТГ  →  t.me/ClayAway")
            s.setSpan(ForegroundColorSpan(C.RED), 25, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = s; textSize = 14f; setTextColor(C.GRAY); gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12)); setOnClickListener { openTg() }
        }
        col.addView(tg, lp(-2, -2, 22))
        show(col)
        mask.pop()
    }

    // ---------- главный экран ----------
    private fun showHome() {
        val frame = FrameLayout(this)
        val col = vbox(this)
        val top = hbox(this).apply { setPadding(dp(16), dp(14), dp(14), dp(10)) }
        top.addView(MaskView(this).apply { glowOn = false }, LinearLayout.LayoutParams(dp(48), dp(54)))
        val names = vbox(this).apply { setPadding(dp(12), 0, 0, 0) }
        names.addView(brand(19f))
        status = label("", 12f, C.GRAY); names.addView(status)
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ghostButton("TG") { openTg() }, lp(-2, -2).apply { rightMargin = dp(8) })
        top.addView(ghostButton("⚙") { settings() })
        col.addView(top)
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        list = vbox(this).apply { setPadding(dp(16), dp(6), dp(16), dp(110)) }
        sc.addView(list); col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        val fab = TextView(this).apply {
            text = "+"; textSize = 34f; setTextColor(C.WHITE); gravity = Gravity.CENTER; elevation = dp(10).toFloat()
            background = ripple(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.RED, C.RED_D)).apply { shape = GradientDrawable.OVAL })
            setOnClickListener { onPlus() }
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(22), dp(26)) })
        ObjectAnimator.ofPropertyValuesHolder(fab, PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f))
            .apply { duration = 900; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE }.start()
        show(frame)
        render()
    }

    override fun onResume() { super.onResume(); if (list != null) render() }

    private fun render() {
        val l = list ?: return
        l.removeAllViews()
        val cl = clones().toList().sorted()
        status?.text = if (envReady()) "Среда активна · клонов: ${cl.size}" else "Среда не создана"
        if (!envReady()) {
            val card = vbox(this).apply {
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3A0309.toInt(), C.CARD)).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), C.RED_D) }
            }
            card.addView(label("Создайте изолированную среду", 18f, C.WHITE, true))
            card.addView(label("Один раз Android создаст отдельный профиль. Клоны живут в нём: свои данные, свои аккаунты и свой DNS, не затрагивая основное устройство.", 13f, C.GRAY), lp(-1, -2, 8))
            card.addView(redButton("СОЗДАТЬ СРЕДУ") { createEnv() }, lp(-1, -2, 16))
            l.addView(card)
        } else if (cl.isEmpty()) {
            val e = vbox(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) }
            e.addView(MaskView(this), lp(dp(190), dp(220)))
            e.addView(label("Пока нет клонов", 20f, C.WHITE, true, true), lp(-2, -2, 10))
            e.addView(label("Нажмите + и выберите приложение,\nкоторое хотите клонировать", 14f, C.GRAY, center = true), lp(-2, -2, 6))
            l.addView(e, lp(-1, -2))
        } else cl.forEach { l.addView(cloneCard(it), lp(-1, -2, 12)) }
        l.addView(label("t.me/ClayAway", 12f, C.RED_D, center = true).apply { setPadding(0, dp(24), 0, 0); setOnClickListener { openTg() } }, lp(-1, -2))
    }

    private fun cloneCard(pkg: String): View {
        val card = vbox(this).apply { setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(C.CARD, dp(18).toFloat(), 0x22FFFFFF, 1) }
        val row = hbox(this)
        val icon = ImageView(this)
        var name = pkg
        try { val ai = packageManager.getApplicationInfo(pkg, 0); name = packageManager.getApplicationLabel(ai).toString(); icon.setImageDrawable(packageManager.getApplicationIcon(ai)) } catch (_: Exception) {}
        row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
        val t = vbox(this).apply { setPadding(dp(12), 0, 0, 0) }
        t.addView(label(name, 16f, C.WHITE, true)); t.addView(label(pkg, 11f, C.GRAY))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        val act = hbox(this)
        act.addView(redButton("▶  Запустить") { cmd("launch", pkg) }.apply { textSize = 13f; setPadding(dp(14), dp(10), dp(14), dp(10)) })
        act.addView(label("DNS", 13f, C.GRAY).apply { setPadding(dp(14), 0, dp(4), 0) })
        act.addView(Switch(this).apply {
            isChecked = pkg in dnsOn()
            setOnCheckedChangeListener { _, on ->
                sp.edit().putStringSet("dns_on", dnsOn().apply { if (on) add(pkg) else remove(pkg) }).apply()
                cmd("dns", pkg) { putExtra("on", on); putExtra("upstream", upstream()) }
            }
        })
        act.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        act.addView(ghostButton("Сброс") { confirm("Сбросить данные «$name» в клоне?\nАккаунты и локальные ID клона будут стёрты.") { cmd("wipe", pkg) } }, lp(-2, -2).apply { rightMargin = dp(6) })
        act.addView(ghostButton("✕") {
            confirm("Удалить клон «$name»?") {
                sp.edit().putStringSet("clones", clones().apply { remove(pkg) }).putStringSet("dns_on", dnsOn().apply { remove(pkg) }).apply()
                cmd("remove", pkg); render()
            }
        })
        card.addView(act, lp(-1, -2, 14))
        return card
    }

    private fun confirm(msg: String, ok: () -> Unit) = AlertDialog.Builder(this, R.style.Dlg)
        .setMessage(msg).setPositiveButton("Да") { _, _ -> ok() }.setNegativeButton("Отмена", null).show()

    // ---------- команды в профиль ----------
    private fun cmd(c: String, pkg: String = "", ex: Intent.() -> Unit = {}) {
        val i = Intent(ACTION_CMD).addCategory(Intent.CATEGORY_DEFAULT).putExtra("cmd", c).putExtra("pkg", pkg).apply(ex)
        try { startActivity(i) } catch (_: Exception) { toast("Среда ещё не готова. Создайте её заново.") }
    }

    private fun createEnv() {
        val i = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, ComponentName(this, ClayAdmin::class.java))
        if (i.resolveActivity(packageManager) != null) startActivityForResult(i, 77)
        else toast("Это устройство не поддерживает рабочие профили")
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) { if (rc == 77) h.postDelayed({ render() }, 1500) }

    private fun onPlus() {
        if (!envReady()) { toast("Сначала создайте среду"); createEnv(); return }
        pickApp()
    }

    // ---------- выбор приложения ----------
    private data class AppRow(val name: String, val pkg: String, val icon: android.graphics.drawable.Drawable)

    private fun pickApp() {
        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dlg.window?.setBackgroundDrawable(ColorDrawable(C.BG))
        val col = vbox(this).apply { setPadding(dp(16), dp(18), dp(16), dp(8)) }
        val head = hbox(this)
        head.addView(MaskView(this).apply { glowOn = false }, LinearLayout.LayoutParams(dp(36), dp(42)))
        head.addView(label("Что клонируем?", 20f, C.WHITE, true).apply { setPadding(dp(10), 0, 0, 0) })
        col.addView(head)
        val search = EditText(this).apply {
            hint = "Поиск приложения"; setHintTextColor(0xFF666666.toInt()); setTextColor(C.WHITE); textSize = 15f; isSingleLine = true
            background = rounded(C.CARD, dp(14).toFloat()); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(search, lp(-1, -2, 12))
        val box = vbox(this)
        col.addView(ScrollView(this).apply { addView(box) }, lp(-1, 0, 8).apply { weight = 1f })
        val loading = vbox(this).apply { gravity = Gravity.CENTER }
        loading.addView(MaskView(this).apply { spin = true }, lp(dp(90), dp(110)))
        box.addView(loading, lp(-1, dp(300)))
        dlg.setContentView(col); dlg.show()

        var all = listOf<AppRow>()
        fun fill(q: String) {
            box.removeAllViews()
            all.filter { it.name.contains(q, true) || it.pkg.contains(q, true) }.forEach { a ->
                val r = hbox(this).apply { setPadding(dp(8), dp(10), dp(8), dp(10)); background = ripple(ColorDrawable(0)) }
                r.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                val t = vbox(this).apply { setPadding(dp(12), 0, 0, 0) }
                t.addView(label(a.name, 15f, C.WHITE)); t.addView(label(a.pkg, 11f, C.GRAY))
                r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(label("+", 22f, C.RED, true))
                r.setOnClickListener { dlg.dismiss(); startClone(a) }
                box.addView(r)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = fill(s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        Thread {
            val pm = packageManager
            val have = clones()
            val rows = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it }.distinctBy { it.first }
                .filter { it.first != packageName && it.first !in have }
                .map { AppRow(it.second.loadLabel(pm).toString(), it.first, it.second.loadIcon(pm)) }
                .sortedBy { it.name.lowercase() }
            runOnUiThread { all = rows; fill("") }
        }.start()
    }

    private fun startClone(a: AppRow) {
        val d = Dialog(this); d.setCancelable(false)
        d.window?.setBackgroundDrawable(ColorDrawable(0xE6000000.toInt()))
        val v = vbox(this).apply { gravity = Gravity.CENTER; setPadding(dp(40), dp(30), dp(40), dp(30)) }
        v.addView(MaskView(this).apply { spin = true }, lp(dp(120), dp(140)))
        v.addView(label("Клонирую ${a.name}…", 16f, C.WHITE, true, true), lp(-2, -2, 10))
        d.setContentView(v); d.show()
        sp.edit().putStringSet("clones", clones().apply { add(a.pkg) }).apply()
        cmd("add", a.pkg)
        h.postDelayed({ d.dismiss(); render(); toast("Клон «${a.name}» готов") }, 2600)
    }

    // ---------- настройки ----------
    private fun settings() {
        val cur = DNS_PRESETS.firstOrNull { it.second == upstream() }?.first ?: upstream()
        AlertDialog.Builder(this, R.style.Dlg).setTitle("Настройки")
            .setItems(arrayOf("DNS клонов:  $cur", "Рекламный ID устройства", "Telegram  t.me/ClayAway", "О приложении")) { _, w ->
                when (w) {
                    0 -> pickDns()
                    1 -> try { startActivity(Intent("com.google.android.gms.settings.ADS_PRIVACY")) } catch (_: Exception) { toast("Настройки → Google → Реклама") }
                    2 -> openTg()
                    3 -> AlertDialog.Builder(this, R.style.Dlg).setTitle("Virtual Clay 1.0").setMessage(
                        "Клоны работают в изолированном профиле Android. DNS-туннель включается только для выбранных клонов: IP и остальной трафик не меняются.\n\nКанал: t.me/ClayAway").setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun pickDns() {
        val items = (DNS_PRESETS.map { it.first } + "Свой сервер…").toTypedArray()
        AlertDialog.Builder(this, R.style.Dlg).setTitle("DNS для клонов").setItems(items) { _, w ->
            if (w < DNS_PRESETS.size) setUpstream(DNS_PRESETS[w].second)
            else {
                val et = EditText(this).apply { hint = "IP (9.9.9.9) или https://…/dns-query"; setTextColor(C.WHITE); setHintTextColor(C.GRAY); isSingleLine = true }
                AlertDialog.Builder(this, R.style.Dlg).setTitle("Свой DNS").setView(et).setPositiveButton("OK") { _, _ ->
                    val s = et.text.toString().trim()
                    if (s.startsWith("https://")) setUpstream("doh:$s") else if (s.isNotEmpty()) setUpstream("udp:$s")
                }.setNegativeButton("Отмена", null).show()
            }
        }.show()
    }

    private fun setUpstream(u: String) {
        sp.edit().putString("upstream", u).apply()
        if (dnsOn().isNotEmpty()) cmd("upstream") { putExtra("upstream", u) }
        toast("DNS обновлён")
    }

    // ---------- копия внутри профиля ----------
    private fun showProfileSide() {
        val col = vbox(this).apply { gravity = Gravity.CENTER; setPadding(dp(36), dp(24), dp(36), dp(24)) }
        col.addView(MaskView(this), lp(dp(200), dp(240)))
        col.addView(brand(22f), lp(-2, -2, 8))
        col.addView(label("Это пространство клонов.\nУправляйте им из основного приложения Virtual Clay.", 14f, C.GRAY, center = true), lp(-2, -2, 12))
        show(col)
    }
}
