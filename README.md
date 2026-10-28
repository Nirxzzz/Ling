# Ling

Ling 是一款常驻桌面的悬浮球工具：屏幕识别、划词翻译，结果在半透明面板中显示；手机端是同一套识别 / 翻译 / 学习能力的 Android 客户端。本仓库以 **GNU GPL v3 only**（SPDX：`GPL-3.0-only`）许可公开 Android 客户端与 macOS / Windows 桌面端的源码、资源和测试。

## 仓库性质

- 仓库地址：<https://github.com/Nirxzzz/Ling>，已公开（`private=false`、`visibility=public`），默认分支为 `main`。
- 采用**单仓库**结构：根目录即仓库根，`android/` 与 `desktop/` 都是它的子目录，不各自独立建库；`android/.githooks/pre-commit` 由仓库根通过 `git config core.hooksPath android/.githooks` 启用。
- 仓库自有源码以 **GPL-3.0-only** 许可公开，版权人为 **Ling contributors**，年份 **2026**；许可证全文见根目录 [LICENSE](LICENSE)，是 GNU 官方 GPLv3 正文的逐字原文。第三方组件、字体与词表仍按各自授权分发，详见下文「许可与第三方资产」。
- 公开可见只说明仓库内容可被访问，不代表已发布成品、已提供安装包，也不代表下文验证记录之外还有额外验收。当前没有已发布的 Release，也不把任何 tag 或产物写成已发布；实际状态以 <https://github.com/Nirxzzz/Ling/releases> 为准。

## 包含内容

### Android

- 版本 `2.7.3`（`versionCode 273`），包名 `com.chun.lingzhu.opensource`，桌面图标入口仍是 `com.chun.lingzhu.ChatActivity`。
- 使用独立 `applicationId`，不能覆盖、升级或替换官方 `com.chun.lingzhu`，数据目录与 SharedPreferences 与官方 App 隔离。公开构建只保留一种构建变体（无 `flavorDimensions` / `productFlavors`）。
- Debug 构建使用 Android 调试签名，不是生产签名；Release 构建需要使用者自备签名配置，缺配置时构建直接失败，不会静默退回 debug 签名。
- 不内置模型、DeepL、翻译服务或腾讯云的 Key，也不内置语音识别 Key；所有接口与 Key 由使用者自填，默认更新源为空。
- 仅保留 5 套已署名的 CC BY-SA 3.0 词表，新装默认词库为 `cet4`（确实存在的那一级）；授权链不完整的考研词库未纳入。
- 语音能力两条独立链路：**实时转写**（豆包 / 阿里云两家，逐字出稿）与**语音对话**（腾讯云识别 → 现有大模型 → 腾讯云合成 → 手机扬声器）。两者都不内置凭据，腾讯凭据与转写 Key 用 AndroidKeyStore 加密存放。
- 构建要求与方法见 **[android/README.md](android/README.md)**。

### 桌面端（macOS / Windows）

- 应用版本 `1.64.1`，Electron `37.10.3`，macOS arm64 与 Windows x64 共用同一份源码。
- 应用标识 `com.ling.opensource`，用户数据目录 `ling-opensource`，与官方客户端隔离。
- 功能包括屏幕识别、划词翻译（AI / DeepL / 百度）、悬浮答案面板与缓存历史、知识卡与 FSRS 复习、Anki `.apkg` 解析模块、专注守护与陪伴气泡、局域网同步、手机常连通道与本地 MCP 服务、实时转写与腾讯语音对话。
- 不内置任何 API Key、内测配置、签名材料或发布模板；不配置更新源时更新功能保持禁用，且不含自动安装器。
- 构建要求与方法见 **[desktop/README.md](desktop/README.md)**。

## 构建入口

| 目标平台 | 主要产物 | 命令 | 详细说明 |
| --- | --- | --- | --- |
| Android | Debug APK；Release 需自备签名 | `cd android && gradle testDebugUnitTest assembleDebug` | [android/README.md](android/README.md) |
| macOS arm64 | `.zip`（未签名、未公证） | `cd desktop && npm ci && npm run pack:mac` | [desktop/README.md](desktop/README.md) |
| Windows x64 | `.zip`（未签名） | `cd desktop && npm ci && npm run pack:win` | [desktop/README.md](desktop/README.md) |

两端的具体依赖版本、签名配置、可复现性边界和产物状态，均以上述子目录 README 为准。桌面端 `package-lock.json` 为 `lockfileVersion 3`，`electron-builder` `26.15.3` 及其依赖已写入锁文件（`packages` 共 333 个条目，含根条目），`npm ci` 可直接安装，不需要先补锁文件；首次安装与打包需要联网下载 Electron `37.10.3` 运行时。本文件只汇总已有执行记录：Android `2.7.3` 的构建与单测、桌面端 `1.64.1` 的打包与产物运行都还没有在这份公开副本上记录，本文件不预写这些通过结论。

