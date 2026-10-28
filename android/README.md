# Ling · Android 公开源码

这是 Ling Android 客户端的**公开源码副本**，不是官方发布仓库，也不是应用商店分发包。仓库里只有可公开的源码、资源与测试；签名材料、内测密钥、官方更新地址和生产配置都未进入本目录。

- 版本：`2.7.3`（`versionCode 273`）
- 包名：`com.chun.lingzhu.opensource`
- 入口 Activity：`com.chun.lingzhu.ChatActivity`（桌面图标；`MAIN` / `LAUNCHER`）
- 自有代码许可：**GNU GPL v3 only**（SPDX：`GPL-3.0-only`），版权人 **Ling contributors**，年份 **2026**，许可证全文见 [LICENSE](LICENSE)（GNU 官方 GPLv3 正文逐字原文）；改用 GPL 不撤销此前按 MIT 给出的授权，当时已取得的副本仍可按 MIT 使用
- 第三方组件与词表：见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 与官方 App 的关系

本副本使用**独立的 applicationId** `com.chun.lingzhu.opensource`，因此：

- 不能覆盖安装、升级或替换官方 `com.chun.lingzhu`；
- 与官方 App 的数据目录、SharedPreferences 相互隔离；
- 即使有人误用官方签名密钥，包名不同也不会触发同签名覆盖升级。

版本号 `2.7.3`（273）标明源码对应的版本；它**不代表**本副本可以覆盖官方安装，也不代表已经完成本仓库的发布验收。

## 构建前置

| 项目 | 要求 |
|---|---|
| JDK | 17（`sourceCompatibility` / `jvmTarget` 均为 17） |
| Gradle | 8.14（系统安装；本目录不含 wrapper） |
| Android SDK Platform | 35（`compileSdk = 35`、`targetSdk = 35`） |
| `minSdk` | 26 |
| AGP / Kotlin | 8.7.3 / 2.0.21（固定在根 `build.gradle.kts`） |

**为什么没有 `gradlew` / `gradle-wrapper.jar`**：本目录不带 wrapper，请直接使用系统安装的 Gradle 8.14：

```bash
gradle --version   # 应为 8.14
```

如果你希望仓库自带 wrapper，可在本机用 Gradle 8.14 执行一次 `gradle wrapper --gradle-version 8.14`，再提交生成的文件；这属于可选的本地操作。

**只有一种构建变体**：本副本已删除 `flavorDimensions` / `productFlavors`（原发行版的渠道 flavor 与构建期内置接口注入一并移除），因此不存在 `internal` / `public` 之类变体后缀，所有任务都不带渠道名。

## 构建

```bash
cd android

# 1. 指向本机 Android SDK（local.properties 已被 .gitignore 排除，不要提交）
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

# 2. 可选：清理旧产物
gradle clean

# 3. 单元测试（JVM）
gradle testDebugUnitTest --console=plain

# 4. Debug 包（无需任何密钥）
gradle assembleDebug --console=plain

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

以上步骤以系统 Gradle 8.14 为准。`2.7.3` 在这份公开副本上（无 `keystore.properties`）的实际执行：`gradle testDebugUnitTest assembleDebug` **构建成功**（约 36 秒）。

- 编译：主源码与测试源码都编译通过。
- `assembleDebug` 产出 debug APK：15,315,144 字节，`applicationId=com.chun.lingzhu.opensource`，`versionName 2.7.3` / `versionCode 273`，`sha256 972f938ab9706cf7f060a0d1495ff55493a2227be9270d7c81e9cbd175c229d2`。包内 `assets/` 含 GPLv3 正文与 notices，词表只有 `junior/gaokao/cet4/cet6/ielts` 五套（无 `kaoyan*`）。**这是调试签名包，不是生产签名，不能覆盖官方 App。**
- 单元测试：153 个测试类、**2380 项，0 失败 / 0 错误 / 0 跳过**。
- release 签名路径**没有**产出：本机没有 `keystore.properties`，release 产物出口由 `checkReleaseSigning` 拦下并明确报错（符合设计）。本目录没有签出、安装或分发过 release 包。
- 设备烟测（Android 14 专用只读模拟器，进程 5598）：安装后冷启动 `ChatActivity` 正常；保存笔记后杀进程冷启动仍可见；未填接口时「检测连接」如实提示缺少接口地址 / 模型，没有假成功；整轮 logcat 无 `FATAL EXCEPTION`、无 `ANR in`。

烟测只覆盖主干路径（启动、配置引导、记事持久化、空接口提示、导航），不等于全面功能验收；没有接真实供应商，也没有做多项设备兼容性验证。

### Release 包与签名

release 变体产生产物的四个出口（`package<Variant>`、`package<Variant>Bundle`、`package<Variant>UniversalApk`、`sign<Variant>Bundle`）都依赖执行阶段的 `checkReleaseSigning`。缺失材料会被检查两次：配置阶段决定是否建 `signingConfigs.release`，执行阶段在 `checkReleaseSigning` 里重新读盘再验一次。以下任一情况都会让该任务抛 `GradleException`、打包失败：`keystore.properties` 不存在；缺 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 任一项；`storeFile` 指向的密钥库文件不存在；配置阶段没能建出 `signingConfigs.release`（例如 keystore 是在配置之后才放进去的，重跑一次构建即可）。`storeFile` 的相对路径按 `android/`（`keystore.properties` 所在目录）解析。材料齐备时 `signingConfigs.release` 才被创建并用于 release 变体。**不会静默退回 debug 签名，也不会产出未签名的 release 包**；闸门保证的是「材料齐备且签名配置已绑定」，真正的签名由 AGP 完成，本仓库没有实际签出并安装过 release 包。签名材料只放在本机，不进入仓库：

1. 自己生成一个 release keystore（不要复用官方私钥）。
2. 在 `android/keystore.properties` 写入你自己的配置（该文件已被忽略）：

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=<your-store-password>
keyAlias=<your-key-alias>
keyPassword=<your-key-password>
```

