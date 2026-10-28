package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentTools 协议层。
 *
 * 只测**不需要 Context** 的那一半：工具表本身、名字核对、参数容错与失败话术。
 * 执行成功路径要真建卡 / 真写待办，属于 Android 层，本机 JVM 测试里拿不到可用的 Context ——
 * 与其写一个到处 NPE 的假测试，不如把「不崩」这条底线测扎实：
 * 这一层的全部价值就在于「模型无论给什么鬼参数，都不能让整轮对话断掉」。
 */
class AgentToolsTest {

    /** 测试只读协议层，永不调用 executor，所以 ctx 永远用不到 —— 但类型上必须给一个 [ToolContext] */
    private fun exec(name: String, args: String): String =
        AgentTools.execute(NoCtx, name, args)

    /**
     * 只测协议层与参数校验，任何走到 app 的路径都该在测试里炸出来 —— 那说明这个用例越界了。
     * [ToolContext.app] 的类型是 Android 的 Context，测试里不能真造一个（本工程单测不放 android 依赖），
     * 所以这里用 unreachable() 占位：真被碰到就是 ClassCastException / NPE，比静默返回 null 更容易定位。
     */
    private object NoCtx : ToolContext {
        // NoCtx 是嵌套 object，看不到外层类的私有成员，所以这里直接抛，不调外层的 unreachable()。
        override val app: android.content.Context
            get() = throw AssertionError("协议层测试不该走到需要 Context 的分支")
        override val pid: String get() = "p0"
    }

    // ---------------- 工具表 ----------------

    @Test fun toolsJsonIsValidJsonArray() {
        val arr = JSONArray(AgentTools.toolsJson())
        assertTrue("工具表不能是空的", arr.length() > 0)
    }

    /** 每个工具都必须 name / description / parameters 齐全 —— 缺一样模型就没法正确调用 */
    @Test fun everyToolHasNameDescriptionAndParameters() {
        val arr = JSONArray(AgentTools.toolsJson())
        val names = HashSet<String>()
        for (i in 0 until arr.length()) {
            val fn = arr.getJSONObject(i).getJSONObject("function")
            val name = fn.getString("name")
            val desc = fn.getString("description")
            val params = fn.getJSONObject("parameters")

            assertTrue("工具名不能为空", name.isNotBlank())
            assertTrue("描述太短，模型不知道该什么时候用：" + name, desc.length >= 20)
            assertEquals("参数表的 type 必须是 object", "object", params.getString("type"))
            assertNotNull("缺少 properties：" + name, params.optJSONObject("properties"))
            assertTrue("工具名重复：" + name, names.add(name))
        }
    }

    /** 每个 required 字段都得在 properties 里声明过，否则模型会拿到一份自相矛盾的契约 */
    @Test fun requiredFieldsAreDeclared() {
        val arr = JSONArray(AgentTools.toolsJson())
        for (i in 0 until arr.length()) {
            val fn = arr.getJSONObject(i).getJSONObject("function")
            val toolName = fn.getString("name")
            val props = fn.getJSONObject("parameters").optJSONObject("properties") ?: JSONObject()
            val req = fn.getJSONObject("parameters").optJSONArray("required") ?: JSONArray()
            for (j in 0 until req.length()) {
                val k = req.getString(j)
                assertTrue(toolName + " 声明了 required 却没定义 " + k, props.has(k))
            }
        }
    }

