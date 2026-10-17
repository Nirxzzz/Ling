# Ling · Android 开源候选

这是 Ling Android 客户端的**本地隔离开源候选**，不是官方发布仓库，也不是
应用商店分发包。仓库里只有可公开的源码、资源与测试；签名材料、内测密钥、
官方更新地址和生产配置都未进入本目录。

- 版本：`2.0.9`（`versionCode 217`）
- 包名：`com.chun.lingzhu.opensource`
- 入口 Activity：`com.chun.lingzhu.ChatActivity`（桌面图标；`MAIN` / `LAUNCHER`）
- 自有代码许可：MIT，版权人写作 **Ling contributors**（见 [LICENSE](LICENSE)）
- 第三方组件与词表：见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 与官方 App 的关系

本候选使用**独立的 applicationId** `com.chun.lingzhu.opensource`，因此：

- 不能覆盖安装、升级或替换官方 `com.chun.lingzhu`；
- 与官方 App 的数据目录、SharedPreferences 相互隔离；
- 即使有人误用官方签名密钥，包名不同也不会触发同签名覆盖升级。

版本号仍为 `2.0.9`（217），用于标明源码对应上游版本；它**不代表**本候选可以
覆盖官方 2.0.9 安装。

## 构建前置

| 项目 | 要求 |
|---|---|
| JDK | 17 或更高（推荐 21） |
| Gradle | 8.14（系统安装；本目录不含 wrapper） |
| Android SDK Platform | 35 |
| Android Build-Tools | 35.0.0 |
| AGP / Kotlin | 8.7.3 / 2.0.21（已固定在根 `build.gradle.kts`） |

**为什么没有 `gradlew` / `gradle-wrapper.jar`**：本目录不带 wrapper，
请直接使用系统安装的 Gradle 8.14：

```bash
gradle --version   # 应为 8.14
```

如果你希望仓库自带 wrapper，可在本机用 Gradle 8.14 执行一次
`gradle wrapper --gradle-version 8.14`，再提交生成的文件；这属于可选的本地操作。

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

以上步骤已用系统 Gradle 8.14 实际执行并通过；`app-debug.apk` 使用 Android 调试签名（非生产签名），不能覆盖官方安装，也不能当作生产发行签名。具体结果见下文「测试范围与结果」。

### Release 包与签名

`assembleRelease` / `bundleRelease` / `installRelease` 在**没有签名配置时会直接
失败**，不会静默退回 debug 签名。签名材料只放在本机，不进入仓库：

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

仓库中**没有**官方私钥、官方口令模板或任何可用的签名凭据。用官方密钥签出的
包也不会覆盖官方 App，因为 applicationId 不同；官方覆盖升级仍只能由官方发布
流程完成。

### 可复现性边界

本目录可以构建出功能等价的 Debug/Release APK，但**不承诺字节级可复现**：
没有 Gradle wrapper、依赖校验和或依赖锁定，APK 签名与 ZIP 时间戳也不固定。
对外应表述为“可构建”，不要表述为“可逐字节复现官方包”。

## 密钥与更新

- **API Key 全部由用户自填**：本候选不内置任何模型、DeepL 或翻译服务的 Key。
  原发行版的构建期预设注入（`PRESETS_X` / `DEEPL_X`）和运行期 preset 分支已整体移除，
  不再保留永远为空的 Preset shim；默认翻译引擎因此是用户自己的 AI 接口。
- **没有官方更新源**：`UPDATE_MANIFEST_URL` 为空。设置页的「更新清单地址」
  留空即不检查更新；填入你自己的静态 JSON 地址后才启用。公开 fork 不会拉取
  维护者的官方 APK。
- 应用仍保留用户自填接口、自建更新清单的能力；请自行确认所用服务和清单的
  合法性与完整性。

## 内置词库与版权

本候选只包含 5 个 Wiktionary / Project Gutenberg 词频表：

`junior.txt`、`gaokao.txt`、`cet4.txt`、`cet6.txt`、`ielts.txt`

