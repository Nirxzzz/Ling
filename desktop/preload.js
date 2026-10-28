const { contextBridge, ipcRenderer } = require('electron')
contextBridge.exposeInMainWorld('orbApi', {
  click: () => ipcRenderer.send('orb:click'),
  copy: (text) => ipcRenderer.send('clipboard:write', text),
  getHistory: () => ipcRenderer.invoke('history:get'),
  getPanelTitle: () => ipcRenderer.invoke('get:panelTitle'),
  move: (dx, dy) => ipcRenderer.send('orb:move', dx, dy),
  reset: () => ipcRenderer.send('orb:reset'),
  doubleclick: () => ipcRenderer.send('orb:doubleclick'),
  // v1.46：三击小球 = 自动扫描开关
  tripleclick: () => ipcRenderer.send('orb:tripleclick'),
  quit: () => ipcRenderer.send('orb:quit'),
  close: () => ipcRenderer.send('answer:close'),
  resize: (h) => ipcRenderer.send('answer:resize', h),
  openPrivacy: () => ipcRenderer.send('open-privacy-pane'),
  openAccessibilityPane: () => ipcRenderer.send('open-accessibility-pane'),

  // 弹出菜单
  menu: () => ipcRenderer.send('orb:menu'),
  menuAction: (a) => ipcRenderer.send('menu:action', a),
  menuClose: () => ipcRenderer.send('menu:close'),
  menuReady: (h) => ipcRenderer.send('menu:ready', h),
  onMenuOpen: (cb) => ipcRenderer.on('menu:open', () => cb()),
  menuResize: (h) => ipcRenderer.send('menu:resize', h),
  getMenuInfo: () => ipcRenderer.invoke('menu:info'),

  // 自动扫描确认卡（v1.25）
  scanStart: () => ipcRenderer.send('scan:start'),
  scanCancel: () => ipcRenderer.send('scan:cancel'),
  scanResize: (h) => ipcRenderer.send('scan:resize', h),

  // v1.61.1 首次新手卡（hint.html 专用；主进程核对 sender 是不是 hintWin）
  hintResize: (h) => ipcRenderer.send('hint:resize', h),
  hintAction: (a) => ipcRenderer.send('hint:action', a),
  onHintSide: (cb) => ipcRenderer.on('hint:side', (_e, s) => cb(s)),

  // 追问（v1.26）
  askOpen: () => ipcRenderer.send('answer:ask-open'),
  // v1.65 通识：点名词词块解释
  askTerm: (t) => ipcRenderer.send('answer:ask-term', t),
  askSend: (t, withShot) => ipcRenderer.send('ask:send', t, !!withShot),   // v1.51：可附带一张新截图
  askInfo: () => ipcRenderer.invoke('ask:info'),
  askCancel: () => ipcRenderer.send('ask:cancel'),
  setDimState: (on) => ipcRenderer.send('orb:dim-state', on),
  mouseThrough: (on) => ipcRenderer.send('orb:mouse-through', on),
  onDimToggle: (cb) => ipcRenderer.on('orb:dim-toggle', () => cb()),

  // 划词翻译
  transTrigger: () => ipcRenderer.send('trans:trigger'),
  transClose: () => ipcRenderer.send('trans:close'),
  transRetry: () => ipcRenderer.send('trans:retry'),
  transResize: (h) => ipcRenderer.send('trans:resize', h),
  transToggleCopy: () => ipcRenderer.send('trans:toggle-copy'),
  transSetLang: (id) => ipcRenderer.send('trans:set-lang', id),
  lookupWord: (w) => ipcRenderer.invoke('trans:word', w),
  getTransLangs: () => ipcRenderer.invoke('trans:langs'),
  getTransInfo: () => ipcRenderer.invoke('trans:info'),

  onChunk: (cb) => ipcRenderer.on('answer:chunk', (_e, d) => cb(d)),
  onState: (cb) => ipcRenderer.on('answer:state', (_e, s) => cb(s)),
  onAnswerMode: (cb) => ipcRenderer.on('answer:mode', (_e, m) => cb(m)),
  onOrb: (cb) => { ipcRenderer.on('orb:busy', () => cb(true)); ipcRenderer.on('orb:idle', () => cb(false)) },
  onScan: (cb) => { ipcRenderer.on('orb:scan', (_e, on) => cb(on)) },
  // v1.76 专注守护 / 陪伴：小球守护环状态 + 球旁气泡（bubble.html 复用这份 preload）
  onGuard: (cb) => { ipcRenderer.on('orb:guard', (_e, g) => cb(g)) },
  onBubble: (cb) => { ipcRenderer.on('bubble:show', (_e, b) => cb(b)) },
  bubbleResize: (h) => ipcRenderer.send('bubble:resize', h),
  bubbleClose: () => ipcRenderer.send('bubble:close'),
  onOrbMode: (cb) => ipcRenderer.on('orb:mode', (_e, label) => cb(label)),
  onCopyMode: (cb) => ipcRenderer.on('trans:copyMode', (_e, on) => cb(on)),
  onTransChunk: (cb) => ipcRenderer.on('trans:chunk', (_e, d) => cb(d)),
  onTransState: (cb) => ipcRenderer.on('trans:state', (_e, s) => cb(s)),
  onTransSrc: (cb) => ipcRenderer.on('trans:src', (_e, t) => cb(t)),
  onTransLang: (cb) => ipcRenderer.on('trans:lang', (_e, i) => cb(i)),
  onMenuLang: (cb) => ipcRenderer.on('menu:lang', (_e, i) => cb(i)),
  onMenuUpdate: (cb) => ipcRenderer.on('menu:update', (_e, d) => cb(d)),

  // v1.31 设置卡：自定义 API + 扫描间隔
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (s) => ipcRenderer.send('settings:save', s),
  cancelSettings: () => ipcRenderer.send('settings:cancel'),
  settingsResize: (h) => ipcRenderer.send('settings:resize', h),
  openExternal: (url) => ipcRenderer.send('settings:openexternal', url),

  // v1.63：设置搜索的 AI 语义匹配（仅设置卡）。只上行 query + requestId；
  // 主进程核对 sender、限长、只回目录白名单里的 id。取消是单向的 fire-and-forget。
  searchSettings: (o) => ipcRenderer.invoke('settings:search', { query: String((o && o.query) || ''), requestId: String((o && o.requestId) || '') }),
  cancelSettingsSearch: (requestId) => ipcRenderer.send('settings:search-cancel', String(requestId || '')),

  // v1.37：API 档案一键切换 / 拉模型列表 / 检测连接 + 面板上的一行提示
  switchProfile: (name) => ipcRenderer.send('settings:switchprofile', name),
  // v1.38：划词翻译热键（传 '__default__' = 恢复默认 ⌘/）
  setHotkey: (acc) => ipcRenderer.invoke('settings:sethotkey', acc),
  fetchModels: (o) => ipcRenderer.invoke('settings:fetchmodels', o),
  testConn: (o) => ipcRenderer.invoke('settings:test', o),
  onNotice: (cb) => ipcRenderer.on('answer:notice', (_e, t) => cb(t)),
  // v1.46：单击小球 → 让答案面板把历史列表打开
  onOpenHistory: (cb) => ipcRenderer.on('answer:open-history', () => cb(true)),
  // v1.7x：思考阶段的进度反馈（还没出正文时显示静态「正在思考…」；不再显示推理字数，桌面端没有真实 token 可显示）
  onAnswerPhase: (cb) => ipcRenderer.on('answer:phase', (_e, t) => cb(t)),
  onTransPhase: (cb) => ipcRenderer.on('trans:phase', (_e, t) => cb(t)),

  // v1.32 外观自定义
  getAppearance: () => ipcRenderer.invoke('appearance:get'),
  setAppearance: (a) => ipcRenderer.send('appearance:set', a),
  onAppearanceChanged: (cb) => ipcRenderer.on('appearance:changed', (_e, a) => cb(a)),
  delProfile: (name) => ipcRenderer.send('settings:delprofile', name),
  // v1.58：翻译面板标题后缀（百度 / 缓存）、缓存和历史管理、答案里的链接
  onTransBadge: (cb) => ipcRenderer.on('trans:badge', (_e, t) => cb(t)),
  clearCache: () => ipcRenderer.invoke('settings:clearcache'),
  clearHistory: () => ipcRenderer.invoke('settings:clearhistory'),
  openLink: (url) => ipcRenderer.send('link:open', url),
  retrySolve: () => ipcRenderer.send('answer:retry'),
  // v1.60：追问 what / why 开关 + 面板上的模式抽屉
  askToggle: (which) => ipcRenderer.send('ask:toggle', which),
  getAskToggles: () => ipcRenderer.invoke('ask:toggles'),
  onAskToggles: (cb) => ipcRenderer.on('ask:toggles', (_e, t) => cb(t)),
  getModes: () => ipcRenderer.invoke('answer:modes'),
  setModeFromPanel: (id) => ipcRenderer.send('answer:set-mode', id),
  onAnswerBadge: (cb) => ipcRenderer.on('answer:badge', (_e, t) => cb(t)),

  // v1.72 [KCard] 知识卡：存卡 / 复习页 / 卡库 / 到期数
  kcardAdd: (raw, deck) => ipcRenderer.invoke('kcard:add', String(raw || ''), deck || ''),
  kcardHas: (raw) => ipcRenderer.invoke('kcard:has', String(raw || '')),
  kcardQueue: () => ipcRenderer.invoke('kcard:queue'),
  kcardAnswer: (id, rating) => ipcRenderer.invoke('kcard:answer', id, rating),
  kcardAll: () => ipcRenderer.invoke('kcard:all'),
  kcardRemove: (id) => ipcRenderer.invoke('kcard:remove', id),
  kcardDueCount: () => ipcRenderer.invoke('kcard:dueCount'),
  kcardClose: () => ipcRenderer.send('kcard:close'),
  kcardResize: (h) => ipcRenderer.send('kcard:resize', h),

  // v1.74：桌面内置更新通道 —— 只暴露「检查 / 下载」两个无参调用。
  // 下载目标（URL / sha256 / 落盘路径）全在主进程按上次 check 过的本平台资产决定，渲染器传不了任何 URL / 路径。
  checkUpdate: () => ipcRenderer.invoke('update:check'),
  downloadUpdate: () => ipcRenderer.invoke('update:download'),

  // v1.61：局域网同步（仅设置卡使用；主进程只接受设置窗口的调用）
  // 连接串只通过 status/start 返回给设置页，复制也走主进程 —— 不把剪贴板能力开放给其他面板。
  lanSyncStatus: () => ipcRenderer.invoke('lan-sync:status'),
  lanSyncStart: () => ipcRenderer.invoke('lan-sync:start'),
  lanSyncStop: () => ipcRenderer.invoke('lan-sync:stop'),
  lanSyncCopy: (i) => ipcRenderer.invoke('lan-sync:copy', i),
  // v1.62：冲突列表 / 三选解决（只接受 KEEP_BASE / KEEP_COPY / KEEP_BOTH）
  lanConflicts: () => ipcRenderer.invoke('lan-sync:conflicts'),
  lanResolveConflict: (copyUid, pick) => ipcRenderer.invoke('lan-sync:resolve', String(copyUid || ''), String(pick || '')),
  // v1.64：MCP（本机外部 Agent）+ 手机常连（持久配对）。都只给设置页用，主进程逐条校验 sender。
  // token 只在 mcpClientCreate 那一次回包里有；其余接口一律不返回 token / 哈希 / 配对密钥。
  mcpStatus: () => ipcRenderer.invoke('mcp:status'),
  mcpSetEnabled: (on) => ipcRenderer.invoke('mcp:set-enabled', !!on),
  mcpSetPort: (p) => ipcRenderer.invoke('mcp:set-port', Number(p) || 0),
  mcpClientCreate: (o) => ipcRenderer.invoke('mcp:client-create', {
    name: String((o && o.name) || ''),
    scopes: Array.isArray(o && o.scopes) ? o.scopes.map(String).slice(0, 4) : []
  }),
  mcpClientRevoke: (id) => ipcRenderer.invoke('mcp:client-revoke', String(id || '')),
  mcpRootsAdd: () => ipcRenderer.invoke('mcp:roots-add'),
  mcpRootsRemove: (p) => ipcRenderer.invoke('mcp:roots-remove', String(p || '')),
  mcpRootsReset: () => ipcRenderer.invoke('mcp:roots-reset'),
  mcpOpenHandouts: () => ipcRenderer.invoke('mcp:open-handouts'),
  mcpAuditList: (limit) => ipcRenderer.invoke('mcp:audit-list', Number(limit) || 20),
  mcpAuditExport: () => ipcRenderer.invoke('mcp:audit-export'),
  mcpAuditCopy: () => ipcRenderer.invoke('mcp:audit-copy'),
  mcpRollback: (id) => ipcRenderer.invoke('mcp:rollback', String(id || '')),
  linkStatus: () => ipcRenderer.invoke('link:status'),
  linkSetEnabled: (on) => ipcRenderer.invoke('link:set-enabled', !!on),
  linkSetPort: (p) => ipcRenderer.invoke('link:set-port', Number(p) || 0),
  linkPairCreate: (name) => ipcRenderer.invoke('link:pair-create', { name: String(name || '') }),
  linkPairRevoke: (id) => ipcRenderer.invoke('link:pair-revoke', String(id || '')),
  // v1.61：历史收藏（面板历史列表里的 ☆ / ★）
  starHistory: (t, star) => ipcRenderer.invoke('history:star', t, !!star),

  // v1.75：实时转写的配置面（只有设置卡的 Key 输入与「打开转写窗口」用这条线）。
  // 主进程逐条核 sender（设置窗口或转写窗口），并且只回 hasKey 布尔 —— 明文 Key 绝不回到渲染器。
  voiceStatus: () => ipcRenderer.invoke('voice:status'),
  voiceSelectProvider: (p) => ipcRenderer.invoke('voice:select-provider', String(p || '')),
  voiceSaveKey: (provider, apiKey) => ipcRenderer.invoke('voice:save-key', { provider: String(provider || ''), apiKey: String(apiKey || '') }),
  voiceDeleteKey: (provider) => ipcRenderer.invoke('voice:delete-key', String(provider || '')),
  voiceOpen: () => ipcRenderer.invoke('voice:open'),
  voiceOpenMicSettings: () => ipcRenderer.send('voice:open-mic-settings')
})
