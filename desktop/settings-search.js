/* settings-search.js — v1.63 设置搜索的目录与解析（对齐 Android 2.6.1，桌面独立线）。
 *
 * 这个模块只做「纯逻辑」，不碰 DOM、不发网络请求：
 *   - catalog：桌面上**真实存在**的设置项白名单（title / 分组 path / 页面上的 targetId）。
 *   - search(query)：本机关键词 / 别名匹配，只返回 catalog 里的条目。
 *   - aliases(id)：某个条目额外认的说法（「字太小」「换颜色」「同步手机」「DeepL」…）。
 *   - parseIds(text)：把模型回传的文本解析成白名单内的 id（去重、有序、有界）。
 *   - buildPrompt(query)：主进程拼发给模型的提示词（只含 query + 目录标题路径 + 允许的 id）。
 *
 * 目录只登记设置页**确实有控件**的项：桌面没有的功能（比如 Android 独有设置）一律不写进来，
 * 搜索命中后也绝不编造入口。targetId 必须对应 settings.html 里真实的 id 或其父级分组容器。
 *
 * UMD：Node 下 module.exports，浏览器下挂 window.LingSettingsSearch。
 */
(function (root, factory) {
  const api = factory()
  if (typeof module === 'object' && module.exports) module.exports = api
  if (typeof window !== 'undefined') window.LingSettingsSearch = api
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict'

  const GROUP_API = '接口'
  const GROUP_APPEAR = '外观'
  const GROUP_SYNC = '同步'
  const GROUP_TRANS = '翻译'
  const GROUP_MODE = '自定义模式'
  const GROUP_CACHE = '缓存'
  const GROUP_HIST = '历史'
  const GROUP_OTHER = '其他'
  const GROUP_MCP = '外部 Agent'
  const GROUP_LINK = '手机常连'
  const GROUP_VOICE = '实时转写'
  const GROUP_FOCUS = '专注与陪伴'

  /* advanced: true = 在默认折叠的分组里（v158 / 其他设置）。点了会自动展开再定位。 */
  const catalog = [
    // —— API 设置 ——
    { id: 'api.version', title: '版本更新 · 检查更新', path: GROUP_API + '/版本更新', targetId: 'updCheck' },
    { id: 'api.version.download', title: '下载最新版', path: GROUP_API + '/版本更新', targetId: 'updDownload' },
    { id: 'api.providers', title: '服务商预设（小米 MiMo / DeepSeek / 硅基流动 / 智谱 / OpenAI 兼容）', path: GROUP_API + '/服务商预设', targetId: 'presets' },
    { id: 'api.baseUrl', title: '接口地址（Base URL）', path: GROUP_API + '/接口地址', targetId: 'baseUrl' },
    { id: 'api.key', title: 'API Key', path: GROUP_API + '/API Key', targetId: 'apiKey' },
    { id: 'api.keyLink', title: '去申请 API Key', path: GROUP_API + '/API Key', targetId: 'keyLink' },
    { id: 'api.model', title: '模型', path: GROUP_API + '/模型', targetId: 'model' },
    { id: 'api.fetch', title: '获取模型列表', path: GROUP_API + '/模型', targetId: 'fetch' },
    { id: 'api.test', title: '检测连接（能不能收图片）', path: GROUP_API + '/模型', targetId: 'test' },
    { id: 'api.thinking', title: '思考强度（关 / 低 / 高 / 最高）', path: GROUP_API + '/思考强度', targetId: 'think' },
    { id: 'api.detail', title: '做题解析强度（简略 / 标准 / 详细）', path: GROUP_API + '/做题解析', targetId: 'detail' },
    { id: 'api.novice', title: '纯小白讲解', path: GROUP_API + '/做题解析', targetId: 'detail' },
    { id: 'api.profiles', title: 'API 档案（切换 / 删除）', path: GROUP_API + '/API 档案', targetId: 'profiles' },
    { id: 'api.saveas', title: '存为档案', path: GROUP_API + '/API 档案', targetId: 'saveas' },
    { id: 'api.profileName', title: '档案名（存为档案时填写）', path: GROUP_API + '/API 档案', targetId: 'profileName' },

    // —— 外观 ——
    { id: 'look.keywords', title: '识别关键词（回答下面的关键词词块）', path: GROUP_APPEAR + '/识别关键词', targetId: 'kwSw' },
    { id: 'look.theme', title: '外观配色（护眼 + 青春配色）', path: GROUP_APPEAR + '/外观配色', targetId: 'themeCards' },
    { id: 'look.side', title: '结果面板位置（屏幕左边 / 跟着小球）', path: GROUP_APPEAR + '/结果面板位置', targetId: 'sideSw' },
    { id: 'look.fontSize', title: '正文字号（结果面板里的字大小）', path: GROUP_APPEAR + '/正文字号', targetId: 'fontSize' },

    // —— 专注守护 + 陪伴（v1.76）——
    { id: 'focus.duration', title: '专注时长（5 / 25 / 45 / 90 分钟）', path: GROUP_FOCUS + '/专注守护', targetId: 'focusDur' },
    { id: 'focus.away', title: '分心阈值（离开前台应用多久提醒一次：10 / 20 / 30 秒）', path: GROUP_FOCUS + '/专注守护', targetId: 'focusAway' },
    { id: 'companion.on', title: '陪伴（球旁短句：开关 / 频率 / AI 陪伴短句）', path: GROUP_FOCUS + '/陪伴', targetId: 'compSw' },

    // —— 局域网同步 ——
    { id: 'sync.start', title: '局域网同步 · 开始同步', path: GROUP_SYNC + '/局域网同步', targetId: 'lanStart' },
    { id: 'sync.stop', title: '局域网同步 · 停止同步', path: GROUP_SYNC + '/局域网同步', targetId: 'lanStop' },
    { id: 'sync.conn', title: '连接串 / 显示二维码（手机粘贴连接串）', path: GROUP_SYNC + '/连接串', targetId: 'lanBox' },
    { id: 'sync.lastStats', title: '上次同步统计', path: GROUP_SYNC + '/上次同步', targetId: 'lanStats' },
    { id: 'sync.conflicts', title: '同步冲突（保留主记录 / 保留副本 / 两条都留）', path: GROUP_SYNC + '/同步冲突', targetId: 'lanConflicts' },

    // —— 外部 Agent（MCP）+ 手机常连（v1.64，各自一个默认折叠分组）——
    { id: 'mcp.toggle', title: '外部 Agent（MCP）· 展开分组', path: GROUP_MCP + '/外部 Agent', targetId: 'mcpBox' },
    { id: 'mcp.start', title: 'MCP 服务 · 开启', path: GROUP_MCP + '/MCP 服务', targetId: 'mcpStart' },
    { id: 'mcp.stop', title: 'MCP 服务 · 关闭', path: GROUP_MCP + '/MCP 服务', targetId: 'mcpStop' },
    { id: 'mcp.port', title: 'MCP 端口（被占用就改这里，不会自动换端口）', path: GROUP_MCP + '/MCP 端口', targetId: 'mcpPort' },
    { id: 'mcp.client', title: '客户端令牌 · 签发（默认只读；写入 / 操作 / 私人记忆要勾选）', path: GROUP_MCP + '/客户端令牌', targetId: 'mcpClientAdd' },
    { id: 'mcp.clients', title: '已签发客户端（撤销 / 撤销它写入的内容）', path: GROUP_MCP + '/客户端令牌', targetId: 'mcpClients' },
    { id: 'mcp.snippet', title: '配置片段（token 用环境变量占位）', path: GROUP_MCP + '/配置片段', targetId: 'mcpSnippetBox' },
    { id: 'mcp.roots', title: '可打开的文件目录（白名单根目录）', path: GROUP_MCP + '/可打开的文件目录', targetId: 'mcpRoots' },
    { id: 'mcp.roots.add', title: '添加可打开的目录', path: GROUP_MCP + '/可打开的文件目录', targetId: 'mcpRootsAdd' },
    { id: 'mcp.handouts', title: '讲义文件夹（打开讲义所在目录）', path: GROUP_MCP + '/讲义文件夹', targetId: 'mcpOpenHandouts' },
    { id: 'mcp.audit', title: '调用审计（查看 / 复制 / 导出）', path: GROUP_MCP + '/调用审计', targetId: 'mcpAuditList' },
    { id: 'mcp.audit.export', title: '导出审计', path: GROUP_MCP + '/调用审计', targetId: 'mcpAuditExport' },
    { id: 'link.toggle', title: '手机常连（持久配对）· 展开分组', path: GROUP_LINK + '/手机常连', targetId: 'linkBox' },
    { id: 'link.start', title: '手机常连 · 开启', path: GROUP_LINK + '/常连服务', targetId: 'linkStart' },
    { id: 'link.stop', title: '手机常连 · 关闭', path: GROUP_LINK + '/常连服务', targetId: 'linkStop' },
    { id: 'link.port', title: '手机常连端口（被占用不自动换）', path: GROUP_LINK + '/常连端口', targetId: 'linkPort' },
    { id: 'link.pair', title: '生成配对 / 二维码（手机扫码连接）', path: GROUP_LINK + '/配对', targetId: 'linkPairAdd' },
    { id: 'link.pairs', title: '已配对手机（撤销配对）', path: GROUP_LINK + '/已配对', targetId: 'linkPairs' },

    // —— 实时转写（v1.75：语音 → 文字，豆包 / 阿里云各一把 Key）——
    { id: 'voice.toggle', title: '实时转写（语音转文字）· 展开分组', path: GROUP_VOICE + '/实时转写', targetId: 'voiceBox' },
    { id: 'voice.provider', title: '转写服务商（豆包 / 阿里云），显式切换、不自动顶替', path: GROUP_VOICE + '/服务商', targetId: 'voiceProviders' },
    { id: 'voice.key', title: '转写 API Key（两家分开加密保存）', path: GROUP_VOICE + '/API Key', targetId: 'voiceKey' },
    { id: 'voice.keySave', title: '保存转写 Key（系统钥匙串加密）', path: GROUP_VOICE + '/API Key', targetId: 'voiceKeySave' },
    { id: 'voice.keyDel', title: '删除转写 Key', path: GROUP_VOICE + '/API Key', targetId: 'voiceKeyDel' },
    { id: 'voice.open', title: '打开转写窗口（实时出字）', path: GROUP_VOICE + '/转写窗口', targetId: 'voiceOpen' },

    // —— 翻译引擎（v158 折叠分组，advanced）——
    { id: 'trans.engine', title: '划词翻译用哪个引擎（自动 / DeepL / 百度 / AI）', path: GROUP_TRANS + '/翻译引擎', targetId: 'engine', advanced: true },
    { id: 'trans.deepl', title: 'DeepL API Key', path: GROUP_TRANS + '/翻译引擎/DeepL', targetId: 'deeplBox', advanced: true },
    { id: 'trans.baidu', title: '百度翻译凭证（APP ID / API Key / 密钥）', path: GROUP_TRANS + '/翻译引擎/百度翻译', targetId: 'baiduBox', advanced: true },
    { id: 'trans.baidu.model', title: '百度模型（大模型翻译 / 机器翻译）', path: GROUP_TRANS + '/翻译引擎/百度翻译', targetId: 'bdModel', advanced: true },

    // —— 自定义模式（advanced）——
    { id: 'mode.list', title: '自定义模式（名字 + 解析要求）', path: GROUP_MODE + '/自定义模式', targetId: 'cmodes', advanced: true },
    { id: 'mode.add', title: '新建自定义模式', path: GROUP_MODE + '/自定义模式', targetId: 'cmAdd', advanced: true },

    // —— 缓存 / 历史（advanced）——
    { id: 'cache.sw', title: '结果缓存开关（省额度）', path: GROUP_CACHE + '/结果缓存', targetId: 'cacheSw', advanced: true },
    { id: 'cache.clear', title: '清空缓存', path: GROUP_CACHE + '/结果缓存', targetId: 'cacheClr', advanced: true },
    { id: 'hist.sw', title: '历史记录开关', path: GROUP_HIST + '/历史记录', targetId: 'histSw', advanced: true },
    { id: 'hist.clear', title: '清空历史', path: GROUP_HIST + '/历史记录', targetId: 'histClr', advanced: true },

    // —— 其他（快捷键 / 扫描 / 背景 / 对比度，折叠分组 advanced）——
    { id: 'other.hotkey', title: '划词翻译快捷键（改键 / 恢复默认）', path: GROUP_OTHER + '/划词翻译快捷键', targetId: 'hkRec', advanced: true },
    { id: 'other.hotkey.dft', title: '快捷键恢复默认', path: GROUP_OTHER + '/划词翻译快捷键', targetId: 'hkDft', advanced: true },
    { id: 'other.interval', title: '自动扫描间隔（秒）', path: GROUP_OTHER + '/自动扫描间隔', targetId: 'interval', advanced: true },
    { id: 'other.bg', title: '背景浓度', path: GROUP_OTHER + '/背景浓度', targetId: 'bgAlpha', advanced: true },
    { id: 'other.contrast', title: '文字对比度', path: GROUP_OTHER + '/文字对比度', targetId: 'contrast', advanced: true }
  ]

  /* 口头说法 → 条目 id。只挂到目录里真实存在的项上。
   * 「字太小」：正文字号（面板里的字）；「换颜色」：外观配色；「同步手机」：局域网同步；「DeepL」：翻译引擎。 */
  const ALIASES = {
    'api.version': '更新 升级 新版本 检查更新 版本号 下载 最新版 自动更新 manifest',
    'api.version.download': '更新 升级 下载安装包 最新版',
    'api.providers': '预设 服务商 小米 mimo deepseek 硅基流动 智谱 openai 兼容 一键填 模板',
    'api.baseUrl': '接口 地址 网址 url base url 端点 endpoint 服务器 反代 网关 地址填错',
    'api.key': '密钥 key token 令牌 凭证 鉴权 sk 申请 key',
    'api.keyLink': '申请 key 注册 官网 去哪申请',
    'api.model': '模型 型号 model 换模型 模型名',
    'api.fetch': '获取列表 拉模型 列出模型 fetch models',
    'api.test': '检测连接 测试 连通 能不能看图 图片 视觉 多模态 报错',
    'api.thinking': '思考强度 思考 推理 reasoning 想的久 慢 速度快 关思考 thinking',
    'api.detail': '解析强度 简略 标准 详细 讲解 讲得细 解题步骤',
    'api.novice': '纯小白 大白话 通俗 零基础 讲明白',
    'api.profiles': '档案 配置档 多个账号 切换接口 删档案',
    'api.saveas': '存为档案 保存档案',
    'api.profileName': '档案名字 起名',
    'look.keywords': '关键词 词块 术语 名词解释 识别关键词 型号 品牌',
    // v1.76：专注守护 / 陪伴
    'focus.duration': '专注 专注守护 番茄钟 守护 时长 专注多久 几分钟 focus 管住自己 自律',
    'focus.away': '分心 分心阈值 走神 离开多久 提醒 切走 玩手机 别的软件 away',
    'companion.on': '陪伴 陪着我 鼓励 短句 小知识 陪伴频率 频率 ai 陪伴短句 消耗额度 companion 说话',
    'look.theme': '换颜色 配色 主题 颜色 皮肤 护眼 深浅 背景色 丑 theme',
    'look.side': '位置 左边 右边 跟着小球 面板在哪 挡住 挡题 遮住',
    'look.fontSize': '字太小 字太大 字号 字体大小 放大字 缩小字 看不清 文字大小 字号 字体 大小',
    'sync.start': '同步手机 手机 局域网 配对 开同步 电脑和手机 传数据 知识卡同步 收藏同步',
    'sync.stop': '停止同步 关同步 断开',
    'sync.conn': '连接串 二维码 扫码 配对码 手机粘贴',
    'sync.lastStats': '上次同步 同步了多少 新增 更新 冲突统计',
    'sync.conflicts': '冲突 保留哪个 保留副本 保留主记录 两条都留',
    'trans.engine': '翻译引擎 划词翻译 翻译用哪个 deepl 百度 自动 引擎',
    'trans.deepl': 'deepl 翻译 key 深翻 德普',
    'trans.baidu': '百度 百度翻译 appid 百度密钥 baidu',
    'trans.baidu.model': '百度模型 大模型翻译 机器翻译 nmt',
    'mode.list': '自定义模式 模式 自己的模式 错题本 定制 提示词',
    'mode.add': '新建模式 加一个模式 新增自定义',
    'cache.sw': '缓存 省钱 不花额度 复用结果',
    'cache.clear': '清空缓存 删缓存 重新问',
    'hist.sw': '历史 记录 存不存 history',
    'hist.clear': '清空历史 删记录 隐私',
    'other.hotkey': '快捷键 热键 划词翻译快捷键 改键 组合键 hotkey 快捷键冲突',
    'other.hotkey.dft': '快捷键恢复默认 重置快捷键 改键恢复',
    'other.interval': '自动扫描 扫描间隔 每隔几秒 自动识别 interval',
    'other.bg': '背景浓度 透明度 背景太实 背景太透 面板透明',
    'other.contrast': '对比度 字不清楚 发灰 对比',
    // v1.64：外部 Agent（MCP）+ 手机常连。和「局域网同步」区分开：那边是点一次对一次数据。
    'mcp.toggle': 'mcp 外部 agent 外部agent 智能体 claude cherry studio 开放 接口 给 agent 用 工具调用',
    'mcp.start': 'mcp 开启 打开 开放 服务 端口 agent 连上',
    'mcp.stop': 'mcp 关闭 停掉 关掉 服务',
    'mcp.port': 'mcp 端口 端口占用 换端口 47321 连不上',
    'mcp.client': '令牌 token 签发 新建客户端 客户端 权限 只读 写入 操作 私人 personal act write',
    'mcp.clients': '已签发 客户端列表 撤销 收回 token 失效 撤销写入 回滚',
    'mcp.snippet': '配置片段 json 配置 环境变量 LING_MCP_TOKEN stdio 桥接',
    'mcp.roots': '可打开目录 文件目录 白名单 根目录 允许打开 路径',
    'mcp.roots.add': '添加目录 加目录 选目录 白名单',
    'mcp.handouts': '讲义 文件夹 保存位置 打开目录 handout',
    'mcp.audit': '审计 调用记录 日志 谁调用 导出审计 记录',
    'mcp.audit.export': '导出审计 保存审计 jsonl',
    'link.toggle': '手机常连 持久配对 常连 手机连接 link 后台连接',
    'link.start': '手机常连 开启 配对 常连服务 端口 47322 连接手机',
    'link.stop': '手机常连 关闭 断开 停止',
    'link.port': '手机常连端口 端口占用 47322 换端口',
    'link.pair': '配对 二维码 扫码 生成配对 手机扫码 link 串',
    'link.pairs': '已配对 手机列表 撤销配对 解绑 删除配对',
    'voice.toggle': '语音转文字 实时转写 语音识别 听写 转写 麦克风 录音 asr stt',
    'voice.provider': '豆包 火山 火山引擎 volc 阿里 阿里云 百炼 dashscope paraformer 服务商 哪家 切换',
    'voice.key': '转写 key 语音密钥 豆包 key 阿里 key api key x-api-key bearer',
    'voice.keySave': '保存 key 加密 钥匙串 safeStorage 密钥',
    'voice.keyDel': '删除 key 清除密钥 换账号',
    'voice.open': '打开转写 实时字幕 边说边出字 转写窗口 语音窗口',
  }

  const MAX_QUERY = 120
  const MAX_IDS = 8
  const MAX_TEXT = 2000
  const MAX_TOKENS = 12
  const MIN_CJK = 2

  const ID_SET = new Set(catalog.map((e) => e.id))
  const BY_ID = new Map(catalog.map((e) => [e.id, e]))
  // catalog 的 id 含驼峰（look.fontSize / api.baseUrl / api.keyLink…）：匹配一律大小写不敏感，命中后回规范 id
  const ID_CANON = new Map(catalog.map((e) => [e.id.toLowerCase(), e.id]))
  const ID_RE = /[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z0-9]+)+/g

  function norm(t) {
    return String(t == null ? '' : t).replace(/\s+/g, ' ').trim().toLowerCase()
  }

  /** 单条目的匹配文本：标题 + 路径 + 别名。 */
  function hayOf(entry) {
    return norm(entry.title + ' ' + entry.path + ' ' + (ALIASES[entry.id] || ''))
  }

  /* 纯 ASCII 单字母太容易误命中（比如「a」），要求 token 长度 ≥ 2 或是中文。 */
  function tokensOf(q) {
    return norm(q).slice(0, MAX_QUERY).split(/[\s,，、;；]+/)
      .filter((t) => t && (t.length > 1 || /[\u3400-\u9fff]/.test(t)))
      .slice(0, MAX_TOKENS)
  }

  /**
   * 本机关键词 / 别名匹配。返回 catalog entry 数组（引用 catalog 里的对象，按相关度、同分按目录顺序）。
   * 空 query → []；切不出有效 token（如「a」）也 → []。整句命中不了时，再用 2~4 字的中文片段兜底（「字太小了」→「字太小」）。
   * 兜底按**片段覆盖度**排序：命中片段越长 / 不同片段越多越靠前，不只看第一段落在哪。
   */
  function search(query) {
    const n = norm(query).slice(0, MAX_QUERY)
    if (!n) return []
    const toks = tokensOf(n)
    if (!toks.length) return []   // 全是 1 个字母之类的噪声（如「a」）→ 不返回任何条目
    const scored = new Map()
    catalog.forEach((entry, idx) => {
      const hay = hayOf(entry)
      let score = 0
      for (const t of toks) {
        const i = hay.indexOf(t)
        if (i < 0) return
        score += i === 0 ? 4 : 1
        if (norm(entry.path).indexOf(t) >= 0) score += 2
        if (norm(entry.title).indexOf(t) >= 2) score += 2
      }
      scored.set(entry.id, { entry, score, idx })
    })
    // 直接打 id（「api.baseUrl」「look.font」）：大小写不敏感，按 id 前缀补进来
    catalog.forEach((entry, idx) => {
      const idl = entry.id.toLowerCase()
      if (scored.has(entry.id) || !idl.startsWith(n)) return
      scored.set(entry.id, { entry, score: 4 + (idl === n ? 4 : 0), idx })
    })
    if (!scored.size && /[\u3400-\u9fff]/.test(n)) {
      const grams = []
      for (const size of [4, 3, 2]) {
        for (let i = 0; i + size <= n.length; i++) {
          const g = n.slice(i, i + size)
          // 只收含中文的 2~4 字子串；纯 ASCII 子串会让「thinking」→「thin」这种噪声混进来
          if (/[\u3400-\u9fff]/.test(g)) grams.push(g)
        }
      }
      const uniq = Array.from(new Set(grams))
      catalog.forEach((entry, idx) => {
        const hay = hayOf(entry)
        let cover = 0, hits = 0, titleHit = false
        for (const g of uniq) {
          if (hay.indexOf(g) < 0) continue
          cover += g.length
          hits++
          if (norm(entry.title).indexOf(g) >= 0) titleHit = true
        }
        if (!hits) return
        scored.set(entry.id, { entry, score: cover * 3 + hits, idx })
        if (titleHit) scored.get(entry.id).score += 2
      })
    }
    return Array.from(scored.values())
      .sort((a, b) => (b.score - a.score) || (a.idx - b.idx))
      .map((x) => x.entry)
  }

  function aliases(id) {
    return ID_SET.has(String(id)) ? String(ALIASES[id] || '') : ''
  }

  function has(id) {
    return ID_SET.has(String(id))
  }

  function get(id) {
    return BY_ID.get(String(id)) || null
  }

  /**
   * 把模型回传的文本解析成白名单内的 id。
   * - 只认 catalog 里存在的 id（大小写不敏感，返回 catalog 里的规范写法）；形似 xxx.yyy 但不在白名单的片段直接丢弃（不猜、不补）。
   * - 去重、保持首次出现顺序、最多 MAX_IDS 条。
   * - 入参不限类型：非字符串 / 空 / 超长都在内部兜住，不抛。
   */
  function parseIds(text) {
    let t = ''
    if (typeof text === 'string') t = text
    else if (Array.isArray(text)) t = text.filter((x) => typeof x === 'string').join(',')
    else return []
    t = t.slice(0, MAX_TEXT)
    const out = []
    const seen = new Set()
    const m = t.match(ID_RE) || []
    for (const raw of m) {
      if (out.length >= MAX_IDS) break
      const id = ID_CANON.get(raw.replace(/[.,:;]+$/, '').toLowerCase())
      if (!id || seen.has(id)) continue
      seen.add(id)
      out.push(id)
    }
    return out
  }

  /**
   * 主进程拼提示词用：目录的标题 / 路径 / id（没有设置值、也没有任何用户数据）。
   * 可选带上本机口头说法（aliases），让模型更会认「字太小」这类说法。
   */
  function catalogForPrompt(includeAliases) {
    return catalog.map((e) => (includeAliases
      ? { id: e.id, title: e.title, path: e.path, aliases: String(ALIASES[e.id] || '') }
      : { id: e.id, title: e.title, path: e.path }))
  }

  return {
    catalog: catalog,
    search: search,
    parseIds: parseIds,
    aliases: aliases,
    has: has,
    get: get,
    catalogForPrompt: catalogForPrompt,
    limits: { query: MAX_QUERY, ids: MAX_IDS, text: MAX_TEXT }
  }
})