它们以 **CC BY-SA 3.0** 分发，署名与分级说明见
`app/src/main/assets/wordbank/LICENSE.txt`。代码的 MIT 许可不覆盖这些词表。

原发行版中的考研三级词库（`kaoyan_bikao.txt`、`kaoyan_jichu.txt`、
`kaoyan_chaogang.txt`）著作权归原出版方，授权链不完整，**未包含**在本目录。
新装默认词库因此改为第一级 `junior`，不会指向缺失资产。富文本词库解析器仍保留，
用户可自行导入合法来源的词库。

## 测试范围与结果

已实际执行 `gradle testDebugUnitTest` 与 `gradle assembleDebug`：JUnit XML 汇总
**1289 tests，0 failures / 0 errors / 0 skips**。APK 内含 MIT 全文、三份第三方
许可全文与 4 份 NOTICE，不含 `kaoyan*` 资产。

Android 14 专用只读模拟器（端口 5582）完成真实安装并启动 `ChatActivity` 成功，
进程存活，截图已目视。首启未填 API 时提示缺少接口/权限，点击「去填写」进入
**首次配置引导**，不是直接跳 API 页。未接真实模型，未验证物理振动，也未做全面
功能验收。

已复制并保留绝大多数 JVM 单元测试。以下测试**没有复制**：

| 文件 | 原因 |
|---|---|
| `LayoutPerfTest.kt` | 直接读取 `assets/wordbank/kaoyan*` 做全量性能基线，依赖未分发的版权词库 |
| `WordbankFixVerifyTest.kt` | 逐条校验考研词库的原文修复内容，属于版权语料扫描 |
| `KaoyanBreakScanTest.kt` | 扫描 `kaoyan*` 词库输出断行诊断 |
| `WordbankHygieneScanTest.kt` | 扫描整个 `assets/wordbank/`（含考研词库）做数据体检 |
| `PresetTest.kt` | 测试已移除的「构建期内置密钥 + 固定异或」机制 |

同时调整了三个保留测试的断言，使其匹配开源副本：

- `RichDeckTest.kt`：词库注册断言改为 5 级；删除依赖考研 assets 的真实文件读取用例；
- `UpdateTest.kt`：默认更新地址断言改为「为空、不自动检查」；
- `OnboardingTest.kt`：删除「内置接口跳过接口步骤」用例，改为所有安装都保留接口步骤。

其余业务测试（解析、排版、调度、网络、UI 策略等）保持原样。

测试数量少于生产工程，原因分三类：排除依赖版权语料的用例（上表前四项）、
已删除的「构建期内置密钥」预设机制（`PresetTest`），以及随开源化调整的过时
接线/默认断言（上列三处）。这是明确的范围说明，不是「全量等价验收」。

## 安全与提交规则

- `.gitignore` 排除 `keystore.properties`、`*.jks`、`local.properties`、
  `lingzhu-internal.properties`、构建产物等。
- `android/.githooks/pre-commit` 会拒绝包含签名文件、私钥标记、长 API Key 或 Bearer 令牌的提交。**在仓库根目录**（即 `android/` 的上一级）执行：

```bash
git config core.hooksPath android/.githooks
```

- `android/` 是根目录 Git 仓库的子目录，不是独立仓库。`core.hooksPath` 按仓库根目录解析，因此写 `android/.githooks` 而不是 `.githooks`；所有 `git` 命令同样应在仓库根目录执行，暂存区中的本目录路径形如 `android/app/...`。

## 目录结构

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/wordbank/     # 5 个 CC BY-SA 词表 + LICENSE.txt
│       │   ├── java/com/chun/lingzhu/
│       │   └── res/                 # 原创矢量图标 + 颜色 / 样式 / 无障碍配置
│       └── test/java/com/chun/lingzhu/
├── licenses/                        # Apache-2.0、MIT(ts-fsrs)、Bouncy Castle
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── .gitignore
└── .githooks/pre-commit
```
