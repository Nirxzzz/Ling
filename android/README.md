# Ling · Android 公开源码

这是 Ling Android 客户端的**公开源码副本**，不是官方发布仓库，也不是应用商店分发包。仓库里只有可公开的源码、资源与测试；签名材料、内测密钥、官方更新地址和生产配置都未进入本目录。

- 版本：`2.7.3`（`versionCode 273`）
- 包名：`com.chun.lingzhu.opensource`
- 入口 Activity：`com.chun.lingzhu.ChatActivity`（桌面图标；`MAIN` / `LAUNCHER`）
- 自有代码许可：MIT，版权人写作 **Ling contributors**（见 [LICENSE](LICENSE)）
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

以上步骤以系统 Gradle 8.14 为准；`2.7.3` 的最终构建与单元测试结果**待主脑在集成后统一执行**，本文件不预写通过结论或通过数量。

### Release 包与签名

`assembleRelease` / `bundleRelease` / `installRelease` 在**没有签名配置时会直接失败**（`GradleException`），不会静默退回 debug 签名。签名材料只放在本机，不进入仓库：

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

本目录可以构建出功能等价的 Debug/Release APK，但**不承诺字节级可复现**：没有 Gradle wrapper、依赖校验和或依赖锁定，APK 签名与 ZIP 时间戳也不固定。对外应表述为“可构建”，不要表述为“可逐字节复现官方包”。

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

它们以 **CC BY-SA 3.0** 分发，署名与分级说明见 `app/src/main/assets/wordbank/LICENSE.txt`。代码的 MIT 许可不覆盖这些词表。

原发行版中的考研三级词库（`kaoyan_bikao.txt`、`kaoyan_jichu.txt`、`kaoyan_chaogang.txt`）著作权归原出版方，授权链不完整，**未包含**在本目录。源码里新装默认勾选的分级是 `cet4`（`WordBank.DEFAULT_LEVEL`），实际可选范围按 `assets/wordbank/` 里真实存在的文件算，不会指向缺失资产；如维护者改回 `junior`，以源码常量为准。富文本词库解析器仍保留，用户可自行导入合法来源的词库。

## 测试范围（历史记录，不是 2.7.3 的验收结果）

> 下面这些是 2.0.9 / 2.0.10 源码整理阶段的真实执行记录，**不是 2.7.3 的验收结果**。2.7.3 的改动没有由本次执行重新构建或运行测试。

- **2.0.9**：已实际执行 `gradle testDebugUnitTest` 与 `gradle assembleDebug`，JUnit XML 汇总 **1289 tests，0 failures / 0 errors / 0 skips**；APK 内含 MIT 全文、三份第三方许可全文与 4 份 NOTICE，不含 `kaoyan*` 资产。
- 2.0.9 在 Android 14 专用只读模拟器（端口 5582）完成真实安装并启动 `ChatActivity`，进程存活，截图已目视。首启未填 API 时提示缺少接口 / 权限，点击「去填写」进入**首次配置引导**，不是直接跳 API 页。未接真实模型，未验证物理振动，也未做全面功能验收。
- **2.7.3**：测试源码已随源码更新（含 `voice/` 下的 `DoubaoProtocolTest`、`VoiceAsrClientTest`、`VoiceSourceTest`），最终执行结果**待主脑在集成后统一执行**；确认前不写通过数量。

为公开副本调整过的测试边界（按源码现状）：

| 处理 | 文件 | 原因 |
|---|---|---|
| 未收录 | `LayoutPerfTest.kt` | 直接读取 `assets/wordbank/kaoyan*` 做全量性能基线 |
| 未收录 | `WordbankFixVerifyTest.kt` | 逐条校验考研词库的原文修复内容，属于版权语料扫描 |
| 未收录 | `KaoyanBreakScanTest.kt` | 扫描 `kaoyan*` 词库输出断行诊断 |
| 未收录 | `PresetTest.kt` | 测试已移除的「构建期内置密钥」机制 |
| 未收录 | `AnswerFreeformWiringTest.kt` | 断言已过时的接线 |
| 换成自撰样本 | `KaoyanBackTest` / `WrapLayoutTest` / `RichDeckTest` / `KaoyanSenseNumberTest` | 原用例读受限词库原文，改为 fixture，仅覆盖解析 / 排版逻辑 |

`WordbankHygieneScanTest` 保留：它扫描 `assets/wordbank/` 下实际存在的文件做数据体检，不再涉及未分发的考研词库。测试数量少于上游生产工程，原因是排除依赖版权语料的用例、删除已移除的预设机制，以及调整过时的接线 / 默认断言——这是明确的范围差异，不是「全量等价验收」。

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

## 目录结构

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── licenses/        # Apache-2.0、MIT、Bouncy Castle、BSD-2-Clause 等
│       │   │   ├── THIRD_PARTY_NOTICES.md
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
