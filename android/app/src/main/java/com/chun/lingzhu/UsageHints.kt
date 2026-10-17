package com.chun.lingzhu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 首次使用提示：某个常用动作**第一次被用户真正点击**时，弹一次简短 Toast（非模态、不拦操作、不重放）。
 * 在"真实尝试这个动作"时才调用（例如权限已通过、马上要起识别 / 弹词），缺前置条件那条路不弹。
 *
 * 为什么单独放一个 object：聊天页只是调用方，将来别处（设置页某个入口）想共用同一套「只提示一次」的
 * 也可以直接调 [showOnce]，不用各自复制一份计数逻辑。
 *
 * 存哪儿：就用 App 现成的 `SharedPreferences("lingzhu")`（和 [Prefs] 同一个文件），但**只用一个独立新 key**
 * （[KEY_SEEN]）—— 不去碰 [Prefs] 里任何既有键，也不改那个文件里的其它值。
 *
 * 为什么可靠：记的是「已提示 id 的集合」（[KEY_SEEN]，StringSet）而不是「最后一个 id」，三个动作各自只提示
 * 一次、互不顶掉；写回用现成标准写法 [android.content.SharedPreferences.Editor.apply]（异步落盘、不阻塞
 * 主线程，这里不需要、也不用 commit）；非主线程调用会自动转回主线程再弹。[message] 控制在两行内。
 */
object UsageHints {
    /** 和 [Prefs] 同一个 SharedPreferences 文件，保证跨进程可见；但不复用它的任何键 */
    private const val SP_NAME = "lingzhu"
    /** 本文件里唯一的独立 key：存已经提示过的动作 id（StringSet） */
    private const val KEY_SEEN = "usageHintSeen"

    /**
     * id（动作标识）第一次被调用时弹一次 [message]，之后同一 id 一律不再弹。
     * 非模态、瞬时、不改变任何页面状态 —— 纯提示，绝不拦用户手上的操作。
     */
    fun showOnce(ctx: Context, id: String, message: String) {
        if (id.isEmpty() || message.isEmpty()) return
        // 主线程兜底：调用点都在点击回调里（主线程），这里再保证一次，别在非主线程直接弹 Toast
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { showOnce(ctx, id, message) }
            return
        }
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val seen = sp.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        if (id in seen) return
        // 标准 apply（异步落盘，不阻塞主线程）；getStringSet 返回的是内部集合，必须新建一个再写回
        sp.edit().putStringSet(KEY_SEEN, HashSet(seen).apply { add(id) }).apply()
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }
}
