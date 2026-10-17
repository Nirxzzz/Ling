package com.chun.lingzhu

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** v1.63：取消能马上掐断、网络抖动自动重试一次、报错大白话、检测连接顺带测看图 */
class NetTest {
    private lateinit var server: MiniHttp
    private val hits = AtomicInteger()
    @Volatile private var handler: (Int, JSONObject) -> Pair<Int, String> = { _, _ -> 200 to "" }

    @Before fun up() {
        ApiClient.RETRY_WAIT_MS = 100
        server = MiniHttp { _, body -> handler(hits.incrementAndGet(), JSONObject(body)) }
    }
    @After fun down() { server.stop(); ApiClient.RETRY_WAIT_MS = 1500 }

    private fun cfg() = ApiConfig("http://127.0.0.1:${server.port}/v1", "sk-test", "m")
    private fun sse(vararg parts: String) = parts.joinToString("") { "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", it)))) + "\n\n" } + "data: [DONE]\n\n"
    private fun msg(content: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()
    private val opts = Solver.Options(Prompts.MODE_GENERAL, "standard", false)
    private val noop = object : Solver.Callbacks { override fun onPhase(text: String) {}; override fun onText(text: String) {} }

    @Test fun abortUnblocksHangingRequest() {
        handler = { _, _ -> Thread.sleep(20_000); 200 to sse("太晚了") }
        val c = Cancel()
        var result: String? = null; var err: Throwable? = null
        val t = thread { try { result = Solver(cfg()).solve("QUJD", opts, noop, c) } catch (e: Throwable) { err = e } }
        Thread.sleep(400)
        val t0 = System.currentTimeMillis()
        c.abort()
        t.join(5_000)
        assertTrue("abort 后 2 秒内应返回", !t.isAlive && System.currentTimeMillis() - t0 < 2_000)
        assertNull(err)
        assertEquals("", result)
        assertEquals(1, hits.get())   // 取消后不能再重试
    }

    @Test fun retriesOnceOn429ThenSucceeds() {
        handler = { n, _ -> if (n == 1) 429 to "{\"error\":{\"message\":\"rate limit\"}}" else 200 to sse("好", "的") }
        assertEquals("好的", Solver(cfg()).translate("hi", "auto", noop, Cancel()))
        assertEquals(2, hits.get())
    }

    @Test fun retriesOnlyOnce() {
        handler = { _, _ -> 503 to "{\"error\":{\"message\":\"busy\"}}" }
        try { Solver(cfg()).translate("hi", "auto", noop, Cancel()); fail() } catch (e: ApiException) {
            assertTrue(e.message!!, e.message!!.startsWith("HTTP 503（服务端出错"))
        }
        assertEquals(2, hits.get())
    }

    @Test fun noRetryOn401() {
        handler = { _, _ -> 401 to "{\"error\":{\"message\":\"bad key\"}}" }
        try { Solver(cfg()).translate("hi", "auto", noop, Cancel()); fail() } catch (e: ApiException) {}
        assertEquals(1, hits.get())
    }

    @Test fun visionUnsupportedIsExplained() {
        handler = { _, _ -> 400 to "{\"error\":{\"message\":\"This model does not support image input\"}}" }
        try { Solver(cfg()).solve("QUJD", opts, noop, Cancel()); fail() } catch (e: ApiException) {
            assertTrue(e.message!!, e.message!!.contains("不支持看图"))
        }
    }

    @Test fun networkErrorsAreChinese() {
        val dead = ApiConfig("http://127.0.0.1:1/v1", "k", "m")   // 端口 1：连接被拒
        try { Solver(dead).translate("hi", "auto", noop, Cancel()); fail() } catch (e: ApiException) {
            assertTrue(e.message!!, e.message!!.startsWith("连不上接口服务器"))
        }
        assertTrue(NetErrors.friendly(java.net.UnknownHostException("x"), "https://api.example.com/v1").contains("api.example.com"))
        assertTrue(NetErrors.friendly(java.net.SocketTimeoutException("t")).contains("超时"))
    }

    @Test fun pingVisionReadsTheSeven() {
        var sentImage = ""
        handler = { _, b ->
            sentImage = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
            assertEquals(false, b.getBoolean("stream"))
            200 to msg("7")
        }
        assertNull(ApiClient(cfg()).pingVision())
        assertTrue(sentImage.startsWith("data:image/png;base64,iVBOR"))
        handler = { _, _ -> 200 to msg("我看不到图片") }
        assertTrue(ApiClient(cfg()).pingVision()!!.contains("没认出来"))
        handler = { _, _ -> 400 to "{\"error\":{\"message\":\"image_url is not supported\"}}" }
        assertTrue(ApiClient(cfg()).pingVision()!!.contains("看图失败"))
    }

    @Test fun screenshotScaling() {
        assertEquals(1080 to 2400, scaledSize(1080, 2400))       // 竖屏原样
        assertEquals(2400 to 1080, scaledSize(2400, 1080))       // 横屏不再被压成 1080 宽
        assertEquals(1080 to 2400, scaledSize(1440, 3200))       // 2K 屏按短边缩
        assertEquals(1440 to 1080, scaledSize(2800, 2100))       // 4:3 平板按短边
        assertEquals(2400 to 800, scaledSize(3000, 1000))        // 超宽：长边封顶
        assertEquals(720 to 1600, scaledSize(720, 1600))         // 不放大
    }
}

