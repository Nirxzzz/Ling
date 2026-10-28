import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在 keystore.properties（不进补丁目录）。配置阶段只**读状态**、不抛异常 ——
// 没有密钥的机器必须照样能跑 assembleDebug / testDebugUnitTest / help；
// release 打包在执行阶段由 checkReleaseSigning 强制校验（见 android 块之后）。
val ksFile = rootProject.file("keystore.properties")
val ksProps = Properties().apply {
    if (ksFile.isFile) ksFile.inputStream().use { load(it) }
}

// 返回「缺什么」，null 表示 release 签名材料齐备。每次都从磁盘重读：
// 配置阶段建 signingConfig 用一次，执行阶段闸门再验一次，避免中途被改掉。
// 只校验字段是否为空、密钥库文件是否存在，绝不回显密码 / keyAlias 的取值。
fun releaseSigningProblem(keystore: java.io.File): String? {
    if (!keystore.isFile) return "android/keystore.properties 不存在"
    val props = Properties().apply { keystore.inputStream().use { load(it) } }
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .filter { props.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) return "keystore.properties 缺少字段：" + missing.joinToString("、")
    val storeFile = props.getProperty("storeFile").trim()
    if (!keystore.parentFile.resolve(storeFile).isFile) return "storeFile 指向的密钥库文件不存在：$storeFile"
    return null
}

// 配置阶段的结果：只有它为 null 才会建 signingConfigs.release。
val releaseSigningProblemAtConfig: String? = releaseSigningProblem(ksFile)

