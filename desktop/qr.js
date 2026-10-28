/* qr.js — 纯本地二维码生成（无网络、无第三方依赖、无 DOM 依赖的编码核心）。
 *
 * 只做桌面设置卡需要的事：把一条连接串（UTF-8 字节模式）编成二维码矩阵，
 * 供 settings.html 用 canvas 画出来。支持版本 1–10、纠错等级 L（足够容纳
 * `ling-sync://<ipv4>:<port>/<43 字符 key>`；超出容量明确抛错，不截断）。
 *
 * 对外 API：
 *   QR.encode(text)                     → { version, size, mask, modules: boolean[][] }
 *   QR.render(canvas, text, sizePx)     → 直接在 canvas 上画（4 模块静区）
 *   QR.toSvg(text, sizePx)              → SVG 字符串（供无 canvas 的环境）
 *   QR.utf8(text)                       → 字节数组
 *
 * Node（测试 / 打包校验）与浏览器（<script src="qr.js">）都能用；浏览器挂到 window.QR。
 * 算法按 ISO/IEC 18004：字节模式 + Reed-Solomon + 8 种掩码按罚分择优。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory()
  else root.QR = factory()
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict'

  /* ---------------- 容量表（纠错等级 L，版本 1–10） ---------------- */

  const TOTAL_CODEWORDS = [0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346]
  const ECC_PER_BLOCK = [0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 36]
  const BLOCKS = [0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2]
  const DATA_CODEWORDS = [0]
  for (let v = 1; v <= 10; v += 1) DATA_CODEWORDS[v] = TOTAL_CODEWORDS[v] - ECC_PER_BLOCK[v] * BLOCKS[v]
  const REMAINDER_BITS = [0, 0, 7, 7, 7, 7, 7, 0, 0, 0, 0]
  const ALIGN = [
    [],
    [],
    [6, 18],
    [6, 22],
    [6, 26],
    [6, 30],
    [6, 34],
    [6, 22, 38],
    [6, 24, 42],
    [6, 26, 46],
    [6, 28, 50]
  ]

  const ECL_L = 1                        // 纠错等级 L 的 2 位编码
  const FORMAT_G15 = 0x537
  const FORMAT_MASK = 0x5412
  const VERSION_G18 = 0x1f25

  /* ---------------- GF(256) / Reed-Solomon ---------------- */

  const EXP = new Array(512)
  const LOG = new Array(256)
  ;(function initGF() {
    let x = 1
    for (let i = 0; i < 255; i += 1) {
      EXP[i] = x
      LOG[x] = i
      x <<= 1
      if (x & 0x100) x ^= 0x11d
    }
    for (let i = 255; i < 512; i += 1) EXP[i] = EXP[i - 255]
  })()

  function gfMul(a, b) {
    if (!a || !b) return 0
    return EXP[LOG[a] + LOG[b]]
  }

  function genPoly(degree) {
    let poly = [1]
    for (let i = 0; i < degree; i += 1) {
      const factor = EXP[i]
      const next = new Array(poly.length + 1).fill(0)
      for (let j = 0; j < poly.length; j += 1) {
        next[j] ^= poly[j]
        next[j + 1] ^= gfMul(poly[j], factor)
      }
      poly = next
    }
    return poly
  }

  function eccBytes(data, degree) {
    const gen = genPoly(degree)
    const buf = new Array(data.length + degree).fill(0)
    for (let i = 0; i < data.length; i += 1) buf[i] = data[i]
    for (let i = 0; i < data.length; i += 1) {
      const factor = buf[i]
      if (!factor) continue
      for (let j = 0; j < gen.length; j += 1) buf[i + j] ^= gfMul(gen[j], factor)
    }
    return buf.slice(data.length)
  }

  /* ---------------- 位流 / 字节 ---------------- */

  function utf8(text) {
    const s = String(text == null ? '' : text)
    if (typeof TextEncoder !== 'undefined') return Array.from(new TextEncoder().encode(s))
    if (typeof Buffer !== 'undefined') return Array.from(Buffer.from(s, 'utf8'))
    const out = []
    for (let i = 0; i < s.length; i += 1) {
      let c = s.charCodeAt(i)
      if (c < 0x80) out.push(c)
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f))
      else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f))
    }
    return out
  }

  function bitLength(n) {
    let len = 0
    while (n) { len += 1; n >>>= 1 }
    return len
  }

  function pickVersion(byteLen) {
    for (let v = 1; v <= 10; v += 1) {
      const ccBits = v < 10 ? 8 : 16
      const needed = 4 + ccBits + byteLen * 8
      if (needed <= DATA_CODEWORDS[v] * 8) return v
    }
    throw new Error('qr: 内容超过版本 10-L 容量（' + byteLen + ' 字节）')
  }

  function buildCodewords(bytes, version) {
    const capacityBits = DATA_CODEWORDS[version] * 8
    const ccBits = version < 10 ? 8 : 16
    const bits = []
    const push = (value, len) => {
      for (let i = len - 1; i >= 0; i -= 1) bits.push((value >>> i) & 1)
    }
    push(4, 4)                    // 字节模式
    push(bytes.length, ccBits)
    for (const b of bytes) push(b, 8)
    for (let i = 0; i < 4 && bits.length < capacityBits; i += 1) bits.push(0)
    while (bits.length % 8) bits.push(0)

    const data = []
    for (let i = 0; i < bits.length; i += 8) {
      let byte = 0
      for (let j = 0; j < 8; j += 1) byte = (byte << 1) | bits[i + j]
      data.push(byte)
    }
    let pad = 0xec
    while (data.length < DATA_CODEWORDS[version]) {
      data.push(pad)
      pad = pad === 0xec ? 0x11 : 0xec
    }

    const blocks = BLOCKS[version]
    const dataLen = DATA_CODEWORDS[version] / blocks
    const eccLen = ECC_PER_BLOCK[version]
    const chunks = []
    for (let i = 0; i < blocks; i += 1) {
      const chunk = data.slice(i * dataLen, (i + 1) * dataLen)
      chunks.push({ data: chunk, ecc: eccBytes(chunk, eccLen) })
    }

    const out = []
    for (let i = 0; i < dataLen; i += 1) for (const b of chunks) out.push(b.data[i])
    for (let i = 0; i < eccLen; i += 1) for (const b of chunks) out.push(b.ecc[i])
    return out
  }

  /* ---------------- 矩阵 ---------------- */

  function formatBits(mask) {
    const data = (ECL_L << 3) | mask
    let d = data << 10
    while (bitLength(d) - bitLength(FORMAT_G15) >= 0) {
      d ^= FORMAT_G15 << (bitLength(d) - bitLength(FORMAT_G15))
    }
    return ((data << 10) | d) ^ FORMAT_MASK
  }

  function versionBits(version) {
    let d = version << 12
    while (bitLength(d) - bitLength(VERSION_G18) >= 0) {
      d ^= VERSION_G18 << (bitLength(d) - bitLength(VERSION_G18))
    }
    return (version << 12) | d
  }

  function maskAt(mask, r, c) {
    switch (mask) {
      case 0: return (r + c) % 2 === 0
      case 1: return r % 2 === 0
      case 2: return c % 3 === 0
      case 3: return (r + c) % 3 === 0
      case 4: return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0
      case 5: return ((r * c) % 2) + ((r * c) % 3) === 0
      case 6: return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0
      default: return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0
    }
  }

  function finder(m, res, row, col) {
    const n = m.length
    for (let r = -1; r <= 7; r += 1) {
      for (let c = -1; c <= 7; c += 1) {
        const rr = row + r
        const cc = col + c
        if (rr < 0 || rr >= n || cc < 0 || cc >= n) continue
        res[rr][cc] = true
        const on =
          (r >= 0 && r <= 6 && (c === 0 || c === 6)) ||
          (c >= 0 && c <= 6 && (r === 0 || r === 6)) ||
          (r >= 2 && r <= 4 && c >= 2 && c <= 4)
        m[rr][cc] = on
      }
    }
  }

  function buildMatrix(version, codewords, mask) {
    const n = version * 4 + 17
    const m = []
    const res = []
    for (let i = 0; i < n; i += 1) {
      m.push(new Array(n).fill(false))
      res.push(new Array(n).fill(false))
    }

    finder(m, res, 0, 0)
    finder(m, res, 0, n - 7)
    finder(m, res, n - 7, 0)

    for (let i = 8; i < n - 8; i += 1) {
      const on = i % 2 === 0
      m[6][i] = on; res[6][i] = true
      m[i][6] = on; res[i][6] = true
    }

    const pos = ALIGN[version]
    for (const a of pos) {
      for (const b of pos) {
        if ((a === 6 && b === 6) || (a === 6 && b === n - 7) || (a === n - 7 && b === 6)) continue
        for (let r = -2; r <= 2; r += 1) {
          for (let c = -2; c <= 2; c += 1) {
            const rr = a + r
            const cc = b + c
            res[rr][cc] = true
            m[rr][cc] = Math.abs(r) === 2 || Math.abs(c) === 2 || (r === 0 && c === 0)
          }
        }
      }
    }

    // 格式信息（15 位，两份）+ 固定深色模块
    const fmt = formatBits(mask)
    for (let i = 0; i < 15; i += 1) {
      const on = ((fmt >> i) & 1) === 1
      if (i < 6) { m[i][8] = on; res[i][8] = true }
      else if (i < 8) { m[i + 1][8] = on; res[i + 1][8] = true }
      else { m[n - 15 + i][8] = on; res[n - 15 + i][8] = true }

      if (i < 8) { m[8][n - i - 1] = on; res[8][n - i - 1] = true }
      else if (i < 9) { m[8][15 - i - 1 + 1] = on; res[8][15 - i - 1 + 1] = true }
      else { m[8][15 - i - 1] = on; res[8][15 - i - 1] = true }
    }
    m[n - 8][8] = true
    res[n - 8][8] = true

    // 版本信息（版本 >= 7，18 位，两份）
    if (version >= 7) {
      const vb = versionBits(version)
      for (let i = 0; i < 18; i += 1) {
        const on = ((vb >> i) & 1) === 1
        const rr = Math.floor(i / 3)
        const cc = (i % 3) + n - 8 - 3
        m[rr][cc] = on; res[rr][cc] = true
        m[cc][rr] = on; res[cc][rr] = true
      }
    }

    // 数据区之字形填充（跳过功能模块），掩码只作用于数据位
    let row = n - 1
    let inc = -1
    let bitIndex = 7
    let byteIndex = 0
    for (let col = n - 1; col > 0; col -= 2) {
      if (col === 6) col -= 1
      for (;;) {
        for (let k = 0; k < 2; k += 1) {
          const cc = col - k
          if (res[row][cc]) continue
          let dark = false
          if (byteIndex < codewords.length) dark = ((codewords[byteIndex] >>> bitIndex) & 1) === 1
          if (maskAt(mask, row, cc)) dark = !dark
          m[row][cc] = dark
          bitIndex -= 1
          if (bitIndex < 0) { byteIndex += 1; bitIndex = 7 }
        }
        row += inc
        if (row < 0 || row >= n) {
          row -= inc
          inc = -inc
          break
        }
      }
    }
    return m
  }

  /* ---------------- 掩码罚分 ---------------- */

  function penalty(m) {
    const n = m.length
    let score = 0

    for (let i = 0; i < n; i += 1) {
      let runH = 1
      let runV = 1
      for (let j = 1; j < n; j += 1) {
        if (m[i][j] === m[i][j - 1]) runH += 1
        else { if (runH >= 5) score += 3 + (runH - 5); runH = 1 }
        if (m[j][i] === m[j - 1][i]) runV += 1
        else { if (runV >= 5) score += 3 + (runV - 5); runV = 1 }
      }
      if (runH >= 5) score += 3 + (runH - 5)
      if (runV >= 5) score += 3 + (runV - 5)
    }

    for (let r = 0; r < n - 1; r += 1) {
      for (let c = 0; c < n - 1; c += 1) {
        const v = m[r][c]
        if (v === m[r][c + 1] && v === m[r + 1][c] && v === m[r + 1][c + 1]) score += 3
      }
    }

    const patA = [true, false, true, true, true, false, true, false, false, false, false]
    const patB = [false, false, false, false, true, false, true, true, true, false, true]
    const scan = (get) => {
      for (let a = 0; a < n; a += 1) {
        for (let b = 0; b <= n - 11; b += 1) {
          let okA = true
          let okB = true
          for (let k = 0; k < 11; k += 1) {
            const v = get(a, b + k)
            if (v !== patA[k]) okA = false
            if (v !== patB[k]) okB = false
          }
          if (okA) score += 40
          if (okB) score += 40
        }
      }
    }
    scan((r, c) => m[r][c])
    scan((c, r) => m[r][c])

    let dark = 0
    for (let r = 0; r < n; r += 1) for (let c = 0; c < n; c += 1) if (m[r][c]) dark += 1
    const total = n * n
    score += Math.floor(Math.abs(dark * 20 - total * 10) / total) * 10
    return score
  }

  /* ---------------- 对外 ---------------- */

  function encode(text) {
    const bytes = utf8(text)
    const version = pickVersion(bytes.length)
    const codewords = buildCodewords(bytes, version)
    let best = null
    for (let mask = 0; mask < 8; mask += 1) {
      const modules = buildMatrix(version, codewords, mask)
      const score = penalty(modules)
      if (!best || score < best.score) best = { modules: modules, mask: mask, score: score }
    }
    return {
      version: version,
      size: version * 4 + 17,
      mask: best.mask,
      modules: best.modules
    }
  }

  function render(canvas, text, sizePx) {
    if (!canvas || typeof canvas.getContext !== 'function') throw new Error('qr: render 需要 canvas')
    const qr = encode(text)
    const size = qr.size
    const quiet = 4
    const target = sizePx > 0 ? sizePx : 180
    const scale = Math.max(1, Math.floor(target / (size + quiet * 2)))
    const dim = (size + quiet * 2) * scale
    canvas.width = dim
    canvas.height = dim
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('qr: canvas 没有 2d 上下文')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, dim, dim)
    ctx.fillStyle = '#000000'
    for (let r = 0; r < size; r += 1) {
      for (let c = 0; c < size; c += 1) {
        if (!qr.modules[r][c]) continue
        ctx.fillRect((c + quiet) * scale, (r + quiet) * scale, scale, scale)
      }
    }
    return qr
  }

  function toSvg(text, sizePx) {
    const qr = encode(text)
    const quiet = 4
    const dim = qr.size + quiet * 2
    const target = sizePx > 0 ? sizePx : 180
    const parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="' + target + '" height="' + target +
      '" viewBox="0 0 ' + dim + ' ' + dim + '" shape-rendering="crispEdges">',
      '<rect width="' + dim + '" height="' + dim + '" fill="#fff"/>']
    for (let r = 0; r < qr.size; r += 1) {
      for (let c = 0; c < qr.size; c += 1) {
        if (!qr.modules[r][c]) continue
        parts.push('<rect x="' + (c + quiet) + '" y="' + (r + quiet) + '" width="1" height="1" fill="#000"/>')
      }
    }
    parts.push('</svg>')
    return parts.join('')
  }

  return { encode: encode, render: render, toSvg: toSvg, utf8: utf8 }
})