class FileMemoTest {
    @Test fun readsOnceWritesInBackground() {
        val f = java.io.File.createTempFile("memo", ".json"); f.writeText("[\"a\"]")
        val parse = { s: String? -> if (s == null) listOf() else org.json.JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }
        assertEquals(listOf("a"), FileMemo.get(f, parse))
        f.writeText("[\"改了但不该读到\"]")
        assertEquals(listOf("a"), FileMemo.get(f, parse))   // 第二次读内存
        FileMemo.put(f, listOf("a", "b")) { org.json.JSONArray(it).toString() }
        assertEquals(listOf("a", "b"), FileMemo.get(f, parse))
        var t = 0; while (!f.readText().contains("b") && t < 50) { Thread.sleep(20); t++ }
        assertEquals("[\"a\",\"b\"]", f.readText())             // 后台落盘了
        FileMemo.clear(f, emptyList<String>())
        assertEquals(emptyList<String>(), FileMemo.get(f, parse))  // 清空后立刻读不到旧内容
        t = 0; while (f.exists() && t < 50) { Thread.sleep(20); t++ }
        assertTrue(!f.exists())
    }
}

class RegionTest {
    @Test fun boxOrdersAndClamps() {
        assertEquals(Region.Box(100, 200, 300, 400), Region.box(300f, 400f, 100f, 200f, 1080, 2400))
        assertEquals(Region.Box(0, 0, 1080, 50), Region.box(-20f, -5f, 2000f, 50f, 1080, 2400))
        assertTrue(!Region.usable(Region.box(10f, 10f, 30f, 200f, 1080, 2400), 48))
    }
    @Test fun mapsToScaledShot() {
        // 2K 屏 1440x3200 截图缩成 1080x2400：框按 0.75 换算
        assertEquals(Region.Box(75, 150, 300, 600), Region.map(Region.Box(100, 200, 400, 800), 1440, 3200, 1080, 2400))
        // 同尺寸原样；贴边的框不越界
        assertEquals(Region.Box(0, 0, 1080, 2400), Region.map(Region.Box(0, 0, 1080, 2400), 1080, 2400, 1080, 2400))
    }
    @Test fun smallBoxesAreUpscaled() {
        assertEquals(1.0, Region.upscale(600, 800), 0.0)
        assertEquals(2.0, Region.upscale(240, 700), 0.001)
        assertEquals(3.0, Region.upscale(100, 300), 0.001)     // 最多 3 倍
        assertEquals(2400.0 / 1000, Region.upscale(200, 1000), 0.001)  // 长边不超 2400
    }
}
