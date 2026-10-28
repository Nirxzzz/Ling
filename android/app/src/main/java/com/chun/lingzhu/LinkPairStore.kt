package com.chun.lingzhu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 手机常连（MCP）的配对信息：`ling-link://<privateIPv4>:<port>/<pairId 32hex>.<key base64url43>`。
 *
 * 三件事，缺一不可：
 *  1. **严格解析**（[parse]）：只认字面私网 IPv4 / 回环、端口 1024–65535、pairId 32 位小写 hex、
 *     密钥 32 字节 base64url 且必须是规范编码。不认主机名、账号密码、query、fragment ——
 *     任何一条不合就整串拒绝，绝不「猜一个看起来像的地址」把密钥发出去。
 *  2. **整条 metadata + 密钥加密落盘**：id / host / port / name / key 一起 JSON 化后用 Android Keystore
 *     里的 AES-256-GCM 密钥加密，密文写 `filesDir/link/pair.bin`。Keystore 密钥不可导出，
 *     备份里即使带上密文也没有解它的钥匙（另外 Backup 规则里 `link/` 也显式排除）。
 *  3. **不泄密**：本类不打日志、不把配对信息塞进 Intent / Bundle / Toast；`Pair.key` 只给 [LingLink]
 *     用来做 AES-GCM 认证加密。
 *
 * 非机密的状态（常连开关、seq 计数块、允许的 app 别名）放普通 SharedPreferences：
 * 它们不是密钥，但也绝不放配对串本身。
 */
class LinkPairStore(context: Context) {

    /** 一条配对。`key` 是 32 字节链路密钥，调用方用完不要复制扩散。 */
    data class Pair(
        val id: String,
        val host: String,
        val port: Int,
        val key: ByteArray,
        val name: String = DEFAULT_NAME,
    )

    private val app: Context = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appsPrefs = app.getSharedPreferences(PREFS_APPS, Context.MODE_PRIVATE)
    private val pairFile = File(File(app.filesDir, DIR), "pair.bin")

    /**
     * 读配对；没有 / 解不开 / 字段不合法都返回 null（不抛给界面，不把原因说成「没配对过」之外的猜测）。
     * 解密失败同时清掉坏文件：Keystore 密钥被系统重置（换机恢复、清凭据）后密文永远解不开，
     * 留着只会每次启动白试一遍。
     */
    fun load(): Pair? {
        if (!pairFile.isFile) return null
        val plain = try {
            LinkCrypto.open(secretKey(), pairFile.readBytes())
        } catch (_: Exception) {
            runCatching { pairFile.delete() }
            return null
        }
        return try {
            parseStored(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            runCatching { pairFile.delete() }
            null
        }
    }

    /** 保存一条配对（整体覆盖）。写临时文件再 rename，掉电不会留下半个密文。 */
    fun save(pair: Pair) {
        require(pair.key.size == KEY_BYTES) { "密钥长度不对" }
        val json = JSONObject()
            .put("id", pair.id)
            .put("host", pair.host)
            .put("port", pair.port)
            .put("name", pair.name)
            .put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(pair.key))
            .toString()
        val sealed = LinkCrypto.seal(secretKey(), json.toByteArray(Charsets.UTF_8))
        val dir = pairFile.parentFile ?: throw IllegalStateException("没有应用私有目录")
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("建不了配对目录")
        val tmp = File(dir, "pair.bin.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(pairFile)) {
            // 个别文件系统 rename 目标已存在时会失败：先删再重试一次，仍失败就如实报错。
            pairFile.delete()
            if (!tmp.renameTo(pairFile)) {
                tmp.delete()
                throw IllegalStateException("配对信息写不进去")
            }
        }
    }

    /** 解除配对：先删密文，再清掉与这条配对绑定的状态。Keystore 密钥留着复用（它不属于某一条配对）。 */
    fun clear() {
        runCatching { pairFile.delete() }
        runCatching { File(pairFile.parentFile, "pair.bin.tmp").delete() }
    }

    // ---------------- 常连开关（不是密钥，普通 SharedPreferences） ----------------

    /**
     * 用户是否打开过「后台常连」。**默认关**：装上、配对完都不自动开 ——
     * 配对是「记住这台电脑」，常连是「现在开始后台听电脑的指令」，两件事分开确认。
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /**
     * 每个请求一个正整数 seq。桌面只校验「正整数」，不要求严格递增（长轮询与回结果会合法重叠），
     * 所以这里按块分配：内存里加一，每 [SEQ_BLOCK] 个才落盘一次，避免每 25 秒写一次 Preferences。
     * 进程重启后从落盘的块起点继续，最多重复 [SEQ_BLOCK] 个数值 —— 服务端不使用它做防重放。
     */
    fun nextSeq(): Int = synchronized(seqLock) {
        if (seqUsed >= SEQ_BLOCK) {
            seqBase += SEQ_BLOCK
            seqUsed = 0
            prefs.edit().putInt(KEY_SEQ_BASE, seqBase).apply()
        }
        seqUsed++
        seqBase + seqUsed
    }

