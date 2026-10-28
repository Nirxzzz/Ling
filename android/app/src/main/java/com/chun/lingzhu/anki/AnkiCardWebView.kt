package com.chun.lingzhu

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Collections

/**
 * v2.7 Anki 卡面：一个**只认自己虚拟域名**的 WebView。
 *
 * ## 为什么不直接把 Anki 的 HTML 塞进 TextView
 * 卡面是 HTML/CSS：模板里有表格、图片、音频、`{{FrontSide}}` 带过来的 `<hr id=answer>`、
 * cloze 的 `<span class=cloze>`、提示用的 `<details>`。拍平成纯文本 = 丢格式、
 * 图片音频全没了，等于把牌组做残。所以真渲染，但必须关在笼子里：
 *
 * ## 笼子由四件事组成
 * 1. **没有网络**：文档自带 CSP（`default-src 'none'`，只放行本虚拟域名），
 *    且 [WebViewClient.shouldInterceptRequest] 对每个 http(s) 请求都给响应（素材或 404），
 *    所以没有请求能落到真正的网络上。卡面里的 `<img src="https://…">` 只会看到 404，
 *    不会替牌组作者发请求、也不会泄露 IP。
 *    只有两类返回 null（都是交给引擎、都不联网）：**主文档本身**（内容已由
 *    `loadDataWithBaseURL` 喂好，再拦一次等于把整张卡 404 成白板），以及
 *    `data:` / `blob:` / `about:`（模板内联的 base64 图片靠它，拦掉就是静默丢图）。
 * 2. **没有文件访问**：`allowFileAccess` / `allowContentAccess` / `allowFileAccessFromFileURLs`
 *    全关，而且文档根本不从 `file://` 载入 —— 底址是虚拟域名 [BASE_URL]。
 *    素材由 [shouldInterceptRequest] 从 `AnkiDeckStore.mediaDir()` 里按**规范路径**取。
 * 3. **没有桥**：从不 `addJavascriptInterface`。模板自带的 `<script>` 照常是允许的
 *    （Anki 生态里确实有纯前端模板逻辑），但它只能在上面这个无网、无文件、无桥的沙箱里跑，
 *    拿不到 App 的任何对象。素材包里的 `.js` 也能被 `<script src>` 引到（[mimeOf] 给它
 *    `application/javascript`）—— 这不额外扩权：内联脚本本来就允许，同一段代码写进模板还是
 *    写进 `.js` 文件，能力完全一样，都关在这个笼子里。
 * 4. **不自动跳外站**：WebView 自己从不导航（[WebViewClient.shouldOverrideUrlLoading] 恒返回 true）。
 *    只有**带用户手势**的 http(s) 点击才经 [onExternalLink] 交给系统浏览器；脚本
 *    `location.href = …`、`<meta http-equiv=refresh>` 这类零点击跳转一律吞掉 —— 否则牌组作者
 *    就能让 App 在后台弹出任意网页。子框架（iframe）的导航根本不批（配合 CSP `frame-src 'none'`）。
 *
 * ## 素材路径校验
 * 只有 `https://anki.local/<扁平文件名>` 这一种形态能命中，且命中前要过
 * [resolve]：解码 → 规范化（`canonicalFile` 会展开软链和 `..`）→ 判断父目录是否**就是**
 * 素材根目录。于是 `../../databases/x`、绝对路径、软链指向别处、子目录嵌套，全部不通过。
 * 拿不到素材就返回 404，并把文件名回调给页面显示「缺素材」，不静默吞掉（内容缺失必须可见）。
 */
@SuppressLint("SetJavaScriptEnabled")
class AnkiCardWebView(ctx: Context) : WebView(ctx) {

    /**
     * **真人点了一下**卡面里的 http(s) 链接才回调（交给外部浏览器）。模板脚本自己跳转、
     * `<meta http-equiv=refresh>` 这类「没人点」的导航不会到这里，见 [Client.shouldOverrideUrlLoading]。
     * 在 WebView 的导航线程上调用。
     */
    var onExternalLink: ((String) -> Unit)? = null

    /** 模板引用了、包里却没有的素材名（去重后回调），供页面显示「缺素材」。在拦截线程上调用。 */
    var onMissingMedia: ((String) -> Unit)? = null

    /** 素材根目录（`AnkiDeckStore.mediaDir`）。拦截在别的线程上跑，所以 @Volatile。 */
    @Volatile private var mediaRoot: File? = null
    private val missing: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

