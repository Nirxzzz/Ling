package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.4 D 档：**工作流可编程** —— 用户把几个提示词步骤串成一条流水线。
 *
 * ## 解决什么问题
 * 现在只有「一个模式 = 一段提示词」。但真实的用法常常是**多步**的：
 *   1. 先看这题考什么（只要考点，不要答案）
 *   2. 再按考点展开讲解
 *   3. 最后出一道同类题让我练
 * 这三步的输出**不一样**，塞进一段提示词只会得到一个四不像。
 *
 * ## 设计取舍：为什么不做成「自动多轮调用」
 * 多轮调用 = 一次点击烧三次 token、等三倍时间、花三倍钱。而且中途任何一步失败，
 * 用户拿到的是一半的结果，比没有更糟。所以这里做成**显式的一步步走**：
 * 每一步用户自己按「下一步」，看得见每步结果、可以中途改主意。
 * 便宜、可控、不会因为一次误触扣掉三倍费用 —— 对「一个还在比赛评审期的 App」这是对的取舍。
 */
object Workflow {

    /** 单个步骤的上限：再多就不是「工作流」而是「没人会读完的清单」 */
    const val MAX_STEPS = 8

    /** 单步提示词上限，和 [Customize.MAX_PROMPT_CHARS] 对齐（防 token 失控） */
    const val MAX_STEP_CHARS = 2000

    const val MAX_NAME_CHARS = 20

    /** 步骤占位符：用上一步的输出替换。写错了不会崩，会原样留在提示词里（人一眼能看见） */
    const val PREV = "{{prev}}"

    /**
     * 一个步骤。
     * @param title 步骤名（显示在按钮上，如「看考点」）
     * @param prompt 这一步的提示词。可含 [PREV] 占位符，会被上一步的输出替换
     * @param usePrev 是否把上一步的输出拼进上下文（false = 这一步独立看画面，不看上一步）
     */
    data class Step(
        val title: String = "步骤",
        val prompt: String = "",
        val usePrev: Boolean = true,
    )

    /**
     * 一条工作流。
     * @param id 稳定标识（持久化与切换用），不要改
     */
    data class Flow(
        val id: String,
        val name: String = "我的工作流",
        val steps: List<Step> = emptyList(),
    ) {
        val usable: Boolean get() = steps.size >= 2 && steps.all { it.prompt.isNotBlank() }
    }

    /**
     * 把第 [index] 步拼成最终要发给模型的提示词。
     *
     * [prev] 是上一步的输出（第一步为 null）。
     *  - 提示词里写了 [PREV] → 就地替换
     *  - 没写但 [Step.usePrev] → 把上一步输出附在后面（用 「上一步结果：」 标记）
     *  - [Step.usePrev] = false → 完全不带上下文，这一步独立作答
     *
     * 抽成纯函数是为了让「到底发了什么给模型」可以被单测断言 ——
     * 提示词拼接错了，表现是「模型忽然答不到点上」，最难查。
     */
    fun promptFor(flow: Flow, index: Int, prev: String?): String {
        val step = flow.steps.getOrNull(index) ?: return ""
        val base = step.prompt.trim()
        if (!step.usePrev || prev.isNullOrBlank()) {
            // 不用上一步：占位符没东西可填，删掉而不是留个 {{prev}} 让模型困惑
            return base.replace(PREV, "").trim()
        }
        return if (base.contains(PREV)) {
            base.replace(PREV, prev.trim())
        } else {
            base + "\n\n上一步的结果：" + prev.trim()
        }
    }

    // ---------------- 序列化 ----------------

    const val PREFIX = "LINGFLOW1:"

    fun encode(flows: List<Flow>): String {
        val arr = JSONArray()
        flows.forEach { f ->
            val o = JSONObject()
            o.put("id", f.id)
            o.put("name", f.name.take(MAX_NAME_CHARS))
            val steps = JSONArray()
            f.steps.take(MAX_STEPS).forEach { s ->
                val so = JSONObject()
                so.put("t", s.title.take(12))
                so.put("p", s.prompt.take(MAX_STEP_CHARS))
                so.put("u", s.usePrev)
                steps.put(so)
            }
            o.put("steps", steps)
            arr.put(o)
        }
        return PREFIX + arr.toString()
    }

    /** 解析。任何不合规输入返回空表，绝不抛 —— 这是「粘贴进来」的入口 */
    fun decode(raw: String?): List<Flow> {
        val s = raw?.trim().orEmpty()
        if (!s.startsWith(PREFIX) || s.length > 20000) return emptyList()
        return try {
            val arr = JSONArray(s.substring(PREFIX.length))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "").trim()
                if (id.isEmpty()) return@mapNotNull null
                val stepsArr = o.optJSONArray("steps")
                val steps = (0 until (stepsArr?.length() ?: 0)).mapNotNull { j ->
                    val so = stepsArr?.optJSONObject(j) ?: return@mapNotNull null
                    Step(
                        title = so.optString("t", "步骤").take(12).ifBlank { "步骤" },
                        prompt = so.optString("p", "").take(MAX_STEP_CHARS),
                        usePrev = so.optBoolean("u", true),
                    )
                }.filter { it.prompt.isNotBlank() }.take(MAX_STEPS)
                Flow(id, o.optString("name", "我的工作流").take(MAX_NAME_CHARS).ifBlank { "我的工作流" }, steps)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