3. 再执行：

```bash
gradle assembleRelease --console=plain
# 产物：app/build/outputs/apk/release/app-release.apk
```

仓库中**没有**官方私钥、官方口令模板或任何可用的签名凭据。用官方密钥签出的包也不会覆盖官方 App，因为 applicationId 不同；官方覆盖升级仍只能由官方发布流程完成。

### 可复现性边界

本目录的 debug 构建已实测可产出 APK（见上文），release 则必须先自备签名材料；两种产物都**不承诺字节级可复现**：没有 Gradle wrapper、依赖校验和或依赖锁定，APK 签名与 ZIP 时间戳也不固定。对外应表述为「可构建」，不要表述为「可逐字节复现官方包」。

## 密钥与更新

- **API Key 全部由用户自填**：本副本不内置任何模型、DeepL、翻译服务或语音服务的 Key。原发行版的构建期预设注入（内置接口 / DeepL Key / 预设方案）和运行期 preset 分支已整体移除，默认翻译引擎因此是用户自己的 AI 接口。
- **语音服务的 Key 分开保存**：实时转写的豆包 / 阿里云 Key 与语音对话的腾讯云凭据互不覆盖，都存在应用私有的独立槽位里（见下文「实时转写」与「语音对话」）。
- **没有官方更新源**：`UPDATE_MANIFEST_URL` 为空。设置页的「更新清单地址」留空即不检查更新；填入你自己的静态 JSON 地址后才启用。公开 fork 不会拉取维护者的官方 APK。
- 应用仍保留用户自填接口、自建更新清单的能力；请自行确认所用服务和清单的合法性与完整性。

## 实时转写（豆包 / 阿里云）

「实时转写」只做语音 → 文字：选哪一档音源就只把那一路音频送去转写，不额外上传别的声音，不做建议，音频与转写文字都不写盘。

- **两家服务商，没有第三家、没有自动切换**：豆包（火山引擎）流式 ASR，新版控制台 API Key 走 `X-Api-Key`；阿里云百炼 paraformer 实时语音识别，走 `Authorization: Bearer <Key>`。两家的 Key 各自保存，一家失效不影响另一家；地址与模型是固定常量，不接受外部覆盖，Key 只会发给这两个域名。
- **三档声音来源**：麦克风、系统播放的声音、两路混录。三档最终都是 16 kHz 单声道 s16le PCM，走同一个出口。后两档依赖 Android 10（API 29）起的播放捕获与 `MediaProjection` 授权，低版本**明确不可选**并给出原因，绝不悄悄改录麦克风。
- **同意按「服务商 × 音源」分别保存**：换服务商或换音源都需要重新同意，不存在「同意过一家就等于同意另一家」。
- **Key 加密存放**：用 AndroidKeyStore 的 AES-256-GCM 封在应用私有目录，密文格式为 `[版本][12 字节 IV][密文 + 16 字节 tag]`，AAD 绑定服务商；输入框始终遮蔽，已保存的从不回显，删除有二次确认。
- 采集由前台服务承担，只在按下「开始」时启动，不随开机自启。

## 语音对话（腾讯识别与合成 + 现有大模型）

「语音对话」是另一条独立链路：**麦克风 → 腾讯云实时语音识别 → 现有配置的大模型（流式）→ 腾讯云语音合成 → 手机扬声器**。腾讯不进转写的服务商选择器。

