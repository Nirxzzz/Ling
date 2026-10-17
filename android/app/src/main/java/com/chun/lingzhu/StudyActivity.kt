package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*

/** 学习、复习与计划共用一个入口；不新建另一份卡片或学习进度。 */
class StudyActivity : Activity() {
    private val prefs by lazy { Prefs(this) }
    private val store by lazy { KnowledgeCard.Store(this) }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "学习与复习"
    }

    override fun onResume() { super.onResume(); draw() }

    private fun draw() {
        Ui.theme = prefs.theme()
        val plan = prefs.studyPlan
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll); Brut.page(this, scroll)
        label("学习与复习", 24f)
        val now = System.currentTimeMillis()
        val all = store.list().filter { plan.acceptsCard(it.deck) }
        val wordDue = WordSrs.all(prefs).count { (key, card) -> plan.acceptsWord(key.substringBefore('\u0000')) && Fsrs.isDue(card, now) }
        label("知识卡 ${all.size} 张 · 新卡 ${all.count { it.fsrs.isNew }} 张\n待复习：知识卡 ${all.count { !it.fsrs.isNew && it.isDue(now) }} 张 / 单词 $wordDue 个")
        button("开始学习 · 每轮最多 ${plan.newLimit} 项") { startActivity(ReviewActivity.intent(this).putExtra("studyNew", true)) }
        button("开始复习 · 每轮最多 ${plan.reviewLimit} 项") { startActivity(ReviewActivity.intent(this)) }
        button("知识卡库 · 新建 / 编辑") { startActivity(Intent(this, KnowledgeListActivity::class.java)) }
        button("导入文档生成知识卡（TXT / MD / DOC / DOCX / 文本 PDF）") { startActivity(Intent(this, ImportActivity::class.java)) }
        label("本机学习计划", 20f)
        label("保存后用于下次开课；修改间隔参数只影响之后的评分，不清空旧进度。限额是每轮上限，不是每日任务。")
        val words = check("学习 / 复习单词", plan.words)
        val cards = check("学习 / 复习知识卡", plan.cards)
        var wordDecks = plan.wordDecks
        var cardDecks = plan.cardDecks
        button("选择单词词库（当前 ${if (wordDecks.isEmpty()) "全部" else "${wordDecks.size} 个"}）") {
            val decks = prefs.deckList()
            choose("单词词库", decks.map { it.id to it.name }, wordDecks) { wordDecks = it }
        }
        button("选择知识卡牌组（当前 ${if (cardDecks.isEmpty()) "全部" else "${cardDecks.size} 个"}）") {
            choose("知识卡牌组", store.list().map { it.deck }.distinct().map { it to it }, cardDecks) { cardDecks = it }
        }
        val newLimit = field("每轮新学数量（0–500）", plan.newLimit.toString(), true)
        val reviewLimit = field("每轮复习数量（1–1000）", plan.reviewLimit.toString(), true)
        val retention = field("目标记忆率 %（70–99；越高复习越频繁）", plan.retention.toString(), true)
        val maxDays = field("最大复习间隔 / 天（1–36500）", plan.maxDays.toString(), true)
        val learning = field("学习步骤 / 分钟（例 1,10；留空自动）", plan.learning)
        val relearning = field("忘记后的重学步骤 / 分钟（例 10）", plan.relearning)
        button("保存学习计划") {
            try {
                fun number(e: EditText, range: IntRange): Int = e.text.toString().toInt().also { require(it in range) { "数字超出标注范围" } }
                require(words.isChecked || cards.isChecked) { "至少选择一种学习内容" }
                val p = StudyPlan(words.isChecked, cards.isChecked, wordDecks, cardDecks,
                    number(newLimit, 0..500), number(reviewLimit, 1..1000), number(retention, 70..99), number(maxDays, 1..36500),
                    learning.text.toString().trim(), relearning.text.toString().trim())
                p.params(); prefs.studyPlan = p
                Toast.makeText(this, "学习计划已保存", Toast.LENGTH_SHORT).show(); draw()
            } catch (e: Exception) { Toast.makeText(this, e.message ?: "请检查数字和学习步骤", Toast.LENGTH_LONG).show() }
        }
        check("每日到期提醒", prefs.reviewNotiOn).setOnCheckedChangeListener { _, on -> prefs.reviewNotiOn = on; ReviewNotify.kick(this) }
        button("提醒时刻 · ${ReviewNotify.fmtMin(prefs.reviewEffectiveMin)}${if (prefs.popupUnifiedOn) "（统一时刻）" else ""}") {
            val minute = prefs.reviewEffectiveMin
            TimePickerDialog(this, { _, h, m ->
                if (prefs.popupUnifiedOn) {
                    prefs.popupUnifiedMin = h * 60 + m
                    BriefingReceiver.kick(this)
                } else prefs.reviewNotiMin = h * 60 + m
                ReviewNotify.kick(this); draw()
            }, minute / 60, minute % 60, true).show()
        }
        label("到期提醒仍统计全部既有学习记录；牌组选择仅限定本轮学习范围。")

        label("知识卡定时弹窗（独立于弹单词）", 20f)
        label("开启后按下面间隔从小球里弹一张所选牌组的知识卡：正面 → 点一下看答案 → 四档评分。关闭 / 稍后不评分。")
        check("知识卡定时弹窗", prefs.knowledgePopupOn).setOnCheckedChangeListener { _, on ->
            prefs.knowledgePopupOn = on
            if (on) KnowledgeAlarm.schedule(this) else KnowledgeAlarm.cancel(this)
        }
        button("弹窗间隔 · ${Prefs.fmtInterval(prefs.knowledgePopupEverySec)}") {
            pickInterval("知识卡弹窗间隔", prefs.knowledgePopupEverySec) {
                prefs.knowledgePopupEverySec = it
                KnowledgeAlarm.schedule(this); draw()
            }
        }
        button("知识卡牌组 · ${if (prefs.knowledgePopupDecks.isEmpty()) "未选（不弹）" else "${prefs.knowledgePopupDecks.size} 个"}") {
            chooseDecks("知识卡弹窗牌组", store.list().map { it.deck }.distinct(), prefs.knowledgePopupDecks) {
                prefs.knowledgePopupDecks = it; draw()
            }
        }
        button("试弹一张知识卡") {
            try { startForegroundService(Intent(this, OrbService::class.java).setAction(OrbService.ACTION_KNOWLEDGE)) }
            catch (e: Exception) { Toast.makeText(this, "起不了小球：" + (e.message ?: "多半是悬浮窗权限"), Toast.LENGTH_SHORT).show() }
        }

        button("返回") { finish() }
        Brut.style(root)
    }

    private fun choose(title: String, items: List<Pair<String, String>>, selected: Set<String>, done: (Set<String>) -> Unit) {
        if (items.isEmpty()) { Toast.makeText(this, "还没有牌组，请先添加或导入", Toast.LENGTH_SHORT).show(); return }
        val chosen = selected.toMutableSet()
        AlertDialog.Builder(this).setTitle("$title · 不勾选表示全部")
            .setMultiChoiceItems(items.map { it.second }.toTypedArray(), items.map { it.first in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen.add(items[i].first) else chosen.remove(items[i].first)
            }.setPositiveButton("确定") { _, _ -> done(chosen); Toast.makeText(this, "范围已选择，请保存学习计划", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("取消", null).show()
    }

    /** 知识卡弹窗间隔：与弹单词共用同一档位表（[Prefs.INTERVAL_STEPS]），单选手选。 */
    private fun pickInterval(title: String, current: Int, done: (Int) -> Unit) {
        val steps = Prefs.INTERVAL_STEPS
        AlertDialog.Builder(this).setTitle("$title（当前 ${Prefs.fmtInterval(current)}）")
            .setSingleChoiceItems(steps.map { Prefs.fmtInterval(it) }.toTypedArray(), steps.indexOfFirst { it == current }) { d, i ->
                done(steps[i]); d.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    /** 知识卡弹窗牌组：**不勾选 = 一个都不弹**（与学习范围的「不勾选 = 全部」刻意不同，避免误弹）。 */
    private fun chooseDecks(title: String, decks: List<String>, selected: Set<String>, done: (Set<String>) -> Unit) {
        if (decks.isEmpty()) { Toast.makeText(this, "还没有知识卡牌组，先导入或新建", Toast.LENGTH_SHORT).show(); return }
        val chosen = selected.toMutableSet()
        AlertDialog.Builder(this).setTitle("$title · 不勾选表示不弹任何牌组")
            .setMultiChoiceItems(decks.toTypedArray(), decks.map { it in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen.add(decks[i]) else chosen.remove(decks[i])
            }.setPositiveButton("确定") { _, _ -> done(chosen) }
            .setNegativeButton("取消", null).show()
    }
    private fun dp(n: Int) = Ui.dp(this, n.toFloat())
    private fun label(text: String, size: Float = 14f) = root.addView(TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)) })
    private fun button(text: String, action: () -> Unit) = root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    private fun check(text: String, value: Boolean): CheckBox = CheckBox(this).apply { this.text = text; isChecked = value; root.addView(this) }
    private fun field(label: String, value: String, numeric: Boolean = false): EditText {
        label(label)
        return EditText(this).apply { setText(value); setSingleLine(); if (numeric) inputType = InputType.TYPE_CLASS_NUMBER; root.addView(this) }
    }
}
