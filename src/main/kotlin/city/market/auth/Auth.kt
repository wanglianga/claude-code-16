package city.market.auth

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 口令散列（JDK 自带，无额外依赖）。格式：iterations:saltHex:hashHex */
object Passwords {
    private const val ITERATIONS = 60_000
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return "$ITERATIONS:${salt.toHex()}:${hash.toHex()}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 3) return false
        val salt = parts[1].fromHex()
        val spec = PBEKeySpec(password.toCharArray(), salt, parts[0].toInt(), 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return hash.toHex() == parts[2]
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** 登录会话（Ktor 默认反射序列化，属性需为基本类型） */
data class UserSession(
    val userId: Int,
    val username: String,
    val role: String,
    val displayName: String
)

object Roles {
    const val MARKET_ADMIN = "MARKET_ADMIN"
    const val REGULATOR = "REGULATOR"
    const val VENDOR = "VENDOR"
    const val CONSUMER = "CONSUMER"

    fun label(role: String) = when (role) {
        MARKET_ADMIN -> "市场管理方"
        REGULATOR -> "监管人员"
        VENDOR -> "摊主"
        CONSUMER -> "消费者"
        else -> role
    }
}
