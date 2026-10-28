package com.chun.lingzhu.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 两家语音服务商（豆包 / 阿里云）的 API Key **各存各的、都是密文**。
 *
 * 三条硬规矩：
 *  1. 明文绝不落盘。落盘的是 AndroidKeyStore 里 AES-256-GCM 密钥封出来的密文
 *     （格式 `[1 字节版本=1][12 字节 IV][密文 + 16 字节 GCM tag]`）。
 *  2. 两家用**各自的 Keystore 密钥**（别名带服务商后缀），AAD 也把服务商绑进去 ——
 *     换一家用同一条密文解不出来，也不存在「改一行代码把豆包的 Key 当阿里的发出去」。
 *  3. 解不开就当没存（Keystore 被重置 / 密文被外力改坏）：清掉坏密文、返回 null，
 *     让界面如实显示「未保存」，而不是拿半个值去连。
 *
 * 与 [com.chun.lingzhu.LinkPairStore] 同一套做法，差别只在 AAD 与别名：
 * 那边的密钥是配对链路密钥，这边的密钥要发给两家云厂商，互不相干。
 */
class VoiceKeyStore(ctx: Context) {

    private val app: Context = ctx.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 这一家是否已经存了可用的 Key（真解一次密：存了但解不开不算「有」）。 */
    fun isSaved(provider: String): Boolean = load(provider) != null

    /**
     * 读回明文 Key。没有 / 解不开 / 解出来是空串都返回 null；
     * 解不开时顺手删掉这条坏密文（否则每次启动都会再失败一次）。
     */
    fun load(provider: String): String? {
        requireProvider(provider)
        val raw = prefs.getString(slot(provider), null) ?: return null
        val blob = try {
            Base64.getDecoder().decode(raw)
        } catch (_: Exception) {
            clear(provider)
            return null
        }
        return try {
            val plain = String(open(provider, blob), Charsets.UTF_8).trim()
            if (plain.isEmpty()) {
                clear(provider)
                null
            } else {
                plain
            }
        } catch (_: Exception) {
            clear(provider)
            null
        }
    }

    /** 保存（覆盖）一家的 Key。空串不写：界面那边「留空 = 沿用已存的」由调用方先判。 */
    fun save(provider: String, key: String) {
        requireProvider(provider)
        val plain = key.trim()
        require(plain.isNotEmpty()) { "Key 是空的" }
        val blob = seal(provider, plain.toByteArray(Charsets.UTF_8))
        // commit 而不是 apply：用户点「保存」要立刻拿到真实结果，失败必须能当场说清。
        val ok = prefs.edit().putString(slot(provider), Base64.getEncoder().encodeToString(blob)).commit()
        if (!ok) throw IllegalStateException("密钥写不进应用私有存储")
    }

    /** 显式删除一家的 Key（界面上的「删除」按钮）。 */
    fun clear(provider: String) {
        requireProvider(provider)
        prefs.edit().remove(slot(provider)).apply()
    }

    // ---------------- 加解密 ----------------

    private fun seal(provider: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        // IV 由加密器自己生成：Keystore 的 AES-GCM 密钥默认不允许调用方指定 IV。
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(provider))
        cipher.updateAAD(aad(provider))
        val ct = cipher.doFinal(plain)
        val iv = cipher.iv ?: throw IllegalStateException("加密器没有给出 IV")
        require(iv.size == IV_BYTES) { "IV 长度不是 12 字节" }
        return ByteArray(1 + iv.size + ct.size).also {
            it[0] = VERSION
            System.arraycopy(iv, 0, it, 1, iv.size)
            System.arraycopy(ct, 0, it, 1 + iv.size, ct.size)
        }
    }

    private fun open(provider: String, blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_BYTES + TAG_BITS / 8) { "密文长度不对" }
        require(blob[0] == VERSION) { "密文版本不认识" }
        val iv = blob.copyOfRange(1, 1 + IV_BYTES)
        val ct = blob.copyOfRange(1 + IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(provider), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(provider))
        return cipher.doFinal(ct)
    }

    private fun secretKey(provider: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = alias(provider)
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 转写要能在界面上随时开始，不能每次解密都弹指纹；打开这一页本身就是用户授权。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    private fun aad(provider: String): ByteArray = "$AAD_PREFIX$provider".toByteArray(Charsets.UTF_8)

    private fun slot(provider: String): String = "key.$provider"

    private fun alias(provider: String): String = "$KEY_ALIAS_PREFIX$provider"

    private fun requireProvider(provider: String) {
        // 按 [VoiceProvider.KEYED] 校验而不是 [VoiceProvider.ALL]：腾讯只有密钥槽位、不进转写选择器，
        // 但它的三个字段同样要加密落盘。认不认识仍然只由这一处说了算（不额外开白名单）。
        require(VoiceProvider.isKeyed(provider)) { "不认识的语音服务商：$provider" }
    }

    private companion object {
        const val PREFS = "ling_voice_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS_PREFIX = "ling-voice-asr-v1-"
        const val AAD_PREFIX = "ling-voice-asr-v1:"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