- **凭据**：腾讯云 AppId / SecretId / SecretKey 三项整套保存，用与转写相同的 AndroidKeyStore AES-256-GCM 实现，但使用单独的 `tencent` 槽位与 AAD，与转写两家的 Key 互不影响；三项输入框全程遮蔽、已保存的不回显，三项留空即沿用已存，删除前二次确认并先停对话。
- **声音参数按腾讯官方语义，不自创倍率**：
  - 音色：腾讯 `VoiceType` 数字 id，默认 `101001`，页内可改，只接受正整数；
  - 语速 `Speed`：范围 **[-2, 6]**，0 为正常语速；这是**官方参数而不是「1.0 倍速」那种倍率**（-2 偏慢、6 最快），页内按 0.5 一档存；
  - 音量：**本机播放增益 0 ~ 100%**（0 = 静音），只作用于本应用自己的 `AudioTrack`，**不发给腾讯**，云端合成没有这一档。
  - 三项都在本机普通设置里（非密钥），下次进入沿用。
- **对话行为**：麦克风常开、回答边说边合成；开口说话（识别出非空白且不像自己正在念的内容）会打断本轮，撤掉模型请求、待合成句子、正在合成的段落与已排进扬声器的音频；迟到的回调按代次丢弃，不会把上一轮答案念出来。同一段识别结果按 segmentId 去重、定稿去抖，不会变成两次请求。
- **同意与开销**：开始前必须单独同意（与转写的同意不共用），页内写明三处都在**你自己的账号**里计费：腾讯识别按时长、腾讯合成按字符、大模型按 token。不保存音频、不保存对话文字、不进聊天历史、不写文件、不参与同步。
- **释放**：麦克风、识别连接、模型请求、合成连接、扬声器、音频焦点与音频模式在停止、离开页面、权限或连接出错时全部释放。这一版没有为语音对话配前台服务，**离开页面即停**，不会在后台继续听。
- **回声消除只报状态，不做保证**：录音源用 `VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION`，并尝试启用系统 `AcousticEchoCanceler`；界面按系统实际回报显示「已启用 / 未启用 / 无此能力」。**没有在任何物理设备上验证过 AEC 的实际效果**，外放时仍可能把自己的声音识别成用户输入，界面明确建议戴耳机。

## 内置词库与版权

本副本只包含 5 个 Wiktionary / Project Gutenberg 词频表：

`junior.txt`、`gaokao.txt`、`cet4.txt`、`cet6.txt`、`ielts.txt`

它们以 **CC BY-SA 3.0** 分发，署名与分级说明见 `app/src/main/assets/wordbank/LICENSE.txt`。这些词表是独立随附的数据资产，本目录的 GPL-3.0-only 代码许可不覆盖它们，也不改变其原有授权；本文件不声称两者的授权可以互换或必然兼容。

原发行版中的考研三级词库（`kaoyan_bikao.txt`、`kaoyan_jichu.txt`、`kaoyan_chaogang.txt`）著作权归原出版方，授权链不完整，**未包含**在本目录。源码里新装默认勾选的分级是 `cet4`（`WordBank.DEFAULT_LEVEL`），实际可选范围按 `assets/wordbank/` 里真实存在的文件算，不会指向缺失资产；如维护者改回 `junior`，以源码常量为准。富文本词库解析器仍保留，用户可自行导入合法来源的词库。

## 测试范围与执行记录

`2.7.3` 在这份公开副本上执行 `testDebugUnitTest`：153 个测试类、**2380 项，0 失败 / 0 错误 / 0 跳过**。这个数字比上游生产树引用的计数少，原因是本副本按版权与机制调整了用例集合，见下表；它不是「全量等价验收」，也不与上游数字做对比结论。

更早的历史记录（不与上面的数字相加，也不能当成 2.7.3 的结果）：2.0.9 当时 `testDebugUnitTest` + `assembleDebug` 通过，JUnit 汇总 1289 tests / 0 失败，那版 APK 内含 MIT 全文与 4 份 NOTICE、不含 `kaoyan*`；2.0.9 也在 Android 14 专用只读模拟器（端口 5582）完成安装并启动 `ChatActivity`。这两条描述的是当时按 MIT 分发的产物，不是当前 GPL-3.0-only 的许可状态。

为公开副本调整过的测试边界（按源码现状）：

| 处理 | 文件 | 原因 |
|---|---|---|
| 未收录 | `LayoutPerfTest.kt` | 直接读取 `assets/wordbank/kaoyan*` 做全量性能基线 |
| 未收录 | `WordbankFixVerifyTest.kt` | 逐条校验考研词库的原文修复内容，属于版权语料扫描 |
| 未收录 | `KaoyanBreakScanTest.kt` | 扫描 `kaoyan*` 词库输出断行诊断 |
| 未收录 | `PresetTest.kt` | 测试已移除的「构建期内置密钥」机制 |
| 未收录 | `AnswerFreeformWiringTest.kt` | 断言已过时的接线 |
| 换成自撰样本 | `KaoyanBackTest` / `WrapLayoutTest` / `RichDeckTest` / `KaoyanSenseNumberTest` | 原用例读受限词库原文，改为 fixture，仅覆盖解析 / 排版逻辑 |