    @Test fun registryAndToolsJsonAgree() {
        val arr = JSONArray(AgentTools.toolsJson())
        assertEquals(AgentTools.registry().size, arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i).getJSONObject("function").getString("name")
            assertTrue("toolsJson 里有 registry 没有的工具：" + n, AgentTools.hasTool(n))
        }
    }

    /** 名字常量与注册表必须一致：常量漂了，ApiClient 里按名字分发就全落到 else 分支 */
    @Test fun nameConstantsAreRegistered() {
        assertTrue(AgentTools.hasTool(AgentTools.NAME_ADD_CARD))
        assertTrue(AgentTools.hasTool(AgentTools.NAME_ADD_REMINDER))
        assertTrue(AgentTools.hasTool(AgentTools.NAME_SEARCH_MEMORY))
        assertTrue(AgentTools.hasTool(AgentTools.NAME_COST_REPORT))
    }

    @Test fun unknownNameIsNotATool() {
        assertFalse(AgentTools.hasTool("web_search"))   // 联网工具不归这一层管
        assertFalse(AgentTools.hasTool(""))
        assertFalse(AgentTools.hasTool("Add_Knowledge_Card"))   // 大小写敏感
    }

    /** 每个工具都要有给界面看的进展文案：空 label 会让 UI 那一格什么都不显示 */
    @Test fun everyToolHasLabel() {
        for (spec in AgentTools.registry()) {
            assertTrue("工具 " + spec.name + " 缺 label", spec.label.isNotBlank())
            assertTrue("label 不像进行时中文", spec.label.contains("…"))
        }
        assertEquals("", AgentTools.labelOf("not_a_tool"))
    }

    // ---------------- 不存在的工具 ----------------

    @Test fun unknownToolReturnsReadableChinese() {
        val msg = AgentTools.execute(NoCtx, "delete_everything", "{}")
        assertTrue(msg, msg.contains("没有名为"))
        assertTrue("要把可用工具列出来：" + msg, msg.contains(AgentTools.NAME_ADD_CARD))
        assertTrue("不能把整套注册表以外的工具也列上", !msg.contains("web_search"))
    }

    /** 模型发了个空名（偶尔真的会）也不能炸 */
    @Test fun blankToolNameIsHandled() {
        val msg = AgentTools.execute(NoCtx, "", "{}")
        assertTrue(msg, msg.contains("没有名为"))
    }

    // ---------------- 模块门控（v2.4） ----------------

    /**
     * 用户关掉一个模块，它的工具**不该出现在请求里**。
     *
     * 与白名单的区别：白名单是「这个 Agent 用不用」，模块是「这个 App 有没有」。
     * 两者都要在 toolsJson 生效，否则模型看到工具就会去调，用户以为关掉了其实只是没显示。
     */
    @Test fun disabledModuleHidesItsTools() {
        val arr = JSONArray(AgentTools.toolsJson(emptySet(), setOf(TodoFeature.id)))
        val sent = HashSet<String>()
        for (i in 0 until arr.length()) sent.add(arr.getJSONObject(i).getJSONObject("function").getString("name"))

        assertFalse("关掉待办后不该再发建提醒：" + sent, sent.contains(AgentTools.NAME_ADD_REMINDER))
        assertTrue("别的模块的工具不能受牵连：" + sent, sent.contains(AgentTools.NAME_ADD_CARD))
    }

    /**
     * 只在 toolsJson 过滤不够：模型凭上下文硬调照样能执行。
     *
     * 这里用待办工具验证 —— 它需要真写数据库，一旦漏过门控就会碰到 [NoCtx.app] 直接抛
     * AssertionError，所以「返回一句拒绝话术」本身就证明执行没被走进去。
     */
    @Test fun disabledModuleBlocksExecute() {
        val msg = AgentTools.execute(NoCtx, AgentTools.NAME_ADD_REMINDER, "{}", emptySet(), setOf(TodoFeature.id))
        assertTrue("要说清是模块被关了，而不是 Agent 没开这个工具：" + msg, msg.contains("模块"))
        assertFalse("不能把两条拒绝理由混在一起：" + msg, msg.contains("当前 Agent 没有开启"))
    }

    // ---------------- 参数容错 ----------------

    /** 这些都必须「返回一句中文」而不是抛异常 —— 抛了就等于整轮对话断掉 */
    @Test fun malformedArgsNeverThrow() {
        val bad = listOf(
            "",
            "   ",
            "{",
            "not json at all",
            "[1,2,3]",
            "null",
            "\"just a string\"",
            "{\"front\": null, \"back\": 42}",
        )
        for (name in AgentTools.names()) {
            for (args in bad) {
                val msg = try {
                    exec(name, args)
                } catch (e: Throwable) {
                    throw AssertionError("工具 " + name + " 遇到参数 " + args + " 抛了异常：" + e, e)
                }
                assertTrue("工具 " + name + " 对 " + args + " 回了空串", msg.isNotBlank())
            }
        }
    }

    /** 未知参数不能让它崩：模型经常自作主张多塞字段（timezone / unit / language 之类） */
    @Test fun extraUnknownArgsAreIgnored() {
        val msg = exec(
            AgentTools.NAME_ADD_CARD,
            "{\"front\":\"导数是什么\",\"back\":\"瞬时变化率\",\"front_extra\":1,\"zzz\":{\"a\":[1,2]},\"timezone\":\"Asia/Shanghai\"}",
        )
        assertTrue(msg.isNotBlank())
    }

    /** 缺必填字段要给出**指明缺什么**的话术，而不是一句泛泛的「执行失败」 */
    @Test fun missingRequiredArgsAreNamed() {
        val noFront = exec(AgentTools.NAME_ADD_CARD, "{\"back\":\"只有反面\"}")
        assertTrue(noFront, noFront.contains("正面"))

        val noBack = exec(AgentTools.NAME_ADD_CARD, "{\"front\":\"只有正面\"}")
        assertTrue(noBack, noBack.contains("反面"))

        val noQuery = exec(AgentTools.NAME_SEARCH_MEMORY, "{}")
        assertTrue(noQuery, noQuery.contains("检索词"))

        val noText = exec(AgentTools.NAME_ADD_REMINDER, "{\"due_at\": 1}")
        assertTrue(noText, noText.contains("提醒内容"))
    }

    /** 已过去的时间要拦下来：建出来也永远不会响，用户会以为设好了 */
    @Test fun pastDueTimeIsRejected() {
        val msg = exec(AgentTools.NAME_ADD_REMINDER,
            "{\"text\":\"交作业\",\"due_at\":1000}")
        assertTrue(msg, msg.contains("已经过去"))
    }

    /** 毫秒时间戳被写成字符串也要认（模型很常这么干） */
    @Test fun dueAtAsStringIsParsed() {
        val past = System.currentTimeMillis() - 10 * 60_000L
        val msg = exec(AgentTools.NAME_ADD_REMINDER,
            "{\"text\":\"交作业\",\"due_at\":\"" + past + "\"}")
        // 认出了这是"过去的时间"就说明字符串被成功解析成了数字；没解析会报"没拿到有效的到点时间"
        assertTrue(msg, msg.contains("已经过去"))
    }

    // ---------------- 纯函数 ----------------

    @Test fun clipShortensAndMarksEllipsis() {
        assertEquals("abc", AgentTools.clip("abc", 10))
        assertEquals("abc…", AgentTools.clip("abcdef", 3))
        assertEquals("a b", AgentTools.clip("a\nb", 10))   // 换行压成空格，免得撑破一行日志
    }

    @Test fun fmtTimeIsChineseStyle() {
        val s = AgentTools.fmtTime(1_700_000_000_000L)
        assertTrue(s, s.contains("月") && s.contains("日"))
    }

    @Test fun todayStrIsEightDigits() {
        val s = AgentTools.todayStr()
        assertEquals(s, 8, s.length)
        assertTrue(s, s.all { it.isDigit() })
    }
}
