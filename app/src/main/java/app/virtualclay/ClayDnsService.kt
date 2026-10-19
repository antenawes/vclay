package app.virtualclay

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors

/**
 * DNS-туннель только для приложений-клонов (addAllowedApplication).
 * Маршрут один: фейковый DNS 10.77.0.1/32 — остальной трафик и IP НЕ меняются,
 * а на устройстве (вне профиля) DNS остаётся как был.
 */
class ClayDnsService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val pool = Executors.newFixedThreadPool(6)
    private val fakeDns = byteArrayOf(10, 77, 0, 1)

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        stopTunnel()
        if (i?.getBooleanExtra("stop", false) == true) { stopSelf(); return START_NOT_STICKY }
        val sp = getSharedPreferences("vc", MODE_PRIVATE)
        val pkgs = sp.getStringSet("dns_pkgs", emptySet()) ?: emptySet()
        val b = Builder().setSession("Virtual Clay DNS").addAddress("10.77.0.2", 32)
            .addDnsServer("10.77.0.1").addRoute("10.77.0.1", 32).setBlocking(true).setMtu(1400)
        var ok = 0
        for (p in pkgs) try { b.addAllowedApplication(p); ok++ } catch (_: Exception) {}
        if (ok == 0) { stopSelf(); return START_NOT_STICKY }
        tun = b.establish() ?: run { stopSelf(); return START_NOT_STICKY }
        running = true
        val fd = tun!!
        Thread { loop(fd, sp.getString("upstream", "udp:1.1.1.1") ?: "udp:1.1.1.1") }.start()
        return START_STICKY
    }

    private fun loop(fd: ParcelFileDescriptor, upstream: String) {
        val inp = FileInputStream(fd.fileDescriptor); val out = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = inp.read(buf); if (n <= 0) continue
                if ((buf[0].toInt() shr 4) != 4) continue
                val ihl = (buf[0].toInt() and 15) * 4
                if (n < ihl + 8 || buf[9].toInt() != 17) continue
                if (!(16..19).all { buf[it] == fakeDns[it - 16] }) continue
                if (u16(buf, ihl + 2) != 53) continue
                val pkt = buf.copyOf(n)
                pool.execute {
                    val q = pkt.copyOfRange(ihl + 8, n)
                    val ans = try { resolve(upstream, q) } catch (_: Exception) { null } ?: return@execute
                    val resp = build(pkt, ihl, ans)
                    synchronized(out) { try { out.write(resp) } catch (_: Exception) {} }
                }
            }
        } catch (_: Exception) {}
    }

    private fun resolve(up: String, q: ByteArray): ByteArray? {
        if (up.startsWith("doh:")) {
            val c = URL(up.substring(4)).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 6000
            c.setRequestProperty("Content-Type", "application/dns-message"); c.setRequestProperty("Accept", "application/dns-message")
            c.outputStream.use { it.write(q) }
            return c.inputStream.use { it.readBytes() }
        }
        val s = DatagramSocket(); protect(s)
        return try {
            s.soTimeout = 4000
            s.connect(InetAddress.getByName(up.removePrefix("udp:")), 53)
            s.send(DatagramPacket(q, q.size))
            val r = ByteArray(4096); val p = DatagramPacket(r, r.size); s.receive(p); r.copyOf(p.length)
        } finally { s.close() }
    }

    private fun build(req: ByteArray, ihl: Int, ans: ByteArray): ByteArray {
        val len = 20 + 8 + ans.size; val o = ByteArray(len)
        o[0] = 0x45; o[2] = (len shr 8).toByte(); o[3] = len.toByte(); o[6] = 0x40; o[8] = 64; o[9] = 17
        System.arraycopy(fakeDns, 0, o, 12, 4); System.arraycopy(req, 12, o, 16, 4)
        System.arraycopy(req, ihl + 2, o, 20, 2); System.arraycopy(req, ihl, o, 22, 2)
        val ul = 8 + ans.size; o[24] = (ul shr 8).toByte(); o[25] = ul.toByte()
        System.arraycopy(ans, 0, o, 28, ans.size)
        var s = 0; for (i in 0 until 20 step 2) s += u16(o, i)
        while (s shr 16 != 0) s = (s and 0xffff) + (s shr 16)
        val c = s.inv() and 0xffff; o[10] = (c shr 8).toByte(); o[11] = c.toByte()
        return o
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun stopTunnel() { running = false; try { tun?.close() } catch (_: Exception) {}; tun = null }
    override fun onDestroy() { stopTunnel(); pool.shutdownNow(); super.onDestroy() }
    override fun onRevoke() { stopTunnel(); super.onRevoke() }
}
