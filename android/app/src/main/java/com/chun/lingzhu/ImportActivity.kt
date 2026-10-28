package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

// 会话阶段（文件内私有）：来源编辑 / AI 生成中 / 结果待确认 / 上一轮失败
private const val PHASE_SOURCE = 0
private const val PHASE_RUNNING = 1
private const val PHASE_RESULTS = 2
private const val PHASE_FAILED = 3

/**
 * 一张「待确认」的知识卡（可编辑模型）。
 *
 * **明确分工**：牌组 / 问题 / 答案可以被用户改——改了就是用户自己的内容；[excerpt] 是 AI 那次生成在
 * 原文里**实际出现过的摘录**（只读），落库时写进 [KnowledgeCard.KCData.origin]，用来核对「这张卡确实出自那段原文」。
 * 内联编辑的内容不当作模型生成结果看待（见类注释）。
 */
private class Row(
    var deck: String,
    var front: String,
    var back: String,
    val excerpt: String,
    var include: Boolean = true,
)

/**
 * 一次 AI 导入的保留态（[ImportActivity.onRetainNonConfigurationInstance] 交给新实例的就是它）。
 *
 * **绝不持有 Activity**：只有发起时的原文 / 文件名 / 生成中线程的取消开关 + 一个指向当前前台页的
 * **弱引用**（只用来喊重画），外加 applicationContext。于是旋转回收时正在跑的生成线程不重启、
 * **不会重复发一次 AI 请求**；[abandoned] 置位（退出页面）后，迟到的线程回调一律不再落地 ——
 * 不迟到写库、不迟到刷界面。
 */
private class ImportSession {
    @Volatile var host: WeakReference<ImportActivity>? = null
    /** 页面已放弃（finish）：后台线程的一切回调 / 落库都要闭嘴 */
    @Volatile var abandoned = false
    /** 每发起一轮生成自增；迟到的旧轮回调认号不认对象，不会把结果糊到新一轮上 */
    @Volatile var runId = 0L
    /** 每发起一次读文件自增；迟到的读文件回调认号不认对象，只有「最新那次」能落地（旧文件不许盖新文件） */
    @Volatile var loadId = 0L
    /** 正在读文件：为真时禁用来源 / 生成控件，避免读入途中发起生成、把名字署错 */
    @Volatile var loading = false
    @Volatile var phase = PHASE_SOURCE
    @Volatile var progress: Pair<Int, Int> = 0 to 0
    @Volatile var error: String? = null
    /** 待弹出的提示（后台线程置，UI 线程消费一次）—— 旋转 / 切后台都不会丢 */
    @Volatile var pendingToast: String? = null
    /** 原文版本号：换文件 / 换粘贴内容时自增，驱动新页面把输入框同步成当前原文 */
    @Volatile var sourceRev = 0
    var cancel = Cancel()
    var sourceText = ""
    var fileName: String? = null
    /** 来源行的说明（文件名 + 提取字数），只用来展示 */
    var sourceNote = ""
    /** 这一轮生成实际用的原文（核对摘录是不是真的出自原文用） */
    var genSource = ""
    /** 这一轮生成实际用的来源名（与 [genSource] 同时冻结：之后改原文 / 换文件都不影响落库署名） */
    var genFileName: String? = null
    val rows = ArrayList<Row>()

    fun attach(a: ImportActivity) { host = WeakReference(a) }
    fun detach(a: ImportActivity) { if (host?.get() === a) host = null }
    fun notifyHost() {
        val a = host?.get() ?: return
        a.runOnUiThread { if (host?.get() === a && !abandoned) a.draw() }
    }
    fun abandon() { abandoned = true; runId++; loadId++; try { cancel.abort() } catch (_: Exception) {}; host = null }
}

/**
 * 知识卡「AI 导入」（切片 D）：选文件或粘贴原文 → 预览 / 编辑原文 → **显式点按钮**才把原文发去当前接口
 * 生成问答卡 → 逐张预览 / 勾选 / 编辑 / 删除 → 确认后一次 [KnowledgeCard.Store.addBatch] 批量落库
 * （来源 [KnowledgeCard.Source.IMPORT]、未学 [Fsrs.Card]、origin = 文件名 + 出处摘录）→ 保存成功后**显式询问**
 * 是否开启知识卡弹出复习（只有用户点「开启」才写 [Prefs.knowledgePopupOn] / 合并牌组并 [KnowledgeAlarm.schedule]）。
 *
 * 纪律：
 *  - 原文只有用户**显式点「AI 生成卡片」**才会离开本机；接口 Key / 原文都不进界面、不进日志。
 *  - 取消 / AI 失败 / 旋转都**不写库**；落库只发生在「确认导入」这一下。
 *  - 旋转不重跑生成（retained non-config instance 原样带回），也不重复发请求。
 *  - 摘录只读并标注是否能在原文里核对到；内联编辑不算模型生成结果。
 */
