package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.79 聊天发图（[ChatStore.Msg.img]）。只测纯函数那一层：序列化 / 丢不丢 / 请求体形状。
 *
 * 三件必须钉住的事：
 *  1. **纯文字会话一个字都不能变** —— 落盘格式和请求体都是（服务端前缀缓存锚在请求开头，
 *     多写一个键或换一种 content 形状都会让历史段每轮全价重算）；
 *  2. 空正文 + 有图 = **一条合法消息**（只发图不打字是常用操作），空正文 + 没图才丢；
 *  3. 这一轮刚发的图走多模态数组，历史里的图（base64 不落盘）退化成占位文字，
 *     绝不能发出 content 为空的消息（OpenAI 兼容实现会 400）。
 */
class ChatImageTest {
    private val t0 = 1_760_000_000_000L
    private val cfg = ApiConfig("https://x/v1", "k", "m")

    private fun plain(i: Int) = ChatStore.user("问$i", t0 + i * 1000L)
    private fun pic(i: Int, text: String = "") = ChatStore.user(text, t0 + i * 1000L, img = "img-$i.jpg")

    // ---------------- 1. 落盘 ----------------

    @Test fun imageMessageRoundTripsWithItsFileName() {
        val l = listOf(plain(1), pic(2, "看这个"), pic(3))
        val back = ChatStore.parse(ChatStore.toJson(l))
        assertEquals(l, back)
        assertEquals("img-3.jpg", back[2].img)
        assertNull(back[0].img)
    }

    /** 纯文字那条序列化出来还得是老三样（role/content/at），一个键都不能多 */
    @Test fun textOnlyMessageKeepsTheOldShape() {
        val o = JSONArray(ChatStore.toJson(listOf(plain(1)))).getJSONObject(0)
        assertEquals(3, o.length())
        assertEquals("user", o.getString("role"))
        assertEquals("问1", o.getString("content"))
        assertTrue(o.has("at"))
        // 和「加图片功能之前那段代码」逐字对照：三个键、同样顺序、不多不少
        val before = JSONArray().put(JSONObject().put("role", "user").put("content", "问1").put("at", t0 + 1000L)).toString()
        assertEquals(before, ChatStore.toJson(listOf(plain(1))))
    }

    // ---------------- 2. 丢不丢 ----------------

    /** 只发图不打字是常规操作：空正文 + 有图必须留下来 */
    @Test fun blankContentWithImageIsAValidMessage() {
        val l = ChatStore.add(emptyList(), pic(1))
        assertEquals(1, l.size)
        assertEquals("img-1.jpg", l[0].img)
        assertEquals(listOf(pic(1)), ChatStore.parse(ChatStore.toJson(l)))
    }

    @Test fun blankContentWithoutImageIsStillDropped() {
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.add(emptyList(), ChatStore.user("", t0)))
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse("""[{"role":"user","content":"","at":1}]"""))
    }

    // ---------------- 3. 请求体 ----------------

    private fun messagesOf(list: List<ChatStore.Msg>, imgB64: (ChatStore.Msg) -> String? = { null }) =
        JSONObject(ChatStore.body(cfg, list, system = "SYS", imgB64 = imgB64)).getJSONArray("messages")

    /** 不带图（imgB64 恒空）→ content 永远是纯字符串，形状和加图片功能之前一样 */
    @Test fun withoutBase64EverythingStaysPlainText() {
        val ms = messagesOf(listOf(plain(1), pic(2, "看这个")))
        assertEquals(3, ms.length())
        assertTrue(ms.getJSONObject(1).get("content") is String)
        assertEquals("看这个", ms.getJSONObject(2).getString("content"))
    }

    /** 历史里的图：base64 不落盘 → 退化成占位文字，不能是空字符串 */
    @Test fun historicalImageDegradesToPlaceholder() {
        val ms = messagesOf(listOf(pic(1)))
        assertEquals(ChatStore.IMG_PLACEHOLDER, ms.getJSONObject(1).getString("content"))
    }

    /** 这一轮刚发的图 → content 升格成多模态数组（text + image_url），形状和识屏那条线一致 */
    @Test fun currentImageBecomesAMultimodalContentArray() {
        val cur = pic(2)
        val ms = messagesOf(listOf(plain(1), cur)) { if (it.at == cur.at) "AAA" else null }
        val content = ms.getJSONObject(2).getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals(ChatStore.IMG_ASK, content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AAA", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("user", ms.getJSONObject(2).getString("role"))
    }

    /** 有图也有字：文字用用户自己写的，不是占位 */
    @Test fun textAlongsideImageIsSentVerbatim() {
        val cur = pic(2, "这是啥")
        val ms = messagesOf(listOf(cur)) { "AAA" }
        assertEquals("这是啥", ms.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    /** 小珠自己的消息永远没有图：即便回调给错了，也得老老实实是纯字符串 */
    @Test fun orbMessagesNeverBecomeMultimodal() {
        val ms = messagesOf(listOf(ChatStore.orb("答", t0)) ) { "AAA" }
        assertTrue(ms.getJSONObject(1).get("content") is String)
    }

    /** 图在窗口之外（老消息）时不影响：窗口里那条还是纯文字 */
    @Test fun imageOutsideTheWindowDoesNotAffectTheBody() {
        val old = pic(1)
        val list = mutableListOf(old)
        for (i in 2..30) { list.add(plain(i)); list.add(ChatStore.orb("答$i", t0 + i * 1000L + 500L)) }
        val ms = messagesOf(list) { if (it.at == old.at) "AAA" else null }
        for (i in 1 until ms.length()) assertTrue(ms.getJSONObject(i).get("content") is String)
    }

    // ---------------- 4. wireText ----------------

    @Test fun wireTextOnlySubstitutesForImages() {
        assertEquals("[图片]", ChatStore.wireText(pic(1)))
        assertEquals("看这个", ChatStore.wireText(pic(2, "看这个")))
        assertEquals("问1", ChatStore.wireText(plain(1)))
        // 空正文又没图：不编造占位（这条消息本来就该被 add/parse 丢掉，走到这儿也不该冒出一个「[图片]」）
        assertEquals("", ChatStore.wireText(ChatStore.user("", t0)))
    }
}
