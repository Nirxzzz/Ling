/* v1.58（对齐安卓 MathText.kt）：把模型写的 LaTeX（$…$、$$…$$、\(…\)、\[…\]）排成面板上能看的样子。
 * 不是完整 TeX —— 只做常见的：希腊字母和运算符 → Unicode；分式上下叠（CSS）；上下标 <sup>/<sub>；
 * √、\text、\left\right、aligned / cases 换行。认不出的命令去掉反斜杠照原样显示，不会出现一串 \xxx。
 * 另外顺手做「链接可点」：网址 / www. 变成可点的链接（点了交给主进程用浏览器打开）。
 * 用法：LZMath.fmt(原始一行文字, 普通文字的处理函数) → HTML；LZMath.joinDisplay(整篇) 先把跨行的 $$…$$ 并成一行。 */
(function () {
  const SYM = {
    alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', epsilon: 'ε', varepsilon: 'ε', zeta: 'ζ', eta: 'η', theta: 'θ', vartheta: 'ϑ',
    iota: 'ι', kappa: 'κ', lambda: 'λ', mu: 'μ', nu: 'ν', xi: 'ξ', pi: 'π', rho: 'ρ', sigma: 'σ', varsigma: 'ς', tau: 'τ', upsilon: 'υ',
    phi: 'φ', varphi: 'φ', chi: 'χ', psi: 'ψ', omega: 'ω', Gamma: 'Γ', Delta: 'Δ', Theta: 'Θ', Lambda: 'Λ', Xi: 'Ξ', Pi: 'Π', Sigma: 'Σ',
    Upsilon: 'Υ', Phi: 'Φ', Psi: 'Ψ', Omega: 'Ω',
    int: '∫', iint: '∬', iiint: '∭', oint: '∮', sum: '∑', prod: '∏', partial: '∂', nabla: '∇', infty: '∞', prime: '′', circ: '∘', degree: '°',
    cdot: '·', cdots: '⋯', ldots: '…', dots: '…', vdots: '⋮', ddots: '⋱', times: '×', div: '÷', pm: '±', mp: '∓', ast: '∗', star: '⋆',
    le: '≤', leq: '≤', leqslant: '≤', ge: '≥', geq: '≥', geqslant: '≥', ne: '≠', neq: '≠', approx: '≈', sim: '∼', simeq: '≃', cong: '≅',
    equiv: '≡', propto: '∝', ll: '≪', gg: '≫', lt: '<', gt: '>', to: '→', rightarrow: '→', leftarrow: '←', gets: '←', leftrightarrow: '↔',
    Rightarrow: '⇒', implies: '⇒', Leftarrow: '⇐', Leftrightarrow: '⇔', iff: '⇔', mapsto: '↦', longrightarrow: '⟶', Longrightarrow: '⟹',
    uparrow: '↑', downarrow: '↓', in: '∈', notin: '∉', ni: '∋', subset: '⊂', subseteq: '⊆', supset: '⊃', supseteq: '⊇', cup: '∪', cap: '∩',
    setminus: '∖', emptyset: '∅', varnothing: '∅', forall: '∀', exists: '∃', neg: '¬', land: '∧', wedge: '∧', lor: '∨', vee: '∨',
    therefore: '∴', because: '∵', angle: '∠', triangle: '△', perp: '⊥', parallel: '∥', mid: '∣', hbar: 'ℏ', ell: 'ℓ', aleph: 'ℵ',
    langle: '⟨', rangle: '⟩', lfloor: '⌊', rfloor: '⌋', lceil: '⌈', rceil: '⌉', vert: '|', Vert: '‖', backslash: '\\', percent: '%',
    '{': '{', '}': '}', '%': '%', '$': '$', '&': '&', '#': '#', _: '_', '|': '‖', ',': ' ', ':': ' ', ';': ' ', ' ': ' ', '!': '',
    quad: ' ', qquad: '  ', square: '□', checkmark: '✓'
  }
  const FUNCS = new Set(['sin', 'cos', 'tan', 'cot', 'sec', 'csc', 'arcsin', 'arccos', 'arctan', 'sinh', 'cosh', 'tanh', 'ln', 'log', 'lg', 'exp',
    'lim', 'max', 'min', 'sup', 'inf', 'det', 'dim', 'ker', 'deg', 'gcd', 'arg', 'mod', 'bmod'])
  const DROP = new Set(['left', 'right', 'big', 'Big', 'bigg', 'Bigg', 'bigl', 'bigr', 'Bigl', 'Bigr', 'biggl', 'biggr', 'middle', 'limits', 'nolimits',
    'displaystyle', 'textstyle', 'scriptstyle', 'nonumber', 'notag', 'boxed'])
  const FONT = new Set(['textit', 'textbf', 'mathrm', 'mathit', 'mathbf', 'mathsf', 'mathtt', 'boldsymbol', 'bm', 'operatorname', 'emph', 'mathcal', 'mathscr', 'underline', 'overbrace', 'underbrace'])
  const ACCENT = { vec: '⃗', overrightarrow: '⃗', bar: '̄', overline: '̅', hat: '̂', widehat: '̂', tilde: '̃', dot: '̇', ddot: '̈' }
  const BB = { R: 'ℝ', N: 'ℕ', Z: 'ℤ', Q: 'ℚ', C: 'ℂ', P: 'ℙ' }
  const REL = new Set(['=', '<', '>', '≤', '≥', '≠', '≈', '≡', '→', '⇒', '⇔', '⟶', '⟹', '∈', '∉', '⊂', '⊆', '⊇', '⊃', '∼', '≃', '≅', '∝', '↔', '←', '⇐', '↦'])
  const BIN = new Set(['+', '−', '±', '∓', '×', '÷', '·'])

  const escH = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

  /* 节点：{t:'x'} 文字 / {sup:[…]} / {sub:[…]} / {num:[…], den:[…], line} 分式 / {br:1} 换行 */
  function parse(tex) {
    const s = String(tex); let i = 0; let tight = 0; const envs = []
    function sym(t, prev) {
      if (tight > 0) return t
      if (REL.has(t)) return ' ' + t + ' '
      if (t === ',' || t === ';') return t + ' '
      if (BIN.has(t) && prev && !(prev.t != null && (/^\s*$/.test(prev.t) || /[([{,]\s*$/.test(prev.t) || [...REL].some((r) => prev.t.trimEnd().endsWith(r))))) return ' ' + t + ' '
      return t
    }
    function tightArg() { tight++; try { return arg() } finally { tight-- } }
    function arg() {
      while (s[i] === ' ') i++
      if (i >= s.length) return []
      if (s[i] === '{') { i++; return seq('}') }
      if (s[i] === '\\') { const o = []; command(o); return o }
      const c = s[i++]; return [{ t: c === '-' ? '−' : c }]
    }
    function rawGroup() {
      while (s[i] === ' ') i++
      if (s[i] !== '{') return ''
      let d = 0; const st = i
      for (; i < s.length; i++) { if (s[i] === '{') d++; else if (s[i] === '}') { d--; if (d === 0) { i++; return s.slice(st + 1, i - 1) } } }
      return s.slice(st + 1)
    }
    function optArg() {
      if (s[i] === '[') { const e = s.indexOf(']', i); if (e > 0) { const inner = s.slice(i + 1, e); i = e + 1; return parse(inner) } }
      return null
    }
    function command(out) {
      i++
      if (i >= s.length) return
      const st = i
      if (/[A-Za-z]/.test(s[i])) { while (i < s.length && /[A-Za-z]/.test(s[i])) i++ } else i++
      const name = s.slice(st, i)
      if (/^[A-Za-z]/.test(name)) while (s[i] === ' ') i++
      const prev = out[out.length - 1]
      if (name === '\\') { out.push({ br: 1 }); optArg() }
      else if (['frac', 'dfrac', 'tfrac', 'cfrac'].includes(name)) { const a = tightArg(); const b = tightArg(); out.push({ num: a, den: b, line: true }) }
      else if (['binom', 'dbinom', 'tbinom'].includes(name)) { const a = tightArg(); const b = tightArg(); out.push({ t: '(' }, { num: a, den: b, line: false }, { t: ')' }) }
      else if (name === 'sqrt') {
        const n = optArg(); const a = arg()
        if (n) out.push({ sup: n })
        out.push({ t: '√' })
        if (a.length === 1 && a[0].t != null && a[0].t.length === 1) out.push(a[0]); else out.push({ sqrt: a })
      }
      else if (name === 'begin') { const env = rawGroup().replace(/\*$/, ''); if (env === 'array') rawGroup(); envs.push(env); if (env === 'cases') out.push({ t: '{ ' }); if (env === 'pmatrix') out.push({ t: '( ' }); if (env === 'bmatrix') out.push({ t: '[ ' }) }
      else if (name === 'end') { const env = rawGroup().replace(/\*$/, ''); envs.pop(); if (env === 'pmatrix') out.push({ t: ' )' }); if (env === 'bmatrix') out.push({ t: ' ]' }) }
      else if (name === 'mathbb' || name === 'Bbb') out.push({ t: [...rawGroup()].map((c) => BB[c] || c).join('') })
      else if (['text', 'textrm', 'mbox', 'hbox', 'textnormal'].includes(name)) out.push({ t: rawGroup() })
      else if (FONT.has(name)) out.push(...arg())
      else if (ACCENT[name]) { const a = arg(); if (a.length === 1 && a[0].t != null) out.push({ t: [...a[0].t].map((c) => c + ACCENT[name]).join('') }); else out.push(...a) }
      else if (name === 'overset' || name === 'stackrel') { const top = tightArg(); const base = arg(); out.push(...base, { sup: top }) }
      else if (name === 'underset') { const bot = tightArg(); const base = arg(); out.push(...base, { sub: bot }) }
      else if (['hspace', 'vspace', 'label', 'tag', 'color'].includes(name)) rawGroup()
      else if (name === 'textcolor') { rawGroup(); out.push(...arg()) }
      else if (DROP.has(name)) { if ((name === 'left' || name === 'right') && s[i] === '.') i++ }
      else if (FUNCS.has(name)) out.push({ t: name === 'bmod' || name === 'mod' ? ' mod ' : (/[A-Za-z0-9]/.test(s[i] || '') ? name + ' ' : name) })
      else if (SYM[name] != null) out.push({ t: sym(SYM[name], prev) })
      else out.push({ t: name })
    }
    function seq(until) {
      const out = []
      while (i < s.length) {
        const c = s[i]; const prev = out[out.length - 1]
        if (until && c === until) { i++; return out }
        if (c === '{') { i++; out.push(...seq('}')) }
        else if (c === '}') i++
        else if (c === '^' || c === '_') { i++; const a = tightArg(); out.push(c === '^' ? { sup: a } : { sub: a }) }
        else if (c === '\\') command(out)
        else if (c === '&') { i++; out.push({ t: /^align/.test(envs[envs.length - 1] || '') ? '' : ' ' }) }
        else if (c === '~') { i++; out.push({ t: ' ' }) }
        else if (c === '\'') { i++; out.push({ t: '′' }) }
        else if (c === '-') { i++; out.push({ t: sym('−', prev) }) }
        else if (c === '*') { i++; out.push({ t: sym('·', prev) }) }
        else if (/\s/.test(c)) i++
        else { i++; out.push({ t: sym(c, prev) }) }
      }
      return out
    }
    return seq(null)
  }

  function html(nodes) {
    let o = ''
    for (const n of nodes) {
      if (n.t != null) o += escH(n.t)
      else if (n.br) o += '<br>'
      else if (n.sup) o += '<sup>' + html(n.sup) + '</sup>'
      else if (n.sub) o += '<sub>' + html(n.sub) + '</sub>'
      else if (n.sqrt) o += '<span class="lzm-sqrt">' + html(n.sqrt) + '</span>'
      else if (n.num) o += '<span class="lzm-frac' + (n.line ? '' : ' nol') + '"><span class="lzm-num">' + html(n.num) + '</span><span class="lzm-den">' + html(n.den) + '</span></span>'
    }
    return o
  }
  /** 线性写法（没法排版的地方 / 复制用）：\frac{a+b}{2} → (a+b)/2 */
  function plain(nodes) {
    let o = ''
    const atom = (x) => (x.length <= 1 || /^[\d.]+$/.test(x)) ? x : '(' + x + ')'
    for (const n of nodes) {
      if (n.t != null) o += n.t
      else if (n.br) o += '\n'
      else if (n.sup) { const p = plain(n.sup); o += '^' + atom(p) }
      else if (n.sub) { const p = plain(n.sub); o += '_' + atom(p) }
      else if (n.sqrt) o += '(' + plain(n.sqrt) + ')'
      else if (n.num) o += n.line ? atom(plain(n.num)) + '/' + atom(plain(n.den)) : plain(n.num) + ', ' + plain(n.den)
    }
    return o
  }
  function render(tex, display) { return '<span class="' + (display ? 'lzm-d' : 'lzm-i') + '">' + html(parse(tex)) + '</span>' }

  const INLINE = /\$\$([^$]+?)\$\$|\\\((.+?)\\\)|\\\[(.+?)\\\]|(?<![\\$\w])\$(?![\s$])((?:\\\$|[^$])+?)(?<![\s\\])\$(?!\d)/g
  const BARE = /\\(frac|dfrac|tfrac|sqrt|int|iint|oint|sum|prod|lim|infty|alpha|beta|gamma|delta|theta|lambda|mu|pi|sigma|omega|varphi|phi|cdot|times|div|pm|le|leq|ge|geq|neq|ne|approx|to|rightarrow|Rightarrow|in|subseteq|cup|cap|partial|mathbb|vec|overline|left|right|begin)(?![a-zA-Z])/
  const LOOSE = /(?<=[\w)\]′])([\^_])(\{[^{}\n]{1,24}\}|-?\d{1,3})/g
  const URL = /(https?:\/\/[^\s<>"'，。；、）)】]+[^\s<>"'，。；、）)】.,;:!?])|(www\.[a-z0-9-]+(?:\.[a-z0-9-]+)+[^\s<>"'，。；、）)】]*[^\s<>"'，。；、）)】.,;:!?])/gi

  /**
   * 一行原始文字 → HTML：公式先抠出来换成占位符，其余交给 f（转义 + 加粗 / 代码），再把公式填回去，最后把网址变成链接。
   * 占位符用私用区字符，转义和 markdown 都不会动它。
   */
  function fmt(raw, f) {
    const parts = []
    const put = (h) => { parts.push(h); return '' + (parts.length - 1) + '' }
    let s = String(raw).replace(INLINE, (m, a, b, c, d) => put(render(a || b || c || d, !!(a || c))))
    // 没加 $ 的裸公式（整行混着中文）：只把命令部分当公式 —— 按空白切块，带反斜杠命令的块才转
    if (BARE.test(s)) s = s.split(/(\s+)/).map((w) => BARE.test(w) ? put(render(w, false)) : w).join('')
    // 公式外面的 x^2 / a_{n+1} 这种纯文本写法也排一下（有网址的行不动，免得把 _1 当下标）
    if (!/:\/\/|www\./.test(s)) s = s.replace(LOOSE, (m, op, body) => put('<' + (op === '^' ? 'sup' : 'sub') + '>' + html(parse(body.replace(/^\{|\}$/g, ''))) + '</' + (op === '^' ? 'sup' : 'sub') + '>'))
    let out = f(s)
    // 网址 → 可点（只动标签外面的文字）
    out = out.split(/(<[^>]+>)/).map((seg) => seg.startsWith('<') ? seg : seg.replace(URL, (u) => {
      const href = /^https?:/i.test(u) ? u : 'https://' + u
      return '<a class="lzm-lnk" href="#" data-href="' + href.replace(/"/g, '%22') + '">' + u + ' ↗</a>'
    })).join('')
    return out.replace(/(\d+)/g, (m, k) => parts[+k])
  }

  /** 把跨行的 $$ … $$ / \[ … \] 并成一行（流式时右边还没到：先当它到结尾了） */
  function joinDisplay(src) {
    const lines = String(src).split('\n'); const out = []
    for (let i = 0; i < lines.length; i++) {
      const t = lines[i].trim()
      const open = t.startsWith('$$') ? '$$' : t.startsWith('\\[') ? '\\]' : null
      if (!open || (t.length > 2 && t.indexOf(open, 2) >= 0)) { out.push(lines[i]); continue }
      let buf = t, j = i + 1, closed = false
      for (; j < lines.length; j++) {
        const l = lines[j].trim(); const k = l.indexOf(open)
        if (k >= 0) { buf += ' ' + l.slice(0, k + open.length); out.push(buf); const rest = l.slice(k + open.length).trim(); if (rest) out.push(rest); closed = true; break }
        buf += ' ' + l
      }
      if (!closed) out.push(buf + ' ' + open)
      i = j
    }
    return out.join('\n')
  }

  const api = { parse, html, plain, render, fmt, joinDisplay, toPlain: (t) => plain(parse(t)) }
  if (typeof window !== 'undefined') window.LZMath = api
  if (typeof module !== 'undefined') module.exports = api
})()