class ImportActivity : Activity() {
    companion object {
        /** SAF 选文件的请求码（只认这一路回包） */
        private const val REQ_FILE = 9001
        /** 只请求支持格式的 MIME：txt / md / doc / docx / pdf，不接受任意文件类型。 */
        private val MIME = arrayOf(
            "text/plain",
            "text/markdown",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/pdf",
        )
        fun intent(ctx: Context): Intent = Intent(ctx, ImportActivity::class.java)
        private const val KEY_SOURCE = "import.source"
        private const val KEY_NAME = "import.name"

        /** 有界读取：最多读 [limit] + 1 字节就停（多出来的那 1 字节让调用方判「太大」），
         *  绝不把超大文件整份读进内存。放在 companion 里（不是实例方法）—— 后台读文件线程就不必隐式攥住 Activity。 */
        private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var remaining = limit.toLong() + 1
            while (remaining > 0) {
                val want = if (remaining < chunk.size) remaining.toInt() else chunk.size
                val n = input.read(chunk, 0, want)
                if (n < 0) break
                out.write(chunk, 0, n)
                remaining -= n
            }
            return out.toByteArray()
        }
    }

    private val prefs by lazy { Prefs(this) }
    private val store by lazy { KnowledgeCard.Store(this) }
    private lateinit var session: ImportSession

    private lateinit var root: LinearLayout
    private lateinit var pickBtn: Button
    private lateinit var sourceField: EditText
    private lateinit var sourceInfo: TextView
    private lateinit var apiLine: TextView
    private lateinit var genBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var statusView: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var rowsHost: LinearLayout
    private lateinit var importBtn: Button
    /** 本地结构化的结果说明（章节 / 知识点 / 建议卡数，以及摘录核对结果） */
    private lateinit var structureInfo: TextView

    private var shownSourceRev = -1
    private var rowsBuilt = false

    @Suppress("DEPRECATION")
    private fun carriedSession(): ImportSession? = lastNonConfigurationInstance as? ImportSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DocumentText.init(applicationContext)
        title = "导入知识卡"
        Ui.applyTheme(prefs)
        // 旋转回来接着用同一个保留态；否则新起一个（进程被杀后就是全新的，不自动发任何请求）。
        val carried = carriedSession()
        session = carried ?: ImportSession()
        buildViews()
        // 没有保留态（进程被杀后恢复）：系统 bundle 里的原文草稿 / 文件名补回来；**不自动发请求**。
        if (carried == null && savedInstanceState != null) {
            session.sourceText = savedInstanceState.getString(KEY_SOURCE).orEmpty()
            savedInstanceState.getString(KEY_NAME)?.let { if (it.isNotEmpty()) session.fileName = it }
            session.sourceRev++
        }
        Brut.style(root)
        session.attach(this)
        draw()
    }

    override fun onResume() {
        super.onResume()
        session.attach(this)
        draw()   // 回前台 / 新实例：按保留态重画（生成中 / 结果已回都能收）
    }

    override fun onPause() {
        super.onPause()
        // 断开弱引用：后台线程不再攥着、也不回调已经不显示的旧页
        session.detach(this)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(KEY_SOURCE, session.sourceText)
        out.putString(KEY_NAME, session.fileName ?: "")
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? {
        session.detach(this)   // 交出去前先跟旧页断开：保留态里只有数据 + 弱引用
        return session
    }

    override fun onDestroy() {
        // 真正退出（不是旋转）才放弃：掐断在飞的连接、作废这一轮回调 —— 不迟到写库
        if (isFinishing) session.abandon()
        super.onDestroy()
    }

    // ---------------- 视图 ----------------

    private fun buildViews() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        Brut.page(this, scroll)

        label("导入知识卡 · AI 生成", 24f)

        // —— 来源 ——
        sectionTitle("1. 选文件或粘贴原文")
        pickBtn = Button(this).apply {
            text = "选择文件（txt / md / doc / docx / pdf）"
            setOnClickListener { pickFile() }
        }
        root.addView(pickBtn)
        sourceInfo = TextView(this).apply { textSize = 12.5f; alpha = 0.75f; setPadding(0, dp(6), 0, dp(4)) }
        root.addView(sourceInfo)
        sourceField = EditText(this).apply {
            hint = "把要生成卡片的原文粘贴到这里，或从上面的文件提取；生成前可以随便改。"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
            minLines = 6
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { session.sourceText = s?.toString().orEmpty() }
            })
        }
        root.addView(sourceField)

        // —— 生成 ——
        sectionTitle("2. 交给 AI 生成问答卡")
        apiLine = TextView(this).apply { textSize = 12.5f; alpha = 0.8f; setPadding(0, dp(2), 0, dp(6)) }
        root.addView(apiLine)
        genBtn = Button(this).apply {
            text = "AI 生成卡片"; tag = Brut.PRIMARY
            setOnClickListener { onGenerate() }
        }
        root.addView(genBtn)
        cancelBtn = Button(this).apply {
            text = "取消生成"; tag = Brut.DANGER
            visibility = android.view.View.GONE
            setOnClickListener { cancelRun() }
        }
        root.addView(cancelBtn)
        statusView = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(statusView)

        // —— 结果 ——
        resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        root.addView(resultsBox)
        resultsBox.addView(TextView(this).apply {
            text = "3. 核对 / 编辑后确认导入（摘录只读，用来核对来源）"
            textSize = 15f; setPadding(0, dp(10), 0, dp(6))
        })
        rowsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsBox.addView(rowsHost)
        importBtn = Button(this).apply {
            text = "确认导入 0 张"; tag = Brut.PRIMARY
            setOnClickListener { commit() }
        }
        resultsBox.addView(importBtn)

        // —— 资料结构化（纯本地规则，不花接口钱）——
        // 为什么放在「生成」之后还有一条独立的路：AI 出题要联网、要花钱、要等；
        // 这条只用本地规则把资料切成章节 + 知识点 + 建议卡，离线也能走，而且每一条都带原文摘录可核对。
        // 两条路的产物最终都汇进同一个 rows，走现有的预览 / 勾选 / 编辑 / 落库流程 —— 不另开一套导入逻辑。
        sectionTitle("或：不联网，按本地规则拆分资料")
        structureInfo = TextView(this).apply { textSize = 12.5f; alpha = 0.75f; setPadding(0, dp(2), 0, dp(6)) }
        root.addView(structureInfo)
        root.addView(Button(this).apply {
            text = "结构化这段资料（本地规则）"
            setOnClickListener { onStructure() }
        })

        // 模块被关掉时整个入口不画：留一个点了就弹「已关闭」的按钮只是噪音。
        // 开关在 registry 一处判（[Features.isEnabled]），这里不自己读 Prefs。
        if (Features.isEnabled(this, "graph")) {
            root.addView(Button(this).apply {
                text = "查看知识图谱"
                setOnClickListener { openGraph() }
            })
        }

        root.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
    }

    /** 按保留态重画。只刷静态区域和可见性；结果行的编辑框只在「有结果且还没建过」时建成一次，
     *  免得每次进度回调都重建输入框、把光标和用户输入冲掉。 */
    internal fun draw() {
        if (session.abandoned) return
        session.pendingToast?.let { m -> session.pendingToast = null; toast(m, long = true) }
        apiLine.text = endpointLabel()

        when (session.phase) {
            PHASE_RUNNING -> {
                statusView.visibility = android.view.View.VISIBLE
                statusView.text = progressText()
                cancelBtn.visibility = android.view.View.VISIBLE
                genBtn.isEnabled = false
            }
            PHASE_FAILED -> {
                statusView.visibility = android.view.View.VISIBLE
                statusView.text = session.error ?: "生成失败"
                cancelBtn.visibility = android.view.View.GONE
                genBtn.isEnabled = true
            }
            else -> {
                statusView.visibility = android.view.View.GONE
                cancelBtn.visibility = android.view.View.GONE
                genBtn.isEnabled = true
            }
        }

        // 读文件 / 生成进行中：来源相关控件全部禁用 —— 免得读入途中发起生成、把名字署错，或和正在跑的那轮打架
        val busy = session.loading || session.phase == PHASE_RUNNING
        genBtn.isEnabled = !busy
        pickBtn.isEnabled = !busy

        if (session.phase == PHASE_RESULTS) {
            if (!rowsBuilt) buildResults()
        } else if (rowsBuilt) {
            clearResults()
        }

        // 原文同步：只在「来源变了」时写回输入框（用户打字不会触发，避免光标被顶掉）
        if (session.sourceRev != shownSourceRev) {
            sourceField.setText(session.sourceText)
            sourceField.setSelection(sourceField.text?.length ?: 0)
            shownSourceRev = session.sourceRev
        }
        sourceInfo.text = session.sourceNote
    }

    private fun buildResults() {
        rowsBuilt = true
        rowsHost.removeAllViews()
        for ((idx, row) in session.rows.withIndex()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(CheckBox(this).apply {
                text = "包含第 ${idx + 1} 张"; isChecked = row.include
                setOnCheckedChangeListener { _, on -> row.include = on; updateCount() }
            })
            head.addView(Button(this).apply {
                text = "删除"; tag = Brut.DANGER
                setOnClickListener { session.rows.remove(row); buildResults(); updateCount() }
            })
            box.addView(head)
            box.addView(EditText(this).apply {
                hint = "牌组"; setText(row.deck); setSingleLine()
                addTextChangedListener(textWatcher { row.deck = it })
            })
            box.addView(EditText(this).apply {
                hint = "问题（正面）"; setText(row.front)
                addTextChangedListener(textWatcher { row.front = it })
            })
            box.addView(EditText(this).apply {
                hint = "答案（背面）"; setText(row.back); minLines = 2; gravity = Gravity.TOP
                addTextChangedListener(textWatcher { row.back = it })
            })
            val matched = row.excerpt.isNotEmpty() && session.genSource.contains(row.excerpt)
            box.addView(TextView(this).apply {
                // 只说明「这句摘录能在原文里找到」，不等于整张卡都对 —— 措辞别替用户下结论
                text = "原文出处（只读 · ${if (matched) "原文摘录已匹配，请核对问答" else "未在原文中找到该摘录，请自行核对"}）：${row.excerpt}"
                textSize = 12f; alpha = 0.8f; setPadding(0, dp(3), 0, 0)
            })
            box.addView(TextView(this).apply {
                text = "存储来源：${session.genFileName ?: "粘贴文本"}"
                textSize = 12f; alpha = 0.6f
            })
            val divider = android.view.View(this).apply {
                setBackgroundColor(0x22000000)
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6) }
            }
            box.addView(divider)
            rowsHost.addView(box)
        }
        Brut.style(rowsHost)
        resultsBox.visibility = android.view.View.VISIBLE
        updateCount()
    }

    private fun clearResults() {
        rowsBuilt = false
        rowsHost.removeAllViews()
        resultsBox.visibility = android.view.View.GONE
    }

    private fun updateCount() {
        val n = session.rows.count { it.include }
        importBtn.text = "确认导入 $n 张"
        importBtn.isEnabled = n > 0
    }

    // ---------------- 选文件 / 提取 ----------------

    private fun pickFile() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, MIME),
                REQ_FILE,
            )
        } catch (e: Exception) {
            toast("打不开文件选择器：" + (e.message ?: ""), long = true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        if (resultCode != RESULT_OK) return   // 用户取消：什么都不做
        val uri = data?.data ?: return
        loadFile(uri)
    }

    private fun loadFile(uri: Uri) {
        val name = displayName(uri) ?: "文件"
        // 冻住保留态引用：后台线程只用它（+ applicationContext），**不攥着 Activity** —— 旋转不泄漏旧页
        val s = session
        // 换文件前先把上一轮整体作废：迟到的读文件 / AI 回调都不许把结果糊到新文件上
        val my = ++s.loadId
        s.runId++
        s.rows.clear()
        s.genSource = ""
        s.genFileName = null
        s.error = null
        s.phase = PHASE_SOURCE
        s.loading = true
        if (rowsBuilt) clearResults()
        toast("正在读取 $name …")
        draw()
        val resolver = applicationContext.contentResolver
        thread(name = "lz-know-doc") {
            val result: Pair<String?, String?> = try {
                // 有界读取：最多读到上限 + 1 字节就停，超限直接判「太大」—— 不把整个大文件先读进内存再判
                val bytes = resolver.openInputStream(uri)?.use { readCapped(it, DocumentText.MAX_INPUT_BYTES) } ?: ByteArray(0)
                when {
                    bytes.size > DocumentText.MAX_INPUT_BYTES ->
                        null to "文件太大（超过 ${DocumentText.MAX_INPUT_BYTES / 1024 / 1024} MB），请拆分后再导入。"
                    bytes.isEmpty() -> null to "打不开这个文件，或它是空的（系统可能已经收回了授权）。"
                    else -> {
                        val text = DocumentText.extract(bytes, name)
                        if (text.isBlank()) null to "没从 $name 读到文字 —— 可能是扫描图片版 PDF、加密文档，或这个格式暂不支持。"
                        else text to null
                    }
                }
            } catch (e: SecurityException) {
                null to "没有读取这个文件的权限（授权可能已过期，重新选一次）。"
            } catch (e: Exception) {
                null to (e.message?.take(120) ?: "这个文件读不出文字。")
            }
            if (s.abandoned || s.loadId != my) return@thread   // 页面已退出 / 又选了别的文件：这次结果作废
            val (text, err) = result
            if (err != null) {
                s.pendingToast = err
            } else {
                val t = text.orEmpty().trim()
                s.sourceText = t
                s.fileName = name          // 真的读到内容才认这个文件名，免得「读失败却署了名」
                s.sourceNote = "文件：$name · 提取 ${t.length} 字（生成前可继续编辑）"
                s.sourceRev++
                s.pendingToast = "已提取 ${t.length} 字，请检查后再生成"
            }
            s.loading = false
            s.notifyHost()   // 交给当前前台页重画（旋转 / 切后台都安全）
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (_: Exception) { null }

    // ---------------- AI 生成（唯一一处调用接口） ----------------

    private fun onGenerate() {
        val text = session.sourceText.trim()
        if (text.isEmpty()) { toast("先选文件或粘贴要生成卡片的原文"); return }
        val api = prefs.api()
        if (api.base.isBlank()) { toast("还没填接口地址（设置 → 接口地址）", long = true); return }

        // 冻住这一轮的原文 + 来源名（核对摘录 / 落库署名都认这一份），清掉上一轮结果
        val s = session
        s.genSource = text
        s.genFileName = s.fileName?.takeIf { it.isNotBlank() } ?: "粘贴文本"
        s.rows.clear()
        s.error = null
        s.progress = 0 to 0
        s.cancel = Cancel()
        s.phase = PHASE_RUNNING
        val my = ++s.runId
        val cancel = s.cancel
        val sourceName = s.genFileName ?: "粘贴文本"
        if (rowsBuilt) clearResults()
        draw()

        // 后台线程只认保留态 s（不攥 Activity）；旋转后由新页重新 attach 收结果
        thread(name = "lz-know-ai") {
            try {
                val drafts = KnowledgeAiImport.generate(text, sourceName, api, cancel) { done, total ->
                    if (s.runId == my && !s.abandoned) {
                        s.progress = done to total
                        s.notifyHost()
                    }
                }
                if (s.runId != my || s.abandoned) return@thread
                if (cancel.get()) { s.phase = PHASE_SOURCE; return@thread }
                when {
                    drafts.isEmpty() -> {
                        s.phase = PHASE_FAILED
                        s.error = "AI 没生成可用的卡片 —— 换一段更完整的原文，或稍后再试。"
                    }
                    // A 冻结契约保证 front/back 非空且不超长；真出现空就按失败处理，绝不静默丢卡
                    drafts.any { it.front.isBlank() || it.back.isBlank() } -> {
                        s.phase = PHASE_FAILED
                        s.error = "AI 返回的卡片不完整，已放弃这次结果（没有写入）。"
                    }
                    else -> {
                        drafts.forEach { s.rows.add(Row(it.deck, it.front, it.back, it.excerpt, true)) }
                        s.phase = PHASE_RESULTS
                    }
                }
            } catch (e: Exception) {
                if (s.runId != my || s.abandoned) return@thread
                if (cancel.get()) s.phase = PHASE_SOURCE
                else { s.phase = PHASE_FAILED; s.error = NetErrors.friendly(e, api.base) }
            } finally {
                s.notifyHost()
            }
        }
    }

    private fun cancelRun() {
        // 取消这一轮生成，也顺手作废在飞的读文件回调：取消之后不许再有东西悄悄落地
        session.loadId++
        if (session.phase != PHASE_RUNNING) return
        session.cancel.abort()
        statusView.visibility = android.view.View.VISIBLE
        statusView.text = "正在取消…"
        toast("已取消生成")
    }

    // ---------------- 本地结构化（不调模型）----------------

    /**
     * 纯文本结构化：本地规则切章节 / 抽知识点 / 出建议卡，**不发任何请求**。
     *
     * 为什么单独一个入口而不是直接顶替「AI 生成」：两者是互补的。
     * AI 出题质量高但要联网付费；本地规则离线零成本，代价是只能认出标题 / 编号 / 定义句这类结构化资料。
     * 所以把它放在「生成」之外、产物汇入同一个 rows —— 用户想用哪个用哪个，也能两边各抽一轮再一起勾选。
     *
     * 抽取不到东西时**明确说没抽到**，不静默返回空列表让用户以为按钮坏了。
     */
    private fun onStructure() {
        val text = session.sourceText.trim()
        if (text.isEmpty()) { toast("先选文件或粘贴要拆分的原文"); return }
        val s = session
        // 冻结这一轮的原文与来源名：和 AI 那轮同一套口径，落库署名不会串
        s.genSource = text
        s.genFileName = s.fileName?.takeIf { it.isNotBlank() } ?: "粘贴文本"
        val name = s.genFileName ?: "粘贴文本"
        val result = MaterialIngest.ingest(text, name)

        if (result.isEmpty) {
            structureInfo.text = "没抽到知识点：" + result.summary + "（资料没有标题 / 编号 / 定义句时，用上面的 AI 生成更合适）"
            toast("这段资料里没找到可结构化的内容", long = true)
            return
        }

        // 与 AI 那轮同样的落点：清掉上一轮结果，把建议卡装进 rows（默认全勾）
        s.rows.clear()
        s.error = null
        for (c in result.cards) {
            // 摘录只读：卡片 origin 里已经带了「文件名 · 原文摘录」，这里摘掉署名作为行内摘录展示
            val excerpt = c.origin.removePrefix(name + " · ")
            s.rows.add(Row(c.deck, c.front, c.back, excerpt, true))
        }
        s.phase = if (s.rows.isEmpty()) PHASE_FAILED else PHASE_RESULTS
        if (s.rows.isEmpty()) s.error = "结构拆出来了，但没形成可导入的卡片 —— 换一段更完整的资料试试。"

        // 可核对性写进界面：告诉用户有多少条摘录真的能在原文里定位到，而不是只报个总数
        val hit = result.points.count { text.contains(it.origin) }
        structureInfo.text = result.summary + " · 摘录可在原文核对 " + hit + "/" + result.points.size +
            "\n本机规则拆分，没有联网、没有消耗接口。下面可以逐张核对 / 编辑 / 勾选。"
        buildResults()
        draw()
    }
    /**
     * 打开知识图谱。
     *
     * 图上的节点来自 [MaterialIngest] 的 points：同一段原文，用户在导入页看到的「知识点清单」和图谱里
     * 的节点是同一份数据，不是两套抽取 —— 否则两边数字对不上，用户没法信任任何一个。
     *
     * 还没结构化过的资料这里现拆一次（纯本地、毫秒级、不花钱），顺便在按钮上方给出「抽到了几个知识点」，
     * 让用户在跳转前就知道图谱里会不会是空的。
     */
    private fun openGraph() {
        // 模块被关掉时这里直接拒：入口已经藏了（见 buildChrome 里那一行），但旧任务栈、
        // 旋转恢复都可能还留着一个已经建好的按钮，点下去不该打开一个用户明说不要的页面。
        // 检查放在最前面 —— 关掉的模块连「结构化」那一次本地拆分都不该白跑。
        if (!Features.isEnabled(this, "graph")) {
            toast("知识图谱已在「模块管理」里关掉 —— 设置 → 模块管理 可以重新打开", long = true)
            return
        }
        val text = session.sourceText
        val points = MaterialIngest.ingest(text, session.fileName.orEmpty()).points
        if (points.isEmpty()) {
            toast("先点上面的「结构化这段资料」，或换一份带标题 / 定义句的资料", long = true)
            return
        }
        // 走 [Features.open] 而不是 startActivity：开关只在 registry 那一处判，
        // 它还会吞掉 ActivityNotFoundException 返回 false（图谱页没注册时不再带崩导入页），
        // 这里只负责把那句人话显示出来。导卡才是主流程，图谱只是可选的查看入口。
        // 图谱页自己从知识卡库建图（[GraphActivity] 里 graph = KnowledgeGraph.build(cards)），
        // 这里不传节点：传一份"预览用"的节点会让图和库里真实的卡对不上 ——
        // 用户在图上点一个节点、跳到复习却找不到那张卡，比不给他看更糟。
        if (!Features.open(this, "graph")) toast("图谱功能准备中：暂时打不开", long = true)
    }
    // ---------------- 确认落库 ----------------

    private fun commit() {
        val included = session.rows.filter { it.include }
        if (included.isEmpty()) { toast("没有勾选任何卡片"); return }
        if (included.any { it.front.isBlank() || it.back.isBlank() }) {
            toast("有卡片的问题或答案是空的 —— 补上或取消勾选后再导入", long = true)
            return
        }
        val name = session.genFileName ?: "粘贴文本"   // 用生成时冻结的来源名，之后改原文 / 换文件都不影响署名
        val now = System.currentTimeMillis()
        val cards = included.map { r ->
            KnowledgeCard.KCData(
                id = 0L,
                deck = r.deck.trim().ifBlank { "默认" },
                front = r.front.trim(),
                back = r.back.trim(),
                source = KnowledgeCard.Source.IMPORT.code,
                createdAt = now,
                fsrs = Fsrs.Card(),
                // origin = 原始文件名 + 该卡在原文里出现的摘录（可核对）
                origin = "$name · ${r.excerpt}",
            )
        }
        val res = store.addBatch(cards)

        val parts = ArrayList<String>()
        parts.add("导入 ${res.added} 张")
        if (res.duplicates > 0) parts.add("重复跳过 ${res.duplicates} 张")
        if (res.full > 0) parts.add("卡库已满，${res.full} 张未导入")
        toast(parts.joinToString(" · "), long = true)

        // 回来源态，方便接着导下一份；原文与来源说明保留
        session.rows.clear()
        session.phase = PHASE_SOURCE
        session.sourceNote = ""
        if (rowsBuilt) clearResults()
        draw()

        if (res.added > 0) promptEnable(res)
    }

    /** 保存成功后的**显式**开启询问：只有用户点「开启」才写设置并排闹钟，绝不静默打开。 */
    private fun promptEnable(res: KnowledgeCard.ImportResult) {
        val decks = res.decks.filter { it.isNotBlank() }.sorted()
        if (decks.isEmpty()) return
        ThemeDialogs.builder(this)
            .setTitle("开启知识卡弹出复习？")
            .setMessage(
                "以上牌组已保存：${decks.joinToString("、")}。\n\n" +
                    "开启后，这些牌组会按设定的间隔在悬浮小球上弹出复习卡。现在开启吗？\n" +
                    "（点「暂不开启」也照样保存，之后可在「学习与复习」里随时开启。）"
            )
            .setPositiveButton("开启") { _, _ ->
                prefs.knowledgePopupDecks = prefs.knowledgePopupDecks + decks
                prefs.knowledgePopupOn = true
                KnowledgeAlarm.schedule(this)
                toast("已开启知识卡弹出")
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }

    // ---------------- 小工具 ----------------

    private fun endpointLabel(): String {
        val api = prefs.api()
        if (api.base.isBlank()) return "⚠ 还没填接口地址 —— 去「设置 → 接口地址」填一套才能生成。"
        val host = try { java.net.URI(api.base).host ?: api.base } catch (_: Exception) { api.base }
        return "发送目标：${api.model.ifBlank { "（未填模型）" }} @ $host\n" +
            "只把上面的原文发给这一个接口；接口 Key 不显示、不写日志。"
    }

    private fun progressText(): String {
        val (done, total) = session.progress
        return if (total > 0) "正在生成问答卡…（第 $done / $total 段）" else "正在生成问答卡…"
    }

    private fun textWatcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onText(s?.toString().orEmpty()) }
    }

    private fun sectionTitle(text: String) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun label(text: String, size: Float = 14f) {
        root.addView(TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)) })
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun dp(n: Int) = Ui.dp(this, n.toFloat())
}