    private val seqLock = Any()
    private var seqBase: Int = prefs.getInt(KEY_SEQ_BASE, 0)
    private var seqUsed: Int = 0

    // ---------------- 允许的 app 别名（与 LinkDispatcher 共用） ----------------

    /**
     * 允许从电脑打开的 app 别名集合。**默认只有三个** [DEFAULT_APPS]（且 [LinkDispatcher] 还会再按
     * 「本机是否装了这个包」过滤一遍）；其余已安装的 app 一律默认关，用户在本页逐个打开。
     *
     * 存的是别名，不是包名：工具参数里永远只出现别名，绝不会把任意包名字符串交给电脑。
     */
    fun allowedApps(): Set<String> {
        val raw = appsPrefs.getStringSet(KEY_ALLOWED, null) ?: return DEFAULT_APPS
        return raw.filter { it.isNotBlank() }.toSet()
    }

    fun setAllowedApps(aliases: Collection<String>) {
        val clean = aliases.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        appsPrefs.edit().putStringSet(KEY_ALLOWED, clean).apply()
    }

    fun isAppAllowed(alias: String): Boolean = alias in allowedApps()

    // ---------------- Keystore ----------------

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 常连要在后台读配对，不能要求每次解密都让用户解锁指纹 —— 打开常连本身就是用户授权。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    private fun parseStored(raw: String): Pair {
        val o = JSONObject(raw)
        val id = o.getString("id")
        val host = o.getString("host")
        val port = o.getInt("port")
        val name = o.optString("name", DEFAULT_NAME).ifBlank { DEFAULT_NAME }
        val keyText = o.getString("key")
        // 落盘内容同样过一遍严格校验：文件被外力改坏时宁可当作「没配对」，也不拿半个合法字段去连。
        val ip = parseIpv4(host) ?: throw IllegalStateException("host")
        if (!isPrivateOrLoopback(ip)) throw IllegalStateException("host")
        if (port !in MIN_PORT..MAX_PORT) throw IllegalStateException("port")
        if (!isPairId(id)) throw IllegalStateException("id")
        val key = decodeKey(keyText) ?: throw IllegalStateException("key")
        return Pair(id, host, port, key, name)
    }

    companion object {
        const val SCHEME = "ling-link://"
        const val DEFAULT_NAME = "电脑"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        /** 与桌面约定的默认端口（Link 服务；MCP Hub 是 47321，两回事）。 */
        const val DEFAULT_PORT = 47322

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ling-link-pair-v1"
        private const val PREFS = "ling_link"
        private const val PREFS_APPS = "link_apps"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SEQ_BASE = "seqBase"
        private const val KEY_ALLOWED = "allowed"
        private const val DIR = "link"
        private const val SEQ_BLOCK = 512

        /** 默认放行的三个别名（还要本机真的装了对应的 app 才在名单里出现）。 */
        val DEFAULT_APPS: Set<String> = setOf("ling_review", "ling_words", "calendar")

        /**
         * 严格解析配对串。规则与桌面生成端一一对应，任何一条不满足就抛 [IllegalArgumentException]：
         *  - 必须以 `ling-link://` 开头，总长 ≤ 512；
         *  - 整串不许出现 `?` / `#`（query、fragment 一律拒绝）；
         *  - 恰好是 `<IPv4>:<port>/<pairId>.<key>`，多一段路径都不行；
         *  - IPv4 必须是**字面**地址且属于 10/8、172.16–31、192.168/16 或 127/8（不做 DNS，不发公网）；
         *  - 端口 1024–65535；
         *  - pairId 32 位**小写** hex；key 43 位 base64url 且解码正好 32 字节、必须是规范编码。
         */
        fun parse(uri: String): Pair {
            val s = uri.trim()
            if (s.isEmpty()) throw IllegalArgumentException("先粘贴电脑上显示的配对串。")
            if (s.length > 512) throw IllegalArgumentException("配对串太长，不像有效的配对串。")
            if (!s.startsWith(SCHEME)) throw IllegalArgumentException("配对串应以 ling-link:// 开头。")
            if (s.contains('?') || s.contains('#')) throw IllegalArgumentException("配对串里不该有 ? 或 #。")

            val rest = s.substring(SCHEME.length)
            val slash = rest.indexOf('/')
            if (slash <= 0) throw IllegalArgumentException("配对串缺少 /配对信息。")
            val authority = rest.substring(0, slash)
            val payload = rest.substring(slash + 1)
            if (payload.isEmpty()) throw IllegalArgumentException("配对串缺少配对信息。")
            if (payload.contains('/')) throw IllegalArgumentException("配对信息里不该再有 /。")

            val colon = authority.lastIndexOf(':')
            if (colon <= 0 || authority.indexOf(':') != colon) {
                throw IllegalArgumentException("地址应是 IPv4:端口（不支持 IPv6 或主机名）。")
            }
            val host = authority.substring(0, colon)
            val portText = authority.substring(colon + 1)
            if (portText.isEmpty() || portText.length > 5 || portText.any { it !in '0'..'9' }) {
                throw IllegalArgumentException("端口不是有效数字。")
            }
            val port = portText.toIntOrNull() ?: throw IllegalArgumentException("端口不是有效数字。")
            if (port !in MIN_PORT..MAX_PORT) throw IllegalArgumentException("端口应在 1024–65535。")

            val ip = parseIpv4(host) ?: throw IllegalArgumentException("只接受字面 IPv4 地址（例如 192.168.1.23）。")
            if (!isPrivateOrLoopback(ip)) {
                throw IllegalArgumentException("只接受局域网地址（10.x / 172.16–31.x / 192.168.x）或回环地址。")
            }

            val dot = payload.indexOf('.')
            if (dot <= 0 || payload.indexOf('.', dot + 1) >= 0) {
                throw IllegalArgumentException("配对信息应是 <pairId>.<密钥>。")
            }
            val id = payload.substring(0, dot)
            val keyText = payload.substring(dot + 1)
            if (!isPairId(id)) throw IllegalArgumentException("pairId 应是 32 位小写十六进制。")
            val key = decodeKey(keyText)
                ?: throw IllegalArgumentException("密钥应是 32 字节的 base64url（43 个字符，不带 =）。")
            return Pair(id, host, port, key, DEFAULT_NAME)
        }

        /** 32 位小写 hex。大小写混写会让同一条配对有两种写法，所以只认小写。 */
        fun isPairId(s: String): Boolean =
            s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

        private fun decodeKey(text: String): ByteArray? {
            if (text.length != 43) return null
            if (!text.all { isBase64UrlChar(it) }) return null
            val key = try {
                Base64.getUrlDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (key.size != KEY_BYTES) return null
            // 非规范编码（末位多余 bit 之类的变体）拒绝：同一串只允许一种解释。
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(key) != text) return null
            return key
        }

        private fun isBase64UrlChar(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

        /** 只认四段十进制、无前导零（`010` 这种可能是八进制的写法直接拒绝）。 */
        fun parseIpv4(host: String): IntArray? {
            if (host.isEmpty() || host.length > 15) return null
            val parts = host.split('.')
            if (parts.size != 4) return null
            val out = IntArray(4)
            for (i in 0 until 4) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3) return null
                if (p.length > 1 && p[0] == '0') return null
                if (p.any { it !in '0'..'9' }) return null
                val n = p.toIntOrNull() ?: return null
                if (n !in 0..255) return null
                out[i] = n
            }
            return out
        }

