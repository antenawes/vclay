package app.virtualclay

import java.security.MessageDigest

/** Пароль в коде не хранится: только соль и SHA-256 (100 000 итераций). */
object Secret {
    private const val SALT = "1d002e3e94ebbe110bb2d337653b7cef"
    private const val HASH = "00284a9762142ba7c97d603833dd1f1f6855cc8f83c99f5b5bba00b088d0736f"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun derive(pw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var h = md.digest((SALT + pw).toByteArray())
        repeat(100_000) {
            md.reset(); md.update(h); md.update(SALT.toByteArray()); md.update(pw.toByteArray())
            h = md.digest()
        }
        return hex(h)
    }

    fun check(pw: String) = MessageDigest.isEqual(derive(pw).toByteArray(), HASH.toByteArray())

    /** Токен «уже входил»: привязан к хешу и времени установки, пароль не нужен. */
    fun token(installTime: Long): String =
        hex(MessageDigest.getInstance("SHA-256").digest((HASH + "|vc|" + installTime).toByteArray()))
}
