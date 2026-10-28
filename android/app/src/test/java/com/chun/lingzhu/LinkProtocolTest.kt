package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ling-link v1 的协议层：配对串严格解析 + 加密信封 + 与桌面端共享的已知答案向量。
 *
 * 这些都是纯 JVM 逻辑（[LinkPairStore.parse] / [LinkWire] 不碰 Android API），所以能在这里直接测：
 *  - 配对串解析的每一条拒绝规则都单独测一遍 —— 少测一条就可能把密钥发到公网或发错设备；
 *  - 信封的双向一致性 + 抗篡改 + 规范编码；
 *  - 与桌面端逐字节对齐的 KAT：手机算出的密文必须与电脑端算出的**完全相同**，
 *    对不上就说明 AAD / IV / 编码里有任何一处不一致（这条比自测「能解自己」有力得多）。
 */
class LinkProtocolTest {

    // ---------------- 配对串 ----------------

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val keyText: String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    private val pairId = "0123456789abcdef0123456789abcdef"

    private fun uri(host: String = "192.168.1.23", port: Int = 47322, id: String = pairId, k: String = keyText) =
        "ling-link://$host:$port/$id.$k"

    @Test fun parseAcceptsPrivateAndLoopback() {
        for (host in listOf("192.168.1.23", "10.0.2.2", "172.16.0.1", "172.31.255.254", "127.0.0.1")) {
            val p = LinkPairStore.parse(uri(host = host))
            assertEquals(host, p.host)
            assertEquals(47322, p.port)
            assertEquals(pairId, p.id)
            assertArrayEquals(key, p.key)
            assertEquals(LinkPairStore.DEFAULT_NAME, p.name)
        }
    }

