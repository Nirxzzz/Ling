/* 玻璃边缘平滑长高（v1.48）—— 答案面板 / 翻译面板共用
 *
 * 先量过：流式期间窗口 setBounds 只跳两次（120→360→480），但 .panel 按内容定高（max-height:100%），
 * 肉眼看到的是玻璃下沿 —— 逐帧采样一次识别，下沿挪了 13 次，其中 12 次一步 >24px（最大 79px），一行一行往下「蹦」。
 *
 * 做法：面板变高时，用 clip-path 先把新长出来的那截裁掉，再逐帧放开（每帧补差值的 30%，约 150ms 到位）。
 *   · 只动 clip-path，不动布局 —— #wrap 的滚动高度、跟读到底、主进程的 resize 全都不受影响，不会互相打架；
 *   · 变矮直接到位（内容已经没了，裁也裁不出过渡），连续长高会从当前露出的位置接着走，不回弹；
 *   · 系统开了「减少动态效果」就不做。 */
(() => {
  const p = document.querySelector('.panel')
  if (!p || !window.ResizeObserver) return
  if (window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches) return
  let shown = 0, target = 0, raf = 0
  const R = getComputedStyle(p).borderTopLeftRadius || '0px'
  const paint = () => {
    const cut = target - shown
    p.style.clipPath = cut > 0.5 ? 'inset(0 0 ' + cut.toFixed(1) + 'px 0 round ' + R + ')' : ''
  }
  const step = () => {
    raf = 0
    shown += Math.max(1, (target - shown) * 0.3)
    if (shown >= target - 0.5) shown = target
    paint()
    if (shown < target) raf = requestAnimationFrame(step)
  }
  new ResizeObserver(() => {
    const h = p.getBoundingClientRect().height
    if (!target) { shown = target = h; return }   // 首次布局：直接到位
    target = h
    if (h <= shown || h - shown < 3) { shown = h; paint(); return }
    paint()   // 这一帧就得裁上，不然新长出来的那截会先整块闪一帧
    if (!raf) raf = requestAnimationFrame(step)
  }).observe(p)
})()
