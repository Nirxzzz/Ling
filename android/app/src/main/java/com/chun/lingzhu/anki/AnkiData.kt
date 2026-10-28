package com.chun.lingzhu

/**
 * Ling 2.7 Anki：导入后的数据模型（契约固定，UI / 渲染器 / 存储三方共用）。
 *
 * 这里只放**数据**，不放行为：
 *  - 字段一律保留 Anki 原样（HTML、`[sound:]`、`{{...}}` 模板语法都不清洗、不截断），
 *    渲染交给 [AnkiTemplate]，持久化交给 [AnkiDeckStore]。
 *  - [Card.state] 是本应用自己的 FSRS 状态（[Fsrs.Card]），不是 Anki 的调度状态；
 *    导入进来的卡一律从 New 开始（沿用现有约定），重导时按卡 id 找回本地学习进度。
 */
object AnkiData {

    /** 模板字段。`ord` 是它在 [Model.fields] / [Note.fields] 里的下标（0 起）。 */
    data class Field(val name: String, val ord: Int)

    /**
     * 卡片模板。`ord` 保留包里的原值（0 起，不重排）；
     * [front] / [back] 是原始 `q_format` / `a_format`，原样保存。
     */
    data class Template(val ord: Int, val name: String, val front: String, val back: String)

    /** 笔记类型。[cloze] = 源库里的 type==1（旧版）/ `Config.kind==KIND_CLOZE`（新版 schema18）。 */
    data class Model(
        val id: Long,
        val name: String,
        val cloze: Boolean,
        val css: String,
        val fields: List<Field>,
        val templates: List<Template>,
    )

    /** 笔记。[fields] 是 `flds` 按 U+001F 切开的原始片段（空片段保留，不补不裁）。 */
    data class Note(
        val id: Long,
        val modelId: Long,
        val fields: List<String>,
        val tags: List<String>,
    )

    /** 卡片。[ord] 是模板序（Cloze 的 `{{c1::}}` 对应 ord 0），[state] 是本地 FSRS 状态。 */
    data class Card(
        val id: Long,
        val noteId: Long,
        val deckId: Long,
        val ord: Int,
        val state: Fsrs.Card = Fsrs.Card(),
    )

    /** 牌组。[name] 是含 `::` 的完整层级名；[cards] / [due] 是导入后实时统计。 */
    data class Deck(val id: Long, val name: String, val cards: Int = 0, val due: Int = 0)

    /** 一道待学习的卡：卡 + 笔记 + 模板 + 所属牌组名，渲染器只需要这一份。 */
    data class StudyCard(
        val collection: String,
        val card: Card,
        val note: Note,
        val model: Model,
        val deck: String,
    )

    /** 一次导入的结果。[collection] 是后续所有接口要传的集合名。 */
    data class ImportResult(
        val collection: String,
        val notes: Int,
        val cards: Int,
        val media: Int,
        val warnings: List<String>,
    )
}