    @Test fun parseRejectsPublicAddress() {
        for (host in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "11.0.0.1")) {
            assertRejects(uri(host = host))
        }
    }

    @Test fun parseRejectsHostnameAndIpv6() {
        assertRejects(uri(host = "macbook.local"))
        assertRejects("ling-link://[::1]:47322/$pairId.$keyText")
        assertRejects(uri(host = "192.168.001.23"))   // 前导零 = 可能的八进制歧义
        assertRejects(uri(host = "192.168.1.256"))
        assertRejects(uri(host = "192.168.1"))
    }

    @Test fun parseRejectsBadPorts() {
        assertRejects(uri(port = 80))          // < 1024
        assertRejects(uri(port = 65536))
        assertRejects(uri(port = 0))
        assertRejects("ling-link://192.168.1.23:4a/$pairId.$keyText")
        assertRejects("ling-link://192.168.1.23/$pairId.$keyText")   // 缺端口
    }

    @Test fun parseRejectsBadSchemeQueryFragment() {
        assertRejects("http://192.168.1.23:47322/$pairId.$keyText")
        assertRejects("ling-sync://192.168.1.23:47322/$pairId.$keyText")
        assertRejects(uri() + "?x=1")
        assertRejects(uri() + "#frag")
        assertRejects("")
    }

    @Test fun parseRejectsBadPairId() {
        assertRejects(uri(id = "0123456789ABCDEF0123456789ABCDEF"))   // 大写
        assertRejects(uri(id = "0123456789abcdef0123456789abcde"))    // 31 位
        assertRejects(uri(id = "0123456789abcdef0123456789abcdef0"))  // 33 位
        assertRejects(uri(id = "0123456789abcdef0123456789abcdeg"))
    }

    @Test fun parseRejectsBadKey() {
        assertRejects(uri(k = keyText.dropLast(1)))                    // 42 位
        assertRejects(uri(k = keyText + "A"))                          // 44 位
        assertRejects(uri(k = keyText.replace('A', '+')))               // 非 base64url 字母表
        assertRejects(uri(k = keyText + "="))                          // 带 padding
        // 41/42 位的 base64url 同样要挡（解出来不是 32 字节）
        val short = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31))
        assertRejects(uri(k = short))
    }

    @Test fun parseRejectsNonCanonicalKeyEncoding() {
        // 32 字节密钥里末位 bit 全 0 时，`...A` 与 `...B` 解码同一串字节，但只有一个规范形式。
        // 这里构造一个末位多余 bit 已被改动的变体：解码后仍是 32 字节，但回编码不等于原文。
        val raw = ByteArray(32)
        val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertTrue(canonical.endsWith("A"))
        val variant = canonical.dropLast(1) + "B"
        val decoded = Base64.getUrlDecoder().decode(variant)
        assertEquals(32, decoded.size)   // 同样能解出 32 字节 —— 所以必须靠回编码把变体挡掉
        assertRejects(uri(k = variant))
    }

    @Test fun parseRejectsDuplicateDotAndTrailingSlash() {
        assertRejects(uri() + "/")
        assertRejects("ling-link://192.168.1.23:47322/$pairId.$keyText.extra")
        assertRejects("ling-link://192.168.1.23:47322/$pairId")
    }

    @Test fun parseTrimsSurroundingWhitespace() {
        assertEquals("192.168.1.23", LinkPairStore.parse("  ${uri()}  ").host)
    }

    private fun assertRejects(s: String) {
        val threw = try {
            LinkPairStore.parse(s)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("应拒绝：$s", threw)
    }

    // ---------------- 信封 ----------------

    private fun sealWithKey(k: ByteArray, iv: ByteArray, plain: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("ling-link-v1".toByteArray(Charsets.UTF_8))
        return c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    @Test fun wireRoundTrip() {
        val inner = JSONObject()
            .put("v", 1)
            .put("pairId", pairId)
            .put("kind", "hello")
            .put("seq", 7)
            .put("at", 1700000000000L)
        val envelope = LinkWire.seal(key, inner)
        val back = LinkWire.open(key, envelope, "hello", pairId)
        assertEquals("hello", back.optString("kind"))
        assertEquals(pairId, back.optString("pairId"))
        assertEquals(7, back.optInt("seq"))
        assertEquals(1700000000000L, back.optLong("at"))
    }

    @Test fun wireUsesFreshIvPerSeal() {
        val inner = JSONObject().put("v", 1).put("pairId", pairId).put("kind", "poll").put("seq", 1).put("at", 1L)
        val a = JSONObject(LinkWire.seal(key, inner)).getString("iv")
        val b = JSONObject(LinkWire.seal(key, inner)).getString("iv")
        assertEquals(16, a.length)   // 12 字节 → 标准 base64 带 padding 正好 16 字符
        assertFalse("每次都要新 IV：$a == $b", a == b)
    }

    @Test fun wireRejectsWrongKindAndPairId() {
        val inner = JSONObject().put("v", 1).put("pairId", pairId).put("kind", "poll").put("seq", 1).put("at", 1L)
        val env = LinkWire.seal(key, inner)
        assertRejected { LinkWire.open(key, env, "hello", pairId) }        // kind 不符
        assertRejected { LinkWire.open(key, env, "poll", "ffffffffffffffffffffffffffffffff") }   // 外层 pairId 不符
    }

    /**
     * 桌面端的**回执**内层不带 pairId（`{v,kind,ok,...}`，见契约 wire v1 与 ling-link.js 的
     * handleHello / handlePoll / handleResult）。这条必须收下 —— 以前要求内层一定有 pairId，
     * 真实连接上每一条回执都被判成「内外层 pairId 不一致」，手机永远停在「连接断开」。
     */
    @Test fun wireAcceptsDesktopResponseWithoutInnerPairId() {
        val iv = ByteArray(12) { 7 }
        // 完全照桌面的形状封：外层显式给 pairId，内层只有 v/kind/ok + 载荷。
        val resp = """{"v":1,"kind":"hello","ok":true,"serverTime":1700000000000,"pollTimeoutMs":25000}"""
        val envelope = JSONObject()
            .put("v", 1)
            .put("pairId", pairId)
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("data", Base64.getEncoder().encodeToString(sealWithKey(key, iv, resp)))
            .toString()
        val inner = LinkWire.open(key, envelope, "hello", pairId)
        assertTrue(inner.optBoolean("ok"))
        assertEquals(25_000, inner.optInt("pollTimeoutMs"))
        assertEquals(0, inner.optString("pairId", "").length)   // 回执本来就不带它
    }

    @Test fun wireRejectsInnerPairIdThatContradictsOuter() {
        val inner = JSONObject().put("v", 1).put("pairId", pairId).put("kind", "poll").put("seq", 1).put("at", 1L)
        val env = LinkWire.seal(key, inner)
        // 自洽的一对照收；把内层换成另一条配对就必须拒（防张冠李戴）。
        assertEquals("poll", LinkWire.open(key, env, "poll", pairId).optString("kind"))
        val other = "ffffffffffffffffffffffffffffffff"
        val swapped = JSONObject(env).put("pairId", other)   // 外层换掉，内层仍是本配对的
        assertRejected { LinkWire.open(key, swapped.toString(), "poll", other) }
    }

    @Test fun wireRejectsTamperedCiphertext() {
        val inner = JSONObject().put("v", 1).put("pairId", pairId).put("kind", "result").put("seq", 1).put("at", 1L)
        val o = JSONObject(LinkWire.seal(key, inner))
        val data = Base64.getDecoder().decode(o.getString("data"))
        data[0] = (data[0].toInt() xor 1).toByte()
        o.put("data", Base64.getEncoder().encodeToString(data))
        assertRejected { LinkWire.open(key, o.toString(), "result", pairId) }
    }

    @Test fun wireRejectsWrongKey() {
        val inner = JSONObject().put("v", 1).put("pairId", pairId).put("kind", "poll").put("seq", 1).put("at", 1L)
        val env = LinkWire.seal(key, inner)
        val other = ByteArray(32) { 9 }
        assertRejected { LinkWire.open(other, env, "poll", pairId) }
    }

    @Test fun wireRejectsNonCanonicalBase64() {
        // base64url 字母表（- _）与省略 padding 两种变体都必须挡掉：同一段密文只允许一种写法。
        assertNull(LinkWire.strictBase64("AQEBAQEBAQ"))            // 10 字符：不是 4 的倍数（省略 padding 的写法）
        assertNull(LinkWire.strictBase64("AQEBAQEBAQ-E"))          // base64url 字母表
        assertNull(LinkWire.strictBase64("AQEBAQEBAQ_B"))
        assertNotNull(LinkWire.strictBase64("AQEBAQEBAQEB"))       // 规范写法

        // 信封层面同样挡：把 data 换成带 -/_ 的变体，open() 直接判协议错误。
        val inner = JSONObject().put("v", 1).put("pairId", pairId).put("kind", "poll").put("seq", 1).put("at", 1L)
        val o = JSONObject(LinkWire.seal(key, inner))
        val url = o.getString("data").replace('+', '-').replace('/', '_')
        val tampered = JSONObject(o.toString()).put("data", url)
        // 如果密文里恰好没有 + /，url 与原文逐字相同 —— 那时这条子断言没有意义，跳过。
        if (url != o.getString("data")) {
            assertRejected { LinkWire.open(key, tampered.toString(), "poll", pairId) }
        }
    }

    @Test fun wireRejectsOversizeBody() {
        assertRejected { LinkWire.open(key, "{" + " ".repeat(LinkWire.MAX_ENVELOPE) + "}", null, null) }
    }

    @Test fun wireRejectsBadVersion() {
        assertRejected { LinkWire.open(key, "{\"v\":2,\"pairId\":\"$pairId\",\"iv\":\"\",\"data\":\"\"}", null, null) }
    }

    private fun assertRejected(block: () -> Unit) {
        // 只接受 LinkWire.Rejected：别的异常（NPE / 越界 / 空密码）不该被当成「正确拒绝」放过。
        val threw = try {
            block()
            false
        } catch (_: LinkWire.Rejected) {
            true
        }
        assertTrue("应判协议错误", threw)
    }

    // ---------------- 与桌面端共享的已知答案向量 ----------------
    //
    // 电脑端（Node crypto）与手机端必须算出逐字节相同的密文。下面这段常量是双方各自算过、再互相对上的；
    // 任何一侧改了 AAD / IV 长度 / 编码方式，这里立刻红。

    @Test fun sharedKatMatchesDesktop() {
        val k = ByteArray(32)                        // 32 × 0x00
        val iv = ByteArray(12) { 1 }                 // 12 × 0x01
        val plain = """{"v":1,"pairId":"00000000000000000000000000000000","kind":"unpair","seq":1,"at":1700000000000}"""
        val ct = sealWithKey(k, iv, plain)
        assertEquals(
            "DmK1SEC4VN8S14308kteU55V3Gs/1gUGP4UhgM/fl9ywGJdtykvc03ii3E6sWPBI0nbMHNnXE+tHHloIIKvbZ7oJyZtS56XeTneWA7s81VopWFJ8dhrcv5VtcplY+Dle18WEbbKF3Pp3EU/HgnE=",
            Base64.getEncoder().encodeToString(ct),
        )

        // 反方向：把这段已知密文用手机端的 open() 解开，必须拿回同一份明文（含 inner 校验）
        val envelope = JSONObject()
            .put("v", 1)
            .put("pairId", "00000000000000000000000000000000")
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("data", Base64.getEncoder().encodeToString(ct))
            .toString()
        val inner = LinkWire.open(k, envelope, "unpair", "00000000000000000000000000000000")
        // 不比较整串文本：JSONObject 的字段顺序取决于实现（org.json 用的是 HashMap），
        // 逐字段比才是「同一份明文」的可靠断言。
        assertEquals(1, inner.optInt("v"))
        assertEquals("00000000000000000000000000000000", inner.optString("pairId"))
        assertEquals("unpair", inner.optString("kind"))
        assertEquals(1, inner.optInt("seq"))
        assertEquals(1700000000000L, inner.optLong("at"))
    }

    @Test fun confirmNormalization() {
        assertEquals("approved", LingLink.normalizeConfirm("approved"))
        assertEquals("denied", LingLink.normalizeConfirm("denied"))
        assertEquals("timeout", LingLink.normalizeConfirm("timeout"))
        assertEquals("n/a", LingLink.normalizeConfirm("n/a"))
        assertEquals("n/a", LingLink.normalizeConfirm(""))
        assertEquals("n/a", LingLink.normalizeConfirm("yes"))   // 不认识的写法一律按「没有确认动作」
    }

    @Test fun backoffScheduleMatchesContract() {
        assertArrayEquals(longArrayOf(1000, 2000, 5000, 10_000, 30_000), LingLink.BACKOFF_MS)
    }
}