`WordbankHygieneScanTest` 保留：它扫描 `assets/wordbank/` 下实际存在的文件做数据体检，不再涉及未分发的考研词库。换成自撰 fixture 的 4 个类（`KaoyanBackTest` / `WrapLayoutTest` / `RichDeckTest` / `KaoyanSenseNumberTest`）只覆盖解析 / 排版逻辑，不覆盖原受限语料的真实内容，因此在本副本通过也不代表对上游语料的行为等价。

## 已实现的主要能力（按源码）

- 屏幕识别与做题：先给「## 答案」作为面板锚点，再按题型写依据 / 公式 / 步骤；条件缺失时说明缺哪一项，不补条件编题。追问优先针对本轮并允许更正上一轮，`Conversation.EXTRA_SHOTS_MAX = 3` 限制附加新图数量。
- 输出预算与完整性：识别按模式分级——通用 `2048`、做题与自定义 `8192`；「纯小白」在基准上乘 `1.5`（做题 12288、通用 3072）。翻译预算 `8192`。接口上限更小时按 `max_tokens` 报错退回 `4096` 重试一次。`ApiClient` 区分正常结束与截断 / 意外 EOF，截断或意外结束的结果不写正常缓存、不记入追问对话。
- 翻译：AI / DeepL / 百度三种引擎加「自动」顺序，翻译缓存键带提示词版本。
- 学习与记事：知识卡与 FSRS 复习、学习计划、Anki `.apkg` 导入（`anki/` 子包，含 `collection.anki21b` 的 zstd 解压路径）、笔记 / 待办 / 提醒、学习数据面板、知识图谱、模块管理。
- 专注与陪伴：专注守护支持「指定学习应用」与「屏幕内容」两种判定，屏幕判定只在本机分析版面、不上传截图；陪伴气泡用本地短句，不联网。
- 互联：局域网同步（`ling-sync://` 配对串）与手机常连（MCP）页。

## 安全与提交规则

- `.gitignore` 排除 `keystore.properties`、`*.jks`、`local.properties`、`lingzhu-internal.properties`、构建产物等。
- `android/.githooks/pre-commit` 会拒绝包含签名文件、私钥标记、长 API Key 或 Bearer 令牌的提交。**在仓库根目录**（即 `android/` 的上一级）执行：

```bash
git config core.hooksPath android/.githooks
```

- `android/` 是根目录 Git 仓库的子目录，不是独立仓库。`core.hooksPath` 按仓库根目录解析，因此写 `android/.githooks` 而不是 `.githooks`；所有 `git` 命令同样应在仓库根目录执行，暂存区中的本目录路径形如 `android/app/...`。
- 本目录公开的是源码：不构成安全审计，也不代表已对真实服务商、真实设备做过端到端验收。
- **分发 APK 时的 GPL 义务**：本目录只是源码。把构建出的 APK 交给他人时，GPLv3 第 6 条要求同时提供与该二进制对应的完整源码（修改版则提供修改版源码），或按第 6 条作出有效的书面提供承诺。仅给出 <https://github.com/Nirxzzz/Ling> 这一地址不能证明它与某个具体 APK 严格对应，也不自动满足第 6 条；随包的 `app/src/main/assets/LICENSE` 与 `THIRD_PARTY_NOTICES.md` 提供许可文本，但不会替分发者完成源码提供义务。

## 目录结构

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── LICENSE          # 自有代码 GPLv3 全文（随 APK 分发，与根 LICENSE 逐字节一致）
│       │   │   ├── THIRD_PARTY_NOTICES.md
│       │   │   ├── licenses/        # Apache-2.0、MIT、Bouncy Castle、BSD-2-Clause 等第三方全文
│       │   │   └── wordbank/        # 5 个 CC BY-SA 词表 + LICENSE.txt
│       │   ├── java/com/chun/lingzhu/
│       │   │   ├── anki/            # Anki 包解析 / 导入
│       │   │   ├── keepalive/       # 后台保活引导
│       │   │   └── voice/           # 实时转写 + 语音对话（腾讯 ASR/TTS、豆包、阿里云）
│       │   └── res/                 # 图标、颜色 / 样式 / 无障碍配置
│       └── test/java/com/chun/lingzhu/
├── licenses/                        # Apache-2.0、BSD-2-Clause、MIT(ts-fsrs)、Bouncy Castle
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── CHANGELOG.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── .gitignore
└── .githooks/pre-commit
```
