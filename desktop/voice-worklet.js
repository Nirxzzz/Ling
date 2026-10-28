/* voice-worklet.js — 采集用的 AudioWorkletProcessor：把输入混成一路 mono，攒成固定长度再回传。
 *
 * 为什么在 worklet 里混：麦克风与系统回环必须在**同一个音频时钟、同一块 128 帧**上相加，
 * 才会得到一条时间轴。若在主线程对两路各跑一遍重采样再拼，两条流的分块边界不同、
 * 重采样尾巴也各有一份，拼出来的句子会互相错位（还会抢同一个 rsCarry）。
 *
 * 输入：input 0 = 麦克风（纯系统声音时是系统回环），input 1 = 系统回环（仅「会议双方」时接）。
 * 输出：**一路** mono Float32。节点在主线程里只接到 destination 以驱动音频线程，
 *       自身不写 output —— 扬声器不会有回放，也不会把采集到的声音再放出来被二次采到。
 *
 * 电平：每个输入各自乘 gain（两路同时时由主线程传 0.5，各留一半 headroom），相加后硬限幅到
 * ±1。识别服务要的是清楚的人声，这里不做降噪 / 动态压缩 / 自动增益 —— 三个开关都由采集约束
 * 在设备侧决定（麦克风开、系统回环关）。
 *
 * 攒块：process() 每 128 个采样被调一次（48k 时约 375 次/秒），每次 postMessage 会把渲染主线程
 * 的 IPC 队列塞满。这里攒到 1600 采样（16k 下 100ms；48k 下约 33ms）发一回。
 */
class VoiceCaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    const opts = (options && options.processorOptions) || {}
    // 两路同时时主线程传 0.5：各留一半 headroom，相加也不会先越界再限幅
    const g = Number(opts.gain)
    this.gain = g > 0 && g <= 1 ? g : 1
    this.block = 1600
    this.buf = new Float32Array(this.block)
    this.n = 0
    this.stopped = false
    this.port.onmessage = (e) => {
      if (!e || !e.data || e.data.type !== 'stop') return
      // 停采：把手上剩下的半块发出去就不再收；之后不再产出任何声音
      if (this.n > 0) {
        this.port.postMessage({ type: 'block', samples: this.buf.slice(0, this.n) })
        this.n = 0
      }
      this.stopped = true
      this.port.postMessage({ type: 'stopped' })
    }
  }

  process(inputs) {
    if (this.stopped) return true
    const a = inputs[0] && inputs[0][0]
    const b = inputs[1] && inputs[1][0]
    // 两路都没接上（或都已 ended）：什么都不产出。绝不拿静音把块填满 ——
    // 那样「断了一路」在界面上会表现成「安静」，用户看不出问题。
    if (!a && !b) return true
    const len = a ? a.length : b.length
    const g = this.gain
    for (let i = 0; i < len; i++) {
      let v = 0
      if (a) v += a[i] * g
      if (b) v += b[i] * g
      // 限幅：headroom 之后仍可能越界（两路同时到峰值），硬切在 ±1 比溢出回绕好得多
      this.buf[this.n++] = v > 1 ? 1 : (v < -1 ? -1 : v)
      if (this.n === this.block) {
        this.port.postMessage({ type: 'block', samples: this.buf })
        this.buf = new Float32Array(this.block)
        this.n = 0
      }
    }
    return true
  }
}

registerProcessor('voice-capture', VoiceCaptureProcessor)