    init {
        // Brut.style() 会递归进 ViewGroup 改控件外观；WebView 内部那堆子 View 不归它管。
        tag = Brut.KEEP
        setBackgroundColor(0)
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") setAllowFileAccessFromFileURLs(false)
            @Suppress("DEPRECATION") setAllowUniversalAccessFromFileURLs(false)
            // 断网靠两件事，**不靠 blockNetworkLoads**：那面旗子在部分实现上会把我们自己的
            // 虚拟域名子资源一起掐掉（图片音频全 404，而且只有真机上才看得出来）。
            // 1. [WebViewClient.shouldInterceptRequest] 对每个 http(s) 请求都给响应（素材或 404），
            //    只有主文档与 data:/blob:/about: 交给引擎 —— 这两类都不发网络请求；
            // 2. 文档自带 CSP：[CSP]，`connect-src 'none'` / `frame-src 'none'`，
            //    外站图片脚本音频一律拒 —— XHR / WebSocket 这类绕过拦截的路径也堵死。
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true   // 音频由用户点播放，不自动响
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            textZoom = 100
        }
        webViewClient = Client()
    }

    /**
     * 画一面的卡面。[fragment] 是 Anki 模板渲染出的 **body 片段**（`AnkiTemplate` 保证不含
     * `<html>`/`<style>`），[css] 是笔记类型的原始 CSS 文本（由这里一次性注入），
     * [root] 是 `AnkiDeckStore.mediaDir(collection)`。
     */
    fun show(fragment: String, css: String, root: File?, fg: Int) {
        mediaRoot = root
        missing.clear()
        loadDataWithBaseURL(BASE_URL, document(fragment, css, fg), "text/html", "utf-8", null)
    }

    /** 已发现缺失的素材名（快照） */
    fun missingMedia(): List<String> = synchronized(missing) { missing.toList() }

    private fun document(fragment: String, css: String, fg: Int): String {
        val hex = "#" + Integer.toHexString(fg and 0xFFFFFF).padStart(6, '0')
        val sb = StringBuilder(fragment.length + css.length + 1024)
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">")
        // 我们自己先立 CSP：模板就算自带一条 <meta http-equiv>，多条 CSP 只会**收紧**、不会放宽
        sb.append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(CSP).append("\">")
        sb.append("<style>").append(baseCss(hex)).append("</style>")
        if (css.isNotBlank()) {
            // 笔记类型的 CSS 里理论上可以出现 "</style>"（字符串常量里就可能），
            // 不转义会把文档结构撕开 —— 换成等价转义序列，样式行为不变。
            sb.append("<style>").append(css.replace("</style", "<\\/style", ignoreCase = true)).append("</style>")
        }
        sb.append("</head><body class=\"card\">")
        sb.append(fragment)
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun baseCss(hex: String): String = """
        html,body{margin:0;padding:0;background:transparent;}
        body.card{padding:12px 14px 20px;color:$hex;font-size:16px;line-height:1.55;
          -webkit-text-size-adjust:100%;word-wrap:break-word;overflow-wrap:break-word;}
        img,video{max-width:100%;height:auto;}
        audio{display:block;width:100%;margin:8px 0;}
        hr#answer{border:0;border-top:1px solid currentColor;opacity:.18;margin:14px 0;}
        .cloze{font-weight:600;}
        a{color:inherit;}
        details.hint{margin:6px 0;}
    """.trimIndent()

    // ── 拦截：所有子资源都走这里，不符合规则的一律 404 ─────────────────────

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // 主文档是 loadDataWithBaseURL 直接喂进去的 HTML，不该由我们「取」。
            // 部分 WebView 实现仍会为它回调一次（地址是 BASE_URL）：那一下要是给了 404，
            // 整张卡就是一片空白 —— 所以主框架一律放行给引擎。
            // 主框架的**导航**走的是 shouldOverrideUrlLoading（恒 true），不会从这里漏出去。
            if (request.isForMainFrame) return null
            val method = request.method ?: "GET"
            if (method != "GET" && method != "HEAD") return blocked(405, "Method Not Allowed")
            val url = request.url?.toString()
            val scheme = request.url?.scheme?.lowercase()
            // data:/blob:/about: 是**引擎自己就能出**的内容（典型：模板里内联的 base64 图片
            // `<img src="data:image/png;base64,…">`）。它们碰不到网络，交给引擎处理，
            // 否则一张内联图会被我们 404 掉 —— 而且它不算「虚拟域名下的缺素材」，
            // 用户连提示都看不到，那就是静默丢内容。
            // http/https 一律自己答（虚拟域名给素材，别的域名 404 挡住），
            // 其余 scheme（file:/content:/…）也是 404：文件系统的门在这里彻底焊死。
            if (scheme == "data" || scheme == "blob" || scheme == "about") return null
            val f = resolve(mediaRoot, url)
            if (f == null) {
                noteMissing(url)
                return blocked(404, "Not Found")
            }
            return serve(f, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // 这个 WebView 任何情况下都不导航：它没网，也不是浏览器。
            if (!request.isForMainFrame) return true
            val u = request.url?.toString().orEmpty()
            // 只有**真人点了一下**的外链才交给系统浏览器。模板里的 <script> 能写
            // `location.href = "https://…"`，<meta http-equiv=refresh> 更能零点击跳转，
            // 这些请求的 hasGesture() 是 false —— 一律吞掉，不给牌组作者一个
            // 「后台拉起任意网页」的开关。其它 scheme（intent:/javascript:/file:）直接丢。
            if (request.hasGesture() && !isVirtual(u) &&
                (u.startsWith("https://", true) || u.startsWith("http://", true))
            ) {
                onExternalLink?.invoke(u)
            }
            return true
        }
    }

    private fun serve(f: File, request: WebResourceRequest): WebResourceResponse {
        val mime = mimeOf(f.name)
        // 文本类素材（牌组的 .css / .js）必须带 charset：encoding 给 null 时引擎可能按
        // ISO-8859-1 解，中文与 emoji 会变成乱码 —— 那是看得见的画面损坏。
        val enc = encodingFor(mime)
        val headers = HashMap<String, String>()
        // Content-Type 不手写进头里：引擎会按 mime + encoding 自己生成，
        // 两处都给容易出现「同一个头两行」的歧义。
        headers["Cache-Control"] = "no-store"
        headers["Accept-Ranges"] = "bytes"
        val len = try { f.length() } catch (e: Exception) { -1L }
        if (len < 0L) return blocked(404, "Not Found")   // 取不到长度才是真拿不到
        // len == 0 是合法素材（空占位文件）：照样给 200 + 空体，别谎报 404、也别当成缺素材
        val range = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("range", true) }?.value
        val span = parseRange(range, len)
        return try {
            if (span == null) {
                headers["Content-Length"] = len.toString()
                WebResourceResponse(mime, enc, 200, "OK", headers, FileInputStream(f))
            } else {
                val (start, last) = span
                headers["Content-Range"] = "bytes $start-$last/$len"
                headers["Content-Length"] = (last - start + 1).toString()
                WebResourceResponse(
                    mime, enc, 206, "Partial Content", headers,
                    Bounded(FileInputStream(f), start, last - start + 1),
                )
            }
        } catch (e: Exception) {
            blocked(404, "Not Found")
        }
    }

    private fun noteMissing(url: String?) {
        val u = url ?: return
        if (!isVirtual(u)) return          // 外站请求不叫「缺素材」，那是被策略挡掉的噪音
        // 报给用户的名字要和 resolve 认的名字是同一个（只解码一次），
        // 否则「tiny%20x.png 缺失」和实际文件名 tiny x.png 对不上，用户按名字找不着。
        val path = try { URI(u).rawPath.orEmpty() } catch (e: Exception) { "" }
        val seg = path.substringAfterLast('/')
        if (seg.isBlank()) return
        val name = try { URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { seg }
        if (name.isBlank()) return
        if (missing.add(name)) onMissingMedia?.invoke(name)
    }

    private fun blocked(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, HashMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val HOST = "anki.local"
        const val BASE_URL = "https://anki.local/"

        /**
         * 文档级 CSP。虚拟域名是我们自己拦的，所以默认源只放行它；
         * **模板自带的 `<script>` 仍要能跑**（Anki 生态里确有纯前端模板逻辑），
         * 但脚本没有任何外连通道：`connect-src 'none'`、`frame-src 'none'`、
         * 图片音频只能来自本虚拟域名**或内联 `data:`**（`<img src="data:image/png;base64,…">`
         * 这种自包含内容在真实牌组里确实有，禁掉就是静默丢图；`data:` 不产生任何请求）。
         * `'unsafe-inline'` 是 `<script>` 内联必须的，沙箱里没有桥、没有文件访问、没有网络，
         * 内联脚本拿不到任何 App 对象。
         *
         * `base-uri` / `child-src` / `worker-src` 都**不吃** `default-src` 的兜底，必须写死：
         * 否则模板一句 `<base href="https://evil/">` 就能改掉所有素材的相对解析基准，
         * 一句 `new Worker(...)` 也能绕过脚本源限制。
         */
        val CSP: String = "default-src 'none'; "
            .plus("img-src https://$HOST data:; ")
            .plus("media-src https://$HOST data:; ")
            .plus("style-src 'unsafe-inline' https://$HOST; ")
            .plus("font-src https://$HOST data:; ")
            .plus("script-src 'unsafe-inline' https://$HOST; ")
            .plus("connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; ")
            .plus("object-src 'none'; form-action 'none'; base-uri 'none'")

        // URI.scheme / URI.host 对相对地址（"", "/x", "tiny.png"）是 null。
        // 直接 `uri.scheme.equals(…)` 会 NPE，而 resolve 的调用点是拦截线程，
        // 抛出去就等于「一个畸形 URL 让整个拦截回调崩掉」——所以一律用 `?.`。
        private fun isVirtual(url: String): Boolean {
            val u = try { URI(url) } catch (e: Exception) { return false }
            return u.scheme?.equals("https", true) == true && u.host?.equals(HOST, true) == true
        }

        /**
         * 虚拟地址 → 素材文件。**只接受素材根目录下的直接子文件**，且必须过 `canonicalFile`
         * （软链、`..`、绝对路径、编码过的分隔符全部在这一步现原形）。
         *
         * 只解码**一次**，所以取 `rawPath` 而不是 `path`（后者已经解过一层，再解一次就是
         * 双重解码，`%252e%252e%252f` 会变成 `../`）。解出来的名字里只要还剩 `/`、`\`、NUL，
         * 或者拼出来的路径规范化后父目录不是素材根，就一律拒绝。
         *
         * 纯函数，不碰 Android 类 —— 单测可以直接喂 File + 字符串。
         */
        fun resolve(root: File?, url: String?): File? {
            if (root == null || url == null) return null
            val uri = try { URI(url) } catch (e: Exception) { return null }
            if (uri.scheme?.equals("https", true) != true) return null
            if (uri.host?.equals(HOST, true) != true) return null
            val raw = uri.rawPath ?: return null
            val rel = raw.removePrefix("/")
            if (rel.isEmpty()) return null
            val name = try {
                URLDecoder.decode(rel.replace("+", "%2B"), "UTF-8")   // 「+」是文件名合法字符，不是空格
            } catch (e: Exception) {
                rel
            }
            if (name.isEmpty() || name == "." || name == ".." || name.contains('\u0000')) return null
            if (name.contains('/') || name.contains('\\')) return null
            val base = try { root.canonicalFile } catch (e: Exception) { return null }
            val target = try { File(base, name).canonicalFile } catch (e: Exception) { return null }
            if (target.parentFile?.canonicalFile != base) return null
            return if (target.isFile) target else null
        }

        /** 素材 MIME：按扩展名给够 WebView 用的那几种，认不出就当二进制流 */
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "mp3" -> "audio/mpeg"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "html", "htm" -> "text/html"
            else -> "application/octet-stream"
        }

        /**
         * 文本类素材的字符集：只有这些会被当文本解析，其余（图片/音频/字体）给 null。
         *
         * 不写 charset 时 `WebResourceResponse` 的默认解法是 ISO-8859-1，
         * 于是牌组自带 `.css` 里的中文注释、`.svg` 里的文字会变成乱码 —— 不是缺内容，
         * 是解错了码。这层纯函数化，测试不用起 WebView。
         */
        fun encodingFor(mime: String): String? =
            when {
                mime.startsWith("text/") -> "utf-8"
                mime == "application/javascript" || mime == "application/json" -> "utf-8"
                mime.endsWith("+xml") -> "utf-8"
                else -> null
            }

        /** `bytes=…` 头：只支持单段。认得就返回 [起, 止]（含止），认不得当「给整份」 */
        fun parseRange(header: String?, len: Long): Pair<Long, Long>? {
            if (header == null || len <= 0) return null
            val h = header.trim()
            if (!h.startsWith("bytes=", true)) return null
            val spec = h.substring(6).trim()
            if (spec.isEmpty() || spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return try {
                if (a.isEmpty()) {                       // bytes=-N：最后 N 字节
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) return null
                    val start = (len - n).coerceAtLeast(0)
                    start to (len - 1)
                } else {
                    val start = a.toLongOrNull() ?: return null
                    if (start < 0 || start >= len) return null
                    val end = if (b.isEmpty()) len - 1 else (b.toLongOrNull() ?: return null)
                    if (end < start) return null
                    start to end.coerceAtMost(len - 1)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 从 [skip] 开始、最多读 [limit] 字节的限长流（给 206 分段响应用，不把整个音频读进内存） */
    private class Bounded(private val src: InputStream, skip: Long, private val limit: Long) : InputStream() {
        private var left = limit

        init {
            var remain = skip
            while (remain > 0) {
                val n = try { src.skip(remain) } catch (e: Exception) { 0L }
                if (n <= 0) break
                remain -= n
            }
        }

        override fun read(): Int {
            if (left <= 0) return -1
            val b = src.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val want = if (len.toLong() > left) left.toInt() else len
            if (want <= 0) return 0
            val n = src.read(b, off, want)
            if (n > 0) left -= n
            return n
        }

        override fun available(): Int = if (left > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else left.toInt()

        override fun close() = src.close()
    }
}
