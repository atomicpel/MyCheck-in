package ru.mycheckin.passport.vault

import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Параметры получения ключа из мастер-пароля (хранятся на сервере, не секретны). */
data class Kdf(val alg: String, val iter: Int, val salt: String)

/**
 * Сквозное шифрование «Учёт SIM» — то же, что в веб-версии (server/web/crypto.js) и docs/SECURITY.md:
 * PBKDF2-HMAC-SHA256 → HKDF-SHA256 (authKey, kek) → AES-256-GCM. Только стандартные средства JVM/Android.
 */
object VaultCrypto {
    const val KDF_ITER = 600_000
    private val rnd = SecureRandom()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun b64(b: ByteArray): String = b64e.encodeToString(b)
    fun unb64(s: String): ByteArray = b64d.decode(s)
    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun newId(): String = UUID.randomUUID().toString()

    class Keys(val authKey: ByteArray, val kek: ByteArray) {
        fun wipe() { authKey.fill(0); kek.fill(0) }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256")) }.doFinal(data)

    /** HKDF-SHA256 (RFC 5869) с пустой солью, 32 байта. */
    fun hkdf(ikm: ByteArray, info: String): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        return hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1)).also { prk.fill(0) }
    }

    fun deriveKeys(password: CharArray, kdf: Kdf): Keys {
        require(kdf.alg == "PBKDF2-SHA256" && kdf.iter >= KDF_ITER) { "Неподдерживаемые параметры ключа" }
        val pw = Normalizer.normalize(String(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val master = pbkdf2Sha256(pw, unb64(kdf.salt), kdf.iter)
        pw.fill(0)
        return Keys(hkdf(master, "simvault-auth-v1"), hkdf(master, "simvault-kek-v1")).also { master.fill(0) }
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018), один блок = 32 байта. Реализован явно: системный PBKDF2 на разных
     * версиях Android по-разному переводит не-латинские символы пароля в байты, а здесь всегда UTF-8 — как в браузере.
     */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iter: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        for (i in 2..iter) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out
    }

    fun seal(key: ByteArray, plain: ByteArray, aad: String): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + c.doFinal(plain)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: String): ByteArray {
        require(sealed.size >= 12 + 16) { "Повреждённые данные" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    data class NewVault(val kdf: Kdf, val authKey: String, val wrappedKey: String, val vaultKey: ByteArray)

    fun createVault(password: CharArray): NewVault {
        val kdf = Kdf("PBKDF2-SHA256", KDF_ITER, b64(randomBytes(16)))
        val keys = deriveKeys(password, kdf)
        val vaultKey = randomBytes(32)
        val wrapped = b64(seal(keys.kek, vaultKey, "simvault-vaultkey-v1"))
        return NewVault(kdf, b64(keys.authKey), wrapped, vaultKey).also { keys.wipe() }
    }

    fun unwrapVaultKey(kek: ByteArray, wrappedKey: String): ByteArray = open(kek, unb64(wrappedKey), "simvault-vaultkey-v1")

    fun encryptRecord(vaultKey: ByteArray, id: String, json: String): String =
        b64(seal(vaultKey, json.toByteArray(Charsets.UTF_8), "rec:$id"))

    fun decryptRecord(vaultKey: ByteArray, id: String, data: String): String =
        String(open(vaultKey, unb64(data), "rec:$id"), Charsets.UTF_8)

    fun encryptBlob(vaultKey: ByteArray, id: String, bytes: ByteArray): ByteArray = seal(vaultKey, bytes, "blob:$id")
    fun decryptBlob(vaultKey: ByteArray, id: String, bytes: ByteArray): ByteArray = open(vaultKey, bytes, "blob:$id")
}