## 已执行的验证

以下结果按原记录保留，来自记录当时的那次真实执行；本文件不重跑、也不把历史结果当成当前版本的验收结论。来源区分：Android 2.0.9 的记录来自当时的源码整理阶段；发布提交里引用的 Android 单测数量，以及签名 APK 安装那类结果，都来自生产源码树，不是在这份公开副本上执行；桌面端以本节列出的执行输出为准。

### Android

- **2.0.9 历史记录（不是 2.7.3 的结果）**：`testDebugUnitTest` + `assembleDebug` 首轮 `15m27s` 通过；补齐 assets 许可文件后最终 `57s` 通过；JUnit XML 汇总 **1289 tests，0 failures / 0 errors / 0 skips**。
- 2.0.9 曾在隔离的 Android 14 专用只读模拟器上完成真实安装并启动 `ChatActivity`，进程存活，截图已目视检查；首启未配置 API 时提示缺少接口 / 权限，点击「去填写」进入首次配置引导。
- **2.7.3**：源码已更新到本仓库，但 `gradle testDebugUnitTest` / `assembleDebug`、JUnit 汇总与设备烟测都还没有在本仓库这份公开副本上执行并记录；确认前不写通过数量，也不把 2.0.9 的记录当成 2.7.3 的结果。

### 桌面端

- 1.60.3 时期的记录：`npm ci` 成功；`npm test` 当时共 **70 项，0 失败**；另跑 `node test/anki.test.js`，**8/8 通过**。测试文件集合此后已有增减，**这不是 1.64.1 的结果**。
- 同一时期：macOS arm64 与 Windows x64 均由 `electron-builder` 基于 Electron `37.10.3` 打包成功，不依赖旧模板 zip；macOS 实现曾在真机运行（按题型自由组织章节、追问带原图，另检查了空配置启动、未配置更新源和设置页）。这些记录对应 1.60.3 的源码，不自动适用于 1.64.1。
- Windows 包未在真机运行，只验证了打包与 zip CRC。
- 补充核对：同用 `deepseek-v4-flash` 对 2 道文本题做新旧提示词对照，两版都算出平均速度 `40 km/h`，也都拒绝缺条件的几何题。样本只有 2 题，只说明这两处行为一致，**不构成准确率提升或 benchmark 结论**。
- macOS 包未签名、未公证；Android Debug 包为非生产签名，不能覆盖官方安装。
- 打包脚本使用 `--publish never`，打包过程未上传任何产物。
- **1.64.1**：在这份公开副本上实际执行的是 `npm ci --ignore-scripts --no-audit --no-fund`（安装 332 个包，跳过了 Electron 二进制下载）、`node --test test/*.test.js`（**303 项通过，0 失败**）与 `npm test` 整体（含 `tool/smoke-main.js`，输出「模块加载通过（未发现模块级错误与启动期异常）」）。界面启动复用源码树已有的 Electron `37.10.3` 运行时，以隔离的临时用户数据目录直接启动这份源码（非打包产物），确认首次启动的新手卡与「配置接口」进入设置页；只到启动与设置入口这一层，语音页面、真实接口与云端链路均未验证，打包也没有做过。

## 已知限制

- **没有真实供应商验收**：本轮没有提供第三方 Key / 腾讯云凭据，Android 语音识别、语音合成与桌面端语音对话均未跑真实云端链路；没有用假 Key 冒充成功。文本识别与翻译也未调用真实供应商做质量验收。
- **没有 AEC 硬件保证**：两端都只是「请求系统回声消除 + 软件兜底判断」，未在任何物理设备上按机型验证；外放时仍可能把自己的声音识别成用户输入。两端界面都如实显示系统回报的状态，不承诺「外放不会自己打断自己」。
- 桌面端 Windows 包**没有 Windows 真机运行记录**；跨平台打包与 zip CRC 的验证来自 1.60.3 时期的历史记录，不适用于 1.64.1。专注守护依赖 macOS 的前台应用查询，在 Windows 上不生效。
- 桌面端没有自动更新安装器：更新只下载 zip 并在文件管理器中定位，由使用者自行替换；不配置 `LING_UPDATE_URL` 时无源。
- 桌面端渲染进程 `sandbox` 为 `false`、未设置 CSP，`preload.js` 暴露的 IPC 面较大，只应加载仓库内的本地页面。
- 桌面端流式解析不区分 `finish_reason=length`：整轮没有正文才报错，截断的正文会照常显示，不会被单独标成「未完成」。
- 桌面端 Anki 只有 `.apkg` 解析模块，未接入界面；`collection.anki21b`（zstd 压缩）不在支持范围。
- 在 macOS 上用浏览器调试工具 `evaluate` 访问窗口时，隔离世界不暴露 `orbApi`；主世界 CDP 可用。这是调试工具限制，不是产品缺陷。
- iOS 按既定决定暂停，没有工程或 IPA。
- Android 目录不带 Gradle wrapper、依赖校验和或依赖锁定，不承诺字节级可复现；桌面端有 `package-lock.json`，但打包需联网取 Electron 运行时、产物也不逐字节固定，同样不承诺字节级可复现。

