package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test

class LinksTest {
    private fun targets(s: String) = Links.find(s).map { it.target }
    private fun texts(s: String) = Links.find(s).map { s.substring(it.start, it.end) }

    @Test fun urls() {
        assertEquals(listOf("https://example.com/a?b=1"), targets("官网：https://example.com/a?b=1。"))
        assertEquals(listOf("https://www.baidu.com"), targets("打开www.baidu.com就行"))
        assertEquals(listOf("https://a.com/x"), targets("见（https://a.com/x）"))
        assertEquals(listOf("https://a.com/x"), texts("(see https://a.com/x)."))
        assertEquals(listOf("https://zh.wikipedia.org/wiki/Foo_(bar)"), texts("https://zh.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals(listOf("http://10.0.2.2:18080/v1"), targets("地址 http://10.0.2.2:18080/v1，"))
    }

    @Test fun phonesAndEmails() {
        assertEquals(listOf("tel:13800138000"), targets("客服电话 138-0013-8000"))
        assertEquals(listOf("tel:+8613800138000"), targets("+86 13800138000"))
        assertEquals(listOf("tel:01012345678"), targets("座机 010-12345678"))
        assertEquals(listOf("tel:4008123123"), targets("400-812-3123"))
        assertEquals(listOf("mailto:ab.c@mail.qq.com"), targets("发到 ab.c@mail.qq.com 。"))
    }

    @Test fun noFalsePositives() {
        assertTrue(targets("x = 12345 + 3.1415926，答案 138").isEmpty())
        assertTrue(targets("面积是 138001380001 平方米").isEmpty())   // 12 位不是手机号
        assertTrue(targets("Node.js 和 example.com 不算（没 www 也没 http）").isEmpty())
        // 网址里的数字不再当电话
        assertEquals(listOf("https://a.com/u/13800138000"), targets("https://a.com/u/13800138000"))
    }

    @Test fun safety() {
        assertTrue(Links.safe("https://a.com"))
        assertTrue(Links.safe("tel:+8613800138000"))
        assertTrue(Links.safe("mailto:a@b.cn"))
        assertFalse(Links.safe("javascript:alert(1)"))
        assertFalse(Links.safe("intent://x#Intent;end"))
        assertFalse(Links.safe("file:///sdcard/a"))
        assertFalse(Links.safe("https://"))
        assertFalse(Links.safe("tel:abc"))
        assertEquals("https://www.a.com", Links.normalize("www.a.com"))
        assertEquals("mailto:a@b.cn", Links.normalize("a@b.cn"))
    }

    @Test fun mdLinkRegexAndLabel() {
        val m = Links.MD.find("看[百度百科](https://baike.baidu.com/item/x)吧")!!
        assertEquals("百度百科", m.groupValues[1])
        assertEquals("https://baike.baidu.com/item/x", m.groupValues[2])
        assertEquals("baike.baidu.com", Links.label("https://baike.baidu.com/item/x?y=1"))
        assertEquals("13800138000", Links.label("tel:13800138000"))
    }
}
