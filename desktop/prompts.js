/* v1.60（对齐安卓 Prompts.kt / Terms.kt v1.66）：追问开关 what / why + 识别关键词（名词词块）。
 * 主进程（require）和答案面板（<script src>）共用一份，test/ 里直接单测。
 *
 * 识别关键词：v1.65 是单独的「通识」模式；v1.60 桌面版起（同安卓）通识模式取消，
 * 改成所有模式的回答末尾都带一行名词清单（设置 →「识别关键词」开关，默认开），点词块 = 追问这个词。 */
(function () {
  /** 名词清单行的固定前缀（面板靠它把清单拆成可点的词块；改了要同步安卓 Prompts.TERMS_HEAD） */
  const TERMS_HEAD = '[可点击名词]'

  /** 拼在各模式系统提示词末尾的关键词要求。型号 / 编号 / 品牌 / 规格优先 —— 这些最常被模型当成「不算名词」漏掉 */
  const TERMS_RULE = '回答写完后，另起一行（前后留空行），严格按这个格式列出画面和回答里值得了解的关键词（3~8 个，按重要性排）：\n'
    + TERMS_HEAD + ' 关键词1｜关键词2｜关键词3\n'
    + '关键词要求：\n'
    + '- 优先列出专有名词：产品型号、编号、品牌、技术规格和标准（例如 R7250、P3 广色域、Wi-Fi 7、LPDDR5X），这类词原样照抄，大小写、数字、连字符都不要改；\n'
    + '- 其次才是术语、概念、缩写、生僻词；必须是画面或回答里真实出现的词，不要编造、不要放人人都懂的词；\n'
    + '- 每个不超过 10 个字（英文最多 2 个单词）；\n'
    + '- 这行清单必须是整个输出的最后一行，格式一个字都不能差；做题模式也一样放在最后。'

  /** 通用讲法：通用 / 人格 / 追问共用（对齐 v1.7x「由浅入深 + 分清事实与假设 + 点出边界」）。
   *  只讲风格与结构 —— 不规定按标点断句，也不要求模型输出内部思考过程（2026-10-15 增补：证据不足就直说不知道；新术语先一句大白话定义再给贴切类比并点明类比边界；不套机械标题与字数、不输出内心推理）。 */
  const STYLE = '讲法（一律照做）：\n'
    + '- 先给结论，再给依据；能一句话说清的先说清。\n'
    + '- 由浅入深：先用大白话或一个贴切的例子点出它是什么，再讲背后的机制 —— 哪些是事实、哪些只是假设或约定，分开说清。\n'
    + '- 碰到新术语，先用一句大白话说清它是什么，再给一个贴切、不会带偏的类比，并点明这个类比在哪一层不成立；不必给每个词都硬造例子。\n'
    + '- 需要时用第一性原理：先把最基本的定义、事实和假设摆出来，再一步步推出结论，不跳步；凡只是约定或经验的结论都明说；最后点出有用的边界（什么时候成立、什么时候不成立）。\n'
    + '- 证据不足就直接说不知道、还缺哪一项，不把猜测当事实，也不编造引用或出处。\n'
    + '- 不写开场白和收尾套话，不额外套机械的小标题，也不设字数硬限，不输出内心推理过程。\n'
    + '- 句子短、段落短、信息密度高；一个观点、一层逻辑、一个转折或一个重要判断，都可以另起一段。按语义、节奏和视觉可读性分段，积极换行、避免文字墙，但不要机械地一句一段。'

  /** 提示词身份：内容一改就递增，主进程把它并进翻译缓存键，让旧提示词产出的缓存失效（与发布版本无关） */
  const PROMPT_VERSION = 'tr-4'

  /** 追问栏 what / why 开关（同安卓 Prompts.ASK_WHAT / ASK_WHY） */
  const ASK_WHAT = '【what：讲简单点，再举个例子】换一种更好懂的讲法，像万维钢写专栏那样解释：'
    + '先用一两句大白话说清它到底是什么、关键在哪；再给一个贴切的类比或生活里的具体例子，让人一下子「看见」它；'
    + '再讲清它背后的机制（哪些是事实、哪些只是假设），最后点出一个有洞见的要点 —— 大多数人没想到、但一点就通的那一层，并说清它什么时候适用、什么时候不适用。'
    + '要求：通俗但不失准确；类比要贴切，不牵强；就事论事，不要硬往某个理论框架、学科体系或「××思维」上靠；'
    + '不堆术语，非用不可的术语先用一句话解释。'
  const ASK_WHY = '【why：问为什么，用第一性原理】先列出这件事依赖的最基本的事实、定义或假设（不能再拆的那几条），'
    + '再从它们出发一步一步推出结论，每一步都说清依据，不跳步，不用「规定如此」「大家都这么说」来代替推理；'
    + '如果某个前提本身只是约定或经验结论，要明说；最后用一句话串起整条因果链，并点出它成立的条件与边界。'
  /** 开关开着、输入框空着直接发送时，代替问题的默认问法 */
  const ASK_WHAT_Q = '讲简单点，举个例子'
  const ASK_WHY_Q = '为什么？'

  function defaultQ(what, why) { return [what ? ASK_WHAT_Q : null, why ? ASK_WHY_Q : null].filter(Boolean).join(' ') }
  /** 真正发给模型的追问；没问题、开关也全关 → ''（不发） */
  function styledAsk(question, what, why) {
    const q = String(question || '').trim() || defaultQ(what, why)
    if (!q) return ''
    const rules = [what ? ASK_WHAT : null, why ? ASK_WHY : null].filter(Boolean)
    return rules.length ? q + '\n\n回答方式：\n' + rules.join('\n') : q
  }
  /** 面板上「追问：…」显示的那句：开了开关就在前面标出来，长提示词不往面板上贴 */
  function askShown(question, what, why) {
    const q = String(question || '').trim() || defaultQ(what, why)
    const tags = [what ? 'what' : null, why ? 'why' : null].filter(Boolean)
    return tags.length ? '［' + tags.join('·') + '］' + q : q
  }

  /** 去掉模型偶尔加的编号前缀（「1. 」「2、」「3)」），但不能伤到以数字开头的型号 / 规格（3.5mm、802.11ax、5G） */
  function stripIndex(t) {
    return t.replace(/^\d{1,2}(?:[、)）]\s*|[.．]\s+|[.．](?=[一-龥]))/, '').trim()
  }
  /** 纯英文长词组只留第一个词（同安卓 Terms.shortenEnglish）；带数字 / 中文的（Wi-Fi 7、P3 广色域）不动 */
  function shortenEnglish(t) {
    return (t.length > 10 && t.includes(' ') && /^[A-Za-z][A-Za-z' -]*$/.test(t)) ? t.split(' ')[0] : t
  }
  /** 占位符 / 说明词：模型有时会把提示词里的样例「关键词1｜关键词2」照抄回来 —— 不算真词 */
  function isPlaceholderTerm(t) { return /^(关键词|词|term)\s*\d*$/i.test(String(t || '').trim()) }
  /** 从回答里拆出名词清单行 → { text: 剥掉清单行的正文, terms }；没有清单原样返回。
   *  含清单标记的整行一律不进正文 —— 那行就是元数据（哪怕是空清单或占位符），不该露在回答里。
   *  清单行可能不止一行（追问的回答偶尔也带）：全部剥掉，词按出现顺序合并去重 */
  function splitTerms(src) {
    src = String(src || '')
    if (src.indexOf(TERMS_HEAD) < 0) return { text: src, terms: [] }
    const keep = [], all = []
    for (const line of src.split('\n')) {
      const k = line.indexOf(TERMS_HEAD)
      if (k < 0) { keep.push(line); continue }
      const got = line.slice(k + TERMS_HEAD.length).split(/[｜|、]/)
        .map((s) => shortenEnglish(stripIndex(s.trim())))
        .filter((t) => t && t.length <= 20 && !/^\d{1,2}$/.test(t) && !isPlaceholderTerm(t))   // 「2、极限」按顿号拆开后剩下的孤零零序号不算词（R7250、5G 这类照留）
      all.push(...got)
    }
    const terms = all.filter((t, idx, a) => a.indexOf(t) === idx).slice(0, 10)
    return { text: keep.join('\n').trimEnd(), terms }
  }

  /** 流式渲染用：清单行（连同它后面的一律）先挡在正文外 —— 完整行、只写出一半的头（「[可点」）都算。
   *  流式期间模型可能正把「[可点击名词] …」吐到最后一行，不挡就会在面板上闪出一行原始格式说明；
   *  done 后 renderBody 用 splitTerms 再收一次尾。 */
  function isTermsMetaLine(t) {
    t = String(t || '')
    if (!t) return false
    if (t.indexOf(TERMS_HEAD) >= 0) return true
    if (t.length >= 2 && TERMS_HEAD.indexOf(t) === 0) return true   // 头的一部分：[可 / [可点 / [可点击…
    return t.indexOf('关键词要求') === 0
  }
  function stripTermsLive(src) {
    src = String(src || '')
    const lines = src.split('\n')
    for (let i = 0; i < lines.length; i++) {
      if (isTermsMetaLine(lines[i].trim())) return lines.slice(0, i).join('\n')
    }
    return src
  }

  /* ---------------- 翻译（v1.7x 富输出） ----------------
   * 译文永远在最前；后面按输入类型给「够用」的讲解：单词给音标 / 词性 / 词形 / 义项 / 搭配 / 辨析 / ≥2 个例句，
   * 短语给结构 / 语域 / 搭配变体 / ≥2 个例句，句子段落给完整译文 + 真正有用的几处讲解。
   * 旧的「一行（单词）/ 一句话（句子）」上限删掉 —— 讲解要给够，但也不做逐词词典。 */
  /** 按输入判断是单词 / 短语 / 句子段落 */
  function classifySource(text) {
    const s = String(text || '').trim()
    if (!s) return 'text'
    const words = s.split(/\s+/).filter(Boolean)
    const cjk = /[\u3400-\u9fff]/.test(s)
    if (/[.!?。！？…]/.test(s)) return 'text'                        // 有句子标点 → 句子/段落
    if (!cjk && words.length >= 6) return 'text'                     // 英文单词数够多 → 句子
    if (cjk && s.length > 14) return 'text'                          // 中文够长 → 句子
    return words.length <= 1 ? 'word' : 'phrase'
  }

  /** 圆圈序号，按条目数取用 */
  const NUM = '①②③④⑤⑥⑦⑧⑨⑩'
  function numbered(items) { return items.map((s, i) => NUM[i] + ' ' + s).join('\n') }

  /* 「翻译 + 讲解」和「只补讲解」共用同一批条目：单体机器翻译（DeepL / 百度）时译文已经给好，
   * AI 只补讲解（enrichPrompt 走 explainRule），绝不重复、也不改写译文。
   * 例句一律「原语言 —— 目标语言」；目标语言以「翻译方向」为准，不写死中文（译成英文时写「中文」就是错的）。 */
  const WORD_TRANS = '译文：最贴切的一到两个目标语言说法（不要重复原词）。'
  const WORD_EXPLAIN_ITEMS = [
    '音标：原文是英文等拼音文字时给国际音标（用 /…/ 包住）；中文可按需给拼音，日文、韩文这类不给音标。',
    '词性与词形：词性缩写加中文（如 n. 名词 / v. 动词）；有复数、过去式、比较级等变化就列出（如 go → went, gone）。',
    '常见义项：这类语境下常用的 2~4 个意思，每个简短，不要把词典里的义项全抄来。',
    '常见搭配：2~4 个高频搭配（原语言 + 目标语言，如 make up one’s mind 下定决心）。',
    '易混辨析：最容易混的近义词，一句话说清区别；没有合适的就省掉这一点。',
    '例句：必须给满两句，每句一行，写成「原语言 —— 目标语言」；句子要短、贴近日常，能体现上面的意思或搭配。'
  ]
  const WORD_RULE = '要处理的是一个单词。严格按下面顺序写，每个小点一行，不要写成一大段，也不要面面俱到：\n'
    + numbered([WORD_TRANS].concat(WORD_EXPLAIN_ITEMS)) + '\n'
    + '没有的小点（如没有词形变化、没有易混词）就省掉，但译文和两个例句必须有。'
  const WORD_EXPLAIN = '要处理的是一个单词。译文已在上方给出，你只补充讲解，不要重复、也不要改写译文。按下面顺序写，每个小点一行，不要写成一大段，也不要面面俱到：\n'
    + numbered(WORD_EXPLAIN_ITEMS) + '\n'
    + '没有的小点（如没有词形变化、没有易混词）就省掉，但两个例句必须有。'

  const PHRASE_TRANS = '译文：整体的自然说法。'
  const PHRASE_EXPLAIN_ITEMS = [
    '结构：这是什么结构、在句子里当什么成分（一句说清）。',
    '语域：口语还是书面、正式还是随意、有没有感情色彩。',
    '搭配与变体：常见搭配、可替换的词、相关的固定说法（有才写）。',
    '例句：必须给满两句，每句一行，写成「原语言 —— 目标语言」，体现它怎么用。'
  ]
  const PHRASE_RULE = '要处理的是一个短语或固定说法。按下面顺序写，每点一行：\n'
    + numbered([PHRASE_TRANS].concat(PHRASE_EXPLAIN_ITEMS)) + '\n'
    + '说清即可，别堆词典条目。'
  const PHRASE_EXPLAIN = '要处理的是一个短语或固定说法。译文已在上方给出，你只补充讲解，不要重复、也不要改写译文。按下面顺序写，每点一行：\n'
    + numbered(PHRASE_EXPLAIN_ITEMS) + '\n'
    + '说清即可，别堆词典条目。'

  const TEXT_POINTS = '写一段「## 要点」：挑真正影响理解的 2~5 处来讲 —— 关键词语境义与词性、搭配、句式的处理、语气或言外之意、容易误译的地方。每处一行，先点出是哪个词或哪一处，再一句话说明。'
  const TEXT_TAIL = '只讲对这句真正有用的，不做逐词词典（一个词的常见义项不必全列）；确实没有值得讲的就不硬凑，不写也行。\n短句、短段、信息密；一处一个新意思就另起一行。'
  const TEXT_RULE = '要处理的是一句话或一段话。按下面顺序写：\n'
    + numbered(['先给完整译文（不要重复原文）。', '再' + TEXT_POINTS]) + '\n' + TEXT_TAIL
  const TEXT_EXPLAIN = '要处理的是一句话或一段话。译文已在上方给出，你只补充讲解，不要重复、也不要改写译文。按下面顺序写：\n'
    + numbered([TEXT_POINTS]) + '\n' + TEXT_TAIL

  function entryRule(kind) { return kind === 'word' ? WORD_RULE : kind === 'phrase' ? PHRASE_RULE : TEXT_RULE }
  function explainRule(kind) { return kind === 'word' ? WORD_EXPLAIN : kind === 'phrase' ? PHRASE_EXPLAIN : TEXT_EXPLAIN }

  /** 划词翻译提示词：语言方向 + 按类型的讲解要求 */
  function translatePrompt(text, langRule, kind) {
    const k = kind || classifySource(text)
    return '你是专业翻译，也能把语言讲到点上。翻译方向：' + langRule + '。\n'
      + entryRule(k) + '\n'
      + STYLE + '\n'
      + '直接输出，不要寒暄，不要写「译文：」这类前缀。\n\n原文：\n' + text
  }
  /** 单体机器翻译之后的「AI 讲解」提示词：机器译文就是最终译文，AI 只补讲解，不重复 / 不改写译文
   *  （对齐移动端：DeepL / 百度 单选时保留原机器译文，输出 = 机器译文 + 讲解，不出现第二份译文）。 */
  function enrichPrompt(text, langRule, mt, engineLabel) {
    const k = classifySource(text)
    const who = engineLabel ? (engineLabel + ' 的机器译文') : '机器译文'
    return '下面是原文，以及' + who + '。这份机器译文就是最终译文，请照原样保留、不要再输出或改写译文，只补一份「讲解」。翻译方向：' + langRule + '。\n'
      + explainRule(k) + '\n'
      + STYLE + '\n'
      + '只写讲解，不要重复原文或译文；直接输出，不要寒暄，不要写「译文：」这类前缀。\n\n原文：\n' + text + '\n\n' + who + '：\n' + mt
  }
  /** 点词块时发的追问 */
  function termQuestion(term) { return '「' + term + '」是什么意思？用大白话两三句话解释，再举一个贴近日常的小例子。' }

  const api = { TERMS_HEAD, TERMS_RULE, STYLE, PROMPT_VERSION, ASK_WHAT, ASK_WHY, ASK_WHAT_Q, ASK_WHY_Q, styledAsk, askShown, splitTerms, stripIndex, shortenEnglish, termQuestion,
    isPlaceholderTerm, isTermsMetaLine, stripTermsLive, classifySource, translatePrompt, enrichPrompt }
  if (typeof window !== 'undefined') window.LZPrompts = api
  if (typeof module !== 'undefined') module.exports = api
})()
