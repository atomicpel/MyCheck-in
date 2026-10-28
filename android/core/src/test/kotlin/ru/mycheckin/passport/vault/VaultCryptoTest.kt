package ru.mycheckin.passport.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Совместимость с веб-версией: вектор создан server/web/crypto.js (WebCrypto) — телефон обязан его прочитать. */
class VaultCryptoTest {
    private val pw = "Ёжик в тумане — Пароль 2026!"
    private val kdf = Kdf("PBKDF2-SHA256", 600000, "qdBlf+7FyULH0N3IfM9YNw==")
    private val authKey = "N50fJD5b9JlRmx5GpTPTpMo+iFWuDYZyswB+Mgx8lhQ="
    private val wrappedKey = "wKtMEHbglWnkNGrNSTGQNHWnUM1gOKGQ6s6QeCmZl1Cd/EsUiZspWGco9Go0fdE9IFIpVZS1tik1pMaU"
    private val id = "dc54747b-557e-4f01-9239-8135ec22e325"
    private val data = "fLHicVYWSDwxVmIBnWFOSjMXS2zpg123ja8qm7JmCCtFpS+VRIQlLGUJ/lamonjOzvs8eMuaAQVsR/6MaxOc+rXbugC2OyDpKmLhlSB5mDd9GK+BOpzez572slVwMIx8esPQLyXLSAUiv2sR1ccM0Vtw8OQsmXr2zDKRoCJn"
    private val blobId = "d3e2e287-dc3d-49b4-a082-5c3a430b6ffa"
    private val blob = "NrdOUOMMecr1934E6JU7rnv7q1PICOLOtKosqaDmbJw="

    @Test fun readsVaultCreatedInBrowser() {
        val keys = VaultCrypto.deriveKeys(pw.toCharArray(), kdf)
        assertEquals(authKey, VaultCrypto.b64(keys.authKey))
        val vk = VaultCrypto.unwrapVaultKey(keys.kek, wrappedKey)
        val rec = VaultCrypto.decryptRecord(vk, id, data)
        assertTrue("Ёлкин" in rec && "79896296939" in rec, rec)
        assertContentEquals(byteArrayOf(1, 2, 3, 250.toByte()), VaultCrypto.decryptBlob(vk, blobId, VaultCrypto.unb64(blob)))
        // Чужой идентификатор (подмена записи сервером) не расшифровывается
        assertFailsWith<Exception> { VaultCrypto.decryptRecord(vk, "другая-запись", data) }
        // Неверный пароль не разворачивает ключ
        val wrong = VaultCrypto.deriveKeys("ёжик в тумане — Пароль 2026!".toCharArray(), kdf)
        assertFailsWith<Exception> { VaultCrypto.unwrapVaultKey(wrong.kek, wrappedKey) }
    }

    @Test fun pbkdf2MatchesRfcVector() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256("passwd", "salt", 1) — первые 32 байта
        val out = VaultCrypto.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", out.joinToString("") { "%02x".format(it) })
    }

    @Test fun roundTrip() {
        val v = VaultCrypto.createVault("Новый пароль 12345".toCharArray())
        val keys = VaultCrypto.deriveKeys("Новый пароль 12345".toCharArray(), v.kdf)
        assertContentEquals(v.vaultKey, VaultCrypto.unwrapVaultKey(keys.kek, v.wrappedKey))
        val enc = VaultCrypto.encryptRecord(v.vaultKey, "id-1", "{}")
        assertTrue(enc != VaultCrypto.encryptRecord(v.vaultKey, "id-1", "{}"))   // новый IV каждый раз
        assertEquals("{}", VaultCrypto.decryptRecord(v.vaultKey, "id-1", enc))
    }
}