android {
    namespace = "com.chun.lingzhu"
    compileSdk = 35

    defaultConfig {
        // Fork-specific applicationId: this build cannot overwrite or upgrade the
        // official com.chun.lingzhu app, even if someone reuses its signing key.
        applicationId = "com.chun.lingzhu.opensource"
        minSdk = 26
        targetSdk = 35
        versionCode = 273
        versionName = "2.7.3"

        manifestPlaceholders["appLabel"] = "Ling"
        manifestPlaceholders["translateLabel"] = "Ling 翻译"
        manifestPlaceholders["shareLabel"] = "Ling 识题"
        // The internal-only adb self-test receiver stays unexported in this fork.
        manifestPlaceholders["selftestExported"] = "false"

        buildConfigField("String", "CHANNEL", "\"开源版\"")
        buildConfigField("String", "APP_NAME", "\"Ling\"")
        // No preset endpoints or keys are compiled into this fork. Users fill their own.
        // No built-in update channel: a public fork must not pull the maintainer's
        // official APKs. Users may still enter their own manifest URL in settings.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
    }

    signingConfigs {
        // 四项齐全且密钥库文件真实存在才建这个配置；缺任何一项就不建，
        // release 打包会由 checkReleaseSigning 明确报错，而不是产出一个未签名的包。
        if (releaseSigningProblemAtConfig == null) {
            create("release") {
                storeFile = rootProject.file(ksProps.getProperty("storeFile").trim())
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            // **不许静默退回 debug 签名，也不许产出未签名 APK。**
            //
            // 旧写法找不到 release 签名时会退回 debug 签名配置，缺密钥也**照常构建成功**：
            // APK 产出、大小与 sha256 都正常，只有装到真机上才暴露 —— debug 签名与已装正式版不同，
            // 覆盖升级直接 INSTALL_FAILED_UPDATE_INCOMPATIBLE，用户必须卸载重装、笔记全靠自己备份过；
            // 而构建日志里一个字都看不出来。
            //
            // 但在配置阶段直接 throw GradleException 又走到另一个极端：没有 keystore.properties 的机器
            // 连 assembleDebug / testDebugUnitTest / help 都起不来 —— 这对公开仓库是常态。
            //
            // 所以校验放到执行阶段：只有真正要产出 release 产物的任务依赖 checkReleaseSigning，
            // 缺材料时明确失败；这里保持 signingConfig 为空（AGP 只会生成未签名包，而那些出口已被闸门挡住）。
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }

    // v2.0.7 文档取文新增两个库（PdfBox-Android + POI），它们的 jar 各自带同名 META-INF 文件：
    //   commons-codec / commons-collections4 / commons-math3 都带 META-INF/LICENSE.txt、NOTICE.txt；
    //   三个 bouncycastle jar 都带 META-INF/BC1024KE.SF、BC2048KE.SF（以及同名 .DSA）。
    // 不排除就是 "2 files found with path 'META-INF/..." 直接构建失败。这些文件对 APK 没有任何用处。
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES", "META-INF/ASL2.0",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/versions/**", "**/module-info.class",
                // 后量子算法参数表：SIKE / Picnic 的查找表，5 个 .properties 共 3.96 MB，
                // 占 APK 体积的 29%。SIKE 已被 NIST 淘汰，Picnic 同样未被采用，
                // 而本应用**从不解密 PDF** —— DocumentText 遇到加密件直接抛
                // InvalidPasswordException 拒绝。所以这些表对功能毫无用处。
                // 排除后实测：APK 13.7 MB → 9.7 MB（-28.8%），
                // dex 中 pqc 类引用 902 处完全保留（只删数据文件，未削代码），
                // 2,032 单测 + 89 项真机自检全绿。
                // 注意：只排除数据文件，不能排除整个 org/bouncycastle/** ——
                // bcprov 的 AES/RC4 实现仍被 pdfbox 用来读非加密 PDF 的部分结构。
                "org/bouncycastle/pqc/**"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// release 签名闸门（执行阶段，而不是配置阶段）
//
// 只绑 release 变体自己产生产物的出口任务，名字全部由变体名推出：
//   package<Variant>（APK，签名在打包内部完成）、package<Variant>Bundle（AAB）、
//   package<Variant>UniversalApk（通用 APK）、sign<Variant>Bundle（AAB 签名，早于 AAB 打包）。
// assembleRelease / bundleRelease / installRelease / 直接点任务 都经由它们，
// 所以按变体绑定、不解析命令行里敲了哪些任务名，任何组合都绕不过去。
// debug 与单测任务完全不依赖闸门：没有密钥的机器照样编译、跑测试。
// 配置阶段是否真的建成了 release 签名配置。闸门只看这个布尔值 + 执行期重读的材料状态，
// 不去读 project 的其它状态，行为与「命令行敲了哪些任务」无关。
val releaseSigningConfigured = android.signingConfigs.findByName("release") != null

val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "确认 release 签名材料齐全；缺任何一项都让 release 打包失败，绝不产出未签名或 debug 签名的包"
    doLast {
        // 材料在执行阶段再读一次：中途被改掉也拦得住。
        val missing = releaseSigningProblem(ksFile)
        if (releaseSigningConfigured && missing == null) return@doLast
        // 配置期没有签名配置时 AGP 会产出未签名的 release 包 —— 那也是静默降级，同样必须失败。
        val why = missing
            ?: "release 构建没有绑定签名配置（keystore.properties 在配置阶段还不存在或不合规），重跑一次构建即可"
        throw GradleException(
            "缺少 release 签名配置（$why）。" +
                "本仓库不会退回 debug 签名，也不会产出未签名的 release 包 —— 那样的 APK 装不上、" +
                "也覆盖不了已安装的正式版。请在 android/keystore.properties 写入 " +
                "storeFile/storePassword/keyAlias/keyPassword 后重试；" +
                "只想本地编译、跑测试请用 assembleDebug / testDebugUnitTest。"
        )
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType != "release") return@onVariants
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val gateTargets = setOf(
            "package$suffix", "package${suffix}Bundle",
            "package${suffix}UniversalApk", "sign${suffix}Bundle",
        )
        project.tasks.matching { it.name in gateTargets }
            .configureEach { dependsOn(checkReleaseSigning) }
    }
}

dependencies {
    // Anki 新版 collection.anki21b 使用 Zstd；JDK/Android 无内置解码器，使用带 Android ABI 的 AAR。
    implementation("com.github.luben:zstd-jni:1.5.7-3@aar")
    // Markdown vault 的 YAML frontmatter 需要保留未知字段和嵌套值，避免手写解析器破坏现有文档。
    // 读取方必须使用 SafeConstructor；不允许 YAML 构造任意 Java 对象。
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 只是空壳 —— 换成真实现
    testImplementation("org.json:json:20240303")

    // v2.0.7 文档取文（DocumentText）：不再手写 PDF/font/OLE 解析，改用两个成熟库。
    //  - PdfBox-Android：Android 移植版 PDFBox（桌面版依赖 java.awt.geom，Android 上没有，不能用）。
    //    取文前必须先 DocumentText.init(context) 初始化其 assets 里的 glyph/cmap 资源。
    //  - POI HWPF（poi-scratchpad）：读老式 .doc 的 OLE2 piece table，中文按文档编码还原。
    //    选 4.1.2 而不是 5.x：4.x 的 POIDocument 用自带的 POILogger（默认 NullLogger），
    //    而 5.x 的 POIDocument 直接持有 org.apache.logging.log4j.Logger 字段，Android 上多一层
    //    硬依赖；4.x 的 hwpf/poifs 也完全不碰 java.awt / javax.imageio / javax.xml.stream。
    //    日志走 NullLogger，所以 log4j 1.x 与 commons-logging 都能排除掉。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi-scratchpad:4.1.2") {
        exclude(group = "log4j", module = "log4j")
        exclude(group = "commons-logging", module = "commons-logging")
    }

    // v2.2.0 局域网同步扫码：只用 zxing core（纯 Java，离线解码），预览用系统 Camera2 自写。
    // 不引 zxing-android-embedded：那套要额外相机封装 / 网络补丁，且会打破「零其他第三方 UI 库」。
    implementation("com.google.zxing:core:3.5.3")

    // v2.7.x 实时语音转写：WebSocket 握手要带自定义请求头（豆包 X-Api-Key / 阿里 Authorization），
    // 而 Android 上的 JDK WebSocket 既不给加头、minSdk 26 也没有；自己实现 RFC 6455 分片/掩码/心跳
    // 等于造轮子，所以引 OkHttp。4.12.0 是 Apache-2.0，许可全文随包在 assets/licenses/okhttp-Apache-2.0.txt。
    // 锁 4.12.0（而不是 5.x）：5.x 改了包结构、要求更高 minSdk，本项目不需要。
    // 只引 okhttp 核心，不引 okhttp-android（那套是给旧 Android 上的 TLS/预置请求头用的）。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
