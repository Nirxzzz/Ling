import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在 keystore.properties（不进补丁目录）；没有这个文件时 release 直接构建失败（见 buildTypes.release）
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}


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
        if (ksProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
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
            // **不许静默退回 debug 签名。**
            //
            // 原来的写法是：
            //     signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // 意思是「找不到 release 签名就用 debug 签名接着编」。
            //
            // 危险在于它**不会失败**：构建成功、APK 产出、大小和 sha256 都正常，
            // 只有装到真机上才会暴露 —— debug 签名的包
            //   · 和已装的 release 版本签名不同，覆盖升级直接 INSTALL_FAILED_UPDATE_INCOMPATIBLE
            //   · 发出去后用户必须卸载重装，笔记数据全靠他自己有没有备份
            //   · 而 CI/构建日志里一个字都看不出来
            //
            // 换台机器、忘了带 keystore.properties、或者只是路径写错，都会踩到。
            // 所以这里改成宁可构建失败，也不产出一个「看起来正常」的错包。
            val rel = signingConfigs.findByName("release")
            if (rel == null) {
                throw GradleException(
                    "缺少 release 签名配置：根目录没有 keystore.properties，或里面缺 storeFile/storePassword/keyAlias/keyPassword。" +
                        "拒绝用 debug 签名打包 —— 那样的 APK 装不上、也覆盖不了已装的正式版。" +
                        "（只想本地跑一下请用 assembleDebug。）"
                )
            }
            signingConfig = rel
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