        fun isPrivateOrLoopback(ip: IntArray): Boolean {
            val a = ip[0]
            val b = ip[1]
            return a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                a == 127
        }
    }
}

/**
 * 配对信息落盘用的对称加密（Keystore 里的 AES-256-GCM）。
 *
 * 放在这里而不是散进 [LinkPairStore]：单元测试要能在纯 JVM 上验证「封 / 开往返 + 抗篡改」，
 * 所以 [seal]/[open] 只依赖传入的 [SecretKey]，不碰 Android Keystore。
 * 文件格式：`[1 字节版本=1][12 字节 IV][密文+GCM tag 16B]`。
 */
internal object LinkCrypto {
    private const val VERSION: Byte = 1
    private const val TAG_BITS = 128

    /**
     * 封一条配对信息。
     *
     * **IV 必须由加密器自己生成，不能自己传**：AndroidKeystore 里的 AES-GCM 密钥默认
     * `setRandomizedEncryptionRequired(true)`，此时 `cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))`
     * 会直接抛 `InvalidAlgorithmParameterException: Caller-provided IV not permitted` ——
     * 那正是「点了保存、输入框清了、配对却没存下来」的真凶（Keystore 根本不让我们指定 IV）。
     * 于是改成不传 IV、读回 `cipher.iv` 再随密文一起存；解密时用存下来的 IV 是允许的。
     */
    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(plain)
        val iv = cipher.iv ?: throw IllegalStateException("加密器没有给出 IV")
        require(iv.size == LinkPairStore.IV_BYTES) { "IV 长度不是 12 字节" }
        return ByteArray(1 + iv.size + ct.size).also {
            it[0] = VERSION
            System.arraycopy(iv, 0, it, 1, iv.size)
            System.arraycopy(ct, 0, it, 1 + iv.size, ct.size)
        }
    }

    fun open(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > 1 + LinkPairStore.IV_BYTES + TAG_BITS / 8) { "密文长度不对" }
        require(blob[0] == VERSION) { "密文版本不认识" }
        val iv = blob.copyOfRange(1, 1 + LinkPairStore.IV_BYTES)
        val ct = blob.copyOfRange(1 + LinkPairStore.IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ct)
    }

    private val AAD = "ling-link-pair-v1".toByteArray(Charsets.UTF_8)
}
