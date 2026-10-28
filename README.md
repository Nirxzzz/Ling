<div align="center">

<img src="desktop/build/icon.png" alt="Ling 悬浮球图标" width="104" height="104">

# Ling · 灵珠

### 把屏幕上的疑问，变成可以理解、可以复习的知识。

一个悬浮在手边的 AI 学习助手。识别屏幕、翻译选区、继续追问，再把值得记住的答案留下来。

**Android · macOS · Windows**

[下载安装](https://github.com/Nirxzzz/Ling/releases) · [快速开始](#快速开始) · [功能一览](#功能一览) · [从源码运行](#从源码运行) · [反馈问题](https://github.com/Nirxzzz/Ling/issues)

</div>

---

## 不必为了一个问题，打断正在做的事

读文献遇到一段难懂的英文，看课程时想问清一个公式，或者刚弄懂一道题、又担心过两天忘记——这些小事，往往要在截图、聊天、翻译和记忆工具之间来回切换。

Ling 想把这段过程接起来：**看到问题 → 就地提问 → 理解答案 → 留作复习。**

- **看不懂，就问屏幕。** 桌面端双击悬浮球截屏，答案在浮层中流式展开；Android 也提供屏幕识别入口。
- **遇到外语，不必整段搬走。** 桌面端选中文字后用快捷键翻译，支持 AI、DeepL 与百度翻译；不同引擎按需配置。
- **第一遍没懂，接着问。** 围绕识别结果继续追问，配合 Markdown 与 LaTeX 阅读解释和公式。
- **值得记住，就留下来。** 将答案存成知识卡，用 FSRS 间隔重复安排复习，而不只是多攒一条聊天记录。

Ling 不是模型服务，也不附赠模型额度。**你选择服务商、填写自己的接口和 Key，Ling 负责把这些能力放到顺手的位置。**

## 下载

源码和安装包都在这个仓库：源码看首页文件，成品到 **[Releases](https://github.com/Nirxzzz/Ling/releases)** 下载。

当前归档版本：**Android 2.7.3 / 桌面端 1.64.1**，对应 tag [`v2.7.3-1.64.1`](https://github.com/Nirxzzz/Ling/tree/v2.7.3-1.64.1)。

| 平台 | 下载 | 安装前请注意 |
| --- | --- | --- |
| Android 8.0+ | [APK · 2.7.3](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/Ling-2.7.3-opensource-debug.apk) | **debug 签名**；独立包名，不能覆盖原官方版 |
| macOS · Apple Silicon | [ZIP · 1.64.1](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/Ling-1.64.1-mac-arm64.zip) | 未做 Developer ID 签名、未公证；已在 macOS 启动验收 |
| Windows · x64 | [ZIP · 1.64.1](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/Ling-1.64.1-win-x64.zip) | 未签名；**已构建，但尚未在 Windows 实机运行验证** |

[查看版本说明与完整校验值](https://github.com/Nirxzzz/Ling/releases/tag/v2.7.3-1.64.1) · [下载 SHA-256 清单](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/sha256.txt)

> 这是本仓库的**开源构建归档**，不是原官方发行渠道。没有生产签名的 Android release 包，也没有经过完整兼容性验证的跨平台正式安装器。请先阅读下方的已知限制，再决定是否用于日常工作。

## 快速开始

### 1. 安装并打开

- **Android：** 安装 APK，按引导配置。使用悬浮球、截屏等功能时，再按用途授予相应权限。
- **macOS：** 解压 ZIP，打开 `Ling.app`。若被 Gatekeeper 拦截，在确认下载来源与校验值后，到「系统设置 → 隐私与安全性」选择「仍要打开」。截屏、读取选区等能力需要对应的系统权限。
- **Windows：** 解压 ZIP 后运行其中的 `Ling.exe`。此包尚无 Windows 实机验收记录，请自行确认运行情况。

### 2. 接上你自己的模型

在设置中填写 **OpenAI 兼容接口地址（Base URL）、模型名和 API Key**，使用「检测连接」检查配置，再保存。屏幕识别需要选择**能接收图片的模型**；只有文本能力的模型不能替代视觉模型。

没有接口或 Key？仍可先了解界面，但依赖云端模型的识别与问答需要先配置服务。仓库不内置可用密钥，也不会在配置缺失时偷偷切换到预置模型服务器。

### 3. 从一个真实问题开始

桌面端双击小球，或在 Android 中使用「识别屏幕」，对当前内容发起提问。读完后继续追问；有复习价值的答案，再存为知识卡。

**关于费用与隐私：** 模型、翻译和语音服务可能由服务商收费。使用识别或翻译功能时，相应截图、选区或文本会发送到你配置的服务；云端语音功能也需要发送音频或文本。请先确认服务商政策，不要把敏感内容交给不信任的接口。

## 功能一览

### 围绕「看见、理解、记住」的主线

| 能力 | Android | 桌面端 |
| --- | --- | --- |
| 屏幕识别与 AI 问答 | 屏幕识别、聊天与追问 | 双击悬浮球截屏，浮层流式展示与追问 |
| 翻译 | 翻译入口，使用自行配置的服务 | 选区快捷翻译；AI / DeepL / 百度引擎 |
| 学习与复习 | 词库学习、知识卡与 FSRS 复习 | 答案存知识卡，按 FSRS 复习 |
| 记录 | 聊天记录、记事与提醒 | 答案历史、星标与结果缓存；**没有笔记界面** |
| 云端语音 | 实时转写、语音对话 | 实时转写、语音对话；可选麦克风 / 系统音频 / 混音 |

Android 随附初中、高考、四级、六级、雅思 **5 套词表**，词表单独按 CC BY-SA 3.0 授权；授权链不完整的考研词库没有纳入公开版。

### 按需探索的进阶能力

- **两条独立的语音链路：** 实时转写使用豆包或阿里云；语音对话使用腾讯云识别 → 你配置的大模型 → 腾讯云合成。需要分别配置服务凭据，不能只填一个模型 Key 就使用全部语音功能。
- **桌面互联：** 源码包含局域网同步、手机常连通道和本地 MCP 服务。
- **专注与陪伴：** 桌面端提供专注守护与陪伴气泡；前台应用检测依赖 macOS，Windows 不具备同等的专注守护能力。

这些是源码中已有的能力，不等于本次归档逐项通过了端到端验收。语音云链路、互联与进阶能力的验证边界见下文。

## 当前验证到哪里

我们把「有代码」「构建成功」和「实际跑过」分开说明，不用测试数量代替真实使用结论。

- **Android 2.7.3：** 主源码与测试源码编译通过，2380 项单元测试通过；Android 14 模拟器验证了冷启动、笔记保存后重启保留、未配置接口时的提示，走查中没有发现 `FATAL EXCEPTION` 或 ANR。
- **桌面端 1.64.1：** 302 项单元测试与 8 项模块加载冒烟通过；源码与最终 macOS 包均实际启动，走通接口配置、识别与追问、历史保留、存卡及复习调度，并检查了 HTTP 500 与取消中的界面恢复。
- **下载产物：** 三个二进制归档均从 GitHub 重新下载，与本地交付文件的 SHA-256 一致。

**桌面问答验收使用本地 fixture 服务，证明请求、流式解析与界面流程接通，不证明真实模型的识别能力或答案质量。** Windows 实机、真实供应商质量、语音云链路、局域网同步、MCP、手机常连、剪贴板翻译、专注守护、陪伴气泡及更新下载尚未完成本次端到端验收。

更详细的执行记录：[Android](android/README.md#构建) · [桌面端](desktop/README.md#界面运行验收)。

## 从源码运行

这是一个单仓库项目：`android/` 与 `desktop/` 分别是两个客户端，不需要另找源码仓库。

```bash
git clone https://github.com/Nirxzzz/Ling.git
cd Ling
```

### 桌面端

需要 **Node.js 24+** 与 npm；首次安装、打包需要联网下载 Electron 运行时。

```bash
cd desktop
npm ci
npm start
```

测试使用 `npm test`；打包使用 `npm run pack:mac` 或 `npm run pack:win`。当前打包目标为 macOS arm64、Windows x64 的 ZIP 归档，不是自动安装器。

[桌面端开发、环境变量与打包说明 →](desktop/README.md)

### Android

需要 **JDK 17、系统 Gradle 8.14、Android SDK 35**，并先配置本地 SDK 路径。仓库**不带 Gradle wrapper**，因此这里使用 `gradle`，不是 `./gradlew`。

```bash
cd android
gradle testDebugUnitTest assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。Debug 无需签名材料；Release 需要你自己的 keystore，缺失时会明确失败，不会回退到调试签名。

[Android 环境配置、签名与构建说明 →](android/README.md)

两端均不承诺字节级可复现构建；Android 未附依赖锁定与校验和，桌面端虽有 `package-lock.json`，Electron 下载、签名和归档元数据仍会影响产物字节。

## 使用前需要知道

- **开源版与原官方版隔离。** Android 包名为 `com.chun.lingzhu.opensource`；桌面应用标识为 `com.ling.opensource`，默认数据目录为 `ling-opensource`。不要把开源归档当作原官方版的覆盖升级包。
- **没有预置服务，也没有官方自动更新源。** 接口、翻译、语音凭据全部自填。桌面更新只下载 ZIP 并定位文件，不自动安装；未配置更新源时不启用更新。
- **模型输出需要判断。** 当前桌面流式解析不会单独标记 `finish_reason=length`，截断答案可能仍然显示为普通正文；重要结论应自行核验。
- **语音外放不保证无回声。** 系统回声消除加软件判断并不等于硬件级保证，尚未按物理设备逐一验证，外放可能被再次识别为输入。
- **Anki 还不是可点击的导入功能。** 桌面源码有 `.apkg` 解析模块，但没有界面入口，也不支持 zstd 压缩的 `collection.anki21b`。
- **iOS 暂停。** 本仓库没有 iOS 工程或 IPA；桌面端也没有笔记界面。
- **桌面安全边界需谨慎。** 当前渲染进程 `sandbox=false`、未设置 CSP，preload 暴露的 IPC 面较大，只应加载仓库内的本地页面。公开源码不等于已完成安全审计。

## 反馈与参与

使用中遇到问题，请在 [Issues](https://github.com/Nirxzzz/Ling/issues) 附上：**平台和系统版本、Ling 版本、复现步骤、预期与实际表现**。截图和日志请先遮掉 API Key、个人内容与服务凭据。

欢迎围绕可复现的问题、平台兼容性和文档清晰度提出改进。改动哪个客户端，就先阅读对应目录的 README；提交时不要加入密钥、签名文件或个人数据。

## 许可与第三方资产

- **项目自有源码：** GNU General Public License version 3 only，SPDX `GPL-3.0-only`，© 2026 Ling contributors。完整条款见 [LICENSE](LICENSE)，软件按许可证约定「按现状」提供，不附担保。许可证附录中的示例不改变本项目的 only 授权选择。
- **历史 MIT 授权：** 本次改用 GPL 不撤销此前已经按 MIT 给出的授权；当时取得的那份代码仍可按当时条款使用。
- **第三方组件与资产：** 各自保留原授权，见 [Android 第三方声明](android/THIRD_PARTY_NOTICES.md) 与 [桌面端第三方声明](desktop/THIRD_PARTY_NOTICES.md)。本页不对第三方条款与 GPL 的组合适用性作概括保证。
- **内置词表：** 独立随附的数据资产，按 CC BY-SA 3.0 分发，不属于项目自有代码的 GPL 授权范围。署名与来源见 [词表许可](android/app/src/main/assets/wordbank/LICENSE.txt)。
- **分发二进制：** 须遵守 GPLv3 第 6 条的对应源码义务，按其允许的方式提供与二进制对应的完整源码及必要构建脚本。仅贴一个仓库首页地址不自动构成义务已履行；第三方修改版应提供自己的对应源码。

本仓库不包含原官方版的生产签名、内测密钥或生产配置。原官方成品及其历史不因本仓库的公开而改变。