## 许可与第三方资产

- 本仓库自有源码（Android 客户端、桌面端及其构建配置）采用 **GNU General Public License version 3 only** 许可，SPDX 标识 **`GPL-3.0-only`**，版权人 **Ling contributors**，年份 **2026**。根、`android/`、`desktop/` 三份 [LICENSE](LICENSE) 与随 APK 打包的 `android/app/src/main/assets/LICENSE` 都是 <https://www.gnu.org/licenses/gpl-3.0.txt> 的**逐字原文**，四份逐字节一致；没有在正文里增删或附加项目自定声明。许可证正文附录里的示例声明（其中出现 “or any later version” 字样）属于 GPL 官方文本本身，不构成本项目的授权选择：本项目按 **GPL-3.0-only** 授权，不提供「或更高版本」。担保与责任限制按正文第 15–17 条（Disclaimer of Warranty / Limitation of Liability / Interpretation）执行，软件按「现状」提供。
- 机器可读元数据与正文一致：`desktop/package.json` 与 `desktop/package-lock.json` 的根条目均声明 `"license": "GPL-3.0-only"`；`android/app/src/main/assets/LICENSE` 随 APK 分发，`android/THIRD_PARTY_NOTICES.md`、`android/app/src/main/assets/THIRD_PARTY_NOTICES.md` 与 `desktop/THIRD_PARTY_NOTICES.md` 里写明自有代码的许可范围与对应源码指向。
- 改用 GPLv3 **不撤销、不追溯取代**此前以 MIT 给出的授权：任何人当初按当时公开的 MIT 条款取得的那份代码，仍可按 MIT 使用该份副本。GPL 约束的是从当前版本及其衍生作品继续复制、修改与分发的情形。
- 第三方组件、字体、依赖与参考资料不适用上述 GPL 许可，各自保留原有授权。本仓库不重写、不合并这些许可证，也不在此对第三方条款与 GPL 的组合适用性下概括结论，以各许可证原文与随附声明为准。Android 见 [android/THIRD_PARTY_NOTICES.md](android/THIRD_PARTY_NOTICES.md)，桌面端见 [desktop/THIRD_PARTY_NOTICES.md](desktop/THIRD_PARTY_NOTICES.md)。
- Android 内置的 5 套词表是**独立随附的数据资产**，以 **CC BY-SA 3.0** 分发，署名与分级说明见 `android/app/src/main/assets/wordbank/LICENSE.txt`。它们是词表数据而不是本仓库的代码，GPL 许可不覆盖这些文件，也不改变它们原有的授权；本文件不声称 CC BY-SA 3.0 与 GPL 相互兼容或可以互相替代。
- 本文件不重写、不合并、不替代任何许可证全文：GPL 正文以仓库内 `LICENSE` 为准，第三方条款以各自许可证原文为准。

## 安全与分发边界

- 仓库公开范围仅限仓库内容本身，不构成完整安全审计；静态检查不覆盖提交历史、构建环境和全部依赖。
- 不使用官方自动更新源，不内置生产签名与内测密钥；使用者需自行确认所填接口、Key 与更新清单的合法性和完整性。
- 公开构建使用独立标识（Android `com.chun.lingzhu.opensource`，桌面端 `com.ling.opensource` / 用户数据目录 `ling-opensource`），源码与官方成品在标识、签名、资产和内置接口上存在明确差异。不要把本仓库的构建产物称为已发布的官方版本，也不要用它覆盖官方安装。
- **对外分发二进制时的 GPL 义务**：本仓库公开的是源码，不是已发布的官方安装包。若有人把本仓库构建出的 APK 或桌面端安装包分发给他人，GPLv3 第 6 条要求随目标代码一并提供**对应的完整源码**（Corresponding Source，含构建与安装所需的脚本等；修改版须提供该修改版的源码），或按第 6 条允许的方式作出有效的书面提供承诺。仅给出公共仓库地址，不能证明接收方拿到的是与该二进制严格对应的那份源码，也不能替代第 6 条本身的要求；本仓库不对任何第三方构建产物作符合性保证。
- 旧官方成品与历史记录不在本仓库范围内，也不因本仓库的存在而改变。
