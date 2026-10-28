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
- Debug 构建使用 Android 调试签名，不是生产签名；Release 构建需要使用者自备签名配置：缺 `keystore.properties` 或其中任一项时，release 产物出口被执行阶段的 `checkReleaseSigning` 挡住并明确报错，不会静默退回 debug 签名、也不产出未签名 release 包。签名校验不在配置阶段抛异常，无签名材料的机器上 debug 构建与单测能正常进行（已实测）。
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

两端的具体依赖版本、签名配置、可复现性边界和产物状态，均以上述子目录 README 为准。桌面端 `package-lock.json` 为 `lockfileVersion 3`，`electron-builder` `26.15.3` 及其依赖已写入锁文件（`packages` 共 333 个条目，含根条目），`npm ci` 可直接安装，不需要先补锁文件；首次安装与打包需要联网下载 Electron `37.10.3` 运行时。两端都已有本副本上的执行记录（见下节）：Android 构建、单测与冒烟通过，桌面端单测与两轮界面验收通过、macOS zip 已构建并启动过。这些记录不包含签名发布包、Windows 运行、真实供应商调用或模型质量结论。

## 已执行的验证

以下都是本仓库这份公开副本上真实执行过、并核对过输出或产物的结果；每条标明它对应的版本，跨版本不互推，也不把历史记录当成当前版本的结论。Android 2.0.9 那条早于本次公开整理，只作历史保留。

### Android

- **2.0.9 历史记录（不是 2.7.3 的结果）**：`testDebugUnitTest` + `assembleDebug` 当时通过，JUnit 汇总 **1289 tests，0 failures / 0 errors / 0 skips**；2.0.9 也曾在隔离的 Android 14 专用只读模拟器上完成安装并启动 `ChatActivity`，首启未配置 API 时会引导去填写。这些对应当时按 MIT 分发的源码，不是当前 GPL-3.0-only 的状态。
- **2.7.3（已实际执行）**：`gradle testDebugUnitTest assembleDebug` 构建成功（约 36 秒），主源码与测试源码均编译通过；`testDebugUnitTest` 共 153 个测试类、**2380 项，0 失败 / 0 错误 / 0 跳过**。
- **2.7.3 APK**：`app-debug.apk`，15,315,144 字节，`applicationId=com.chun.lingzhu.opensource`，2.7.3 / 273，`sha256 972f938ab9706cf7f060a0d1495ff55493a2227be9270d7c81e9cbd175c229d2`。包内含 GPLv3 正文与 notices，词表只有 5 套 CC BY-SA 文件。**调试签名，不是生产签名，不能覆盖官方 App。**
- **2.7.3 签名闸门（已实测）**：缺 `keystore.properties` 时配置阶段不再抛异常，debug 构建与单测正常进行；release 产物出口由执行阶段的 `checkReleaseSigning` 拦下并明确报错，没有退回 debug 签名，也没有产出未签名 release 包。本目录没有签出、安装或分发过 release 包。
- **2.7.3 设备冒烟（Android 14 专用只读模拟器）**：安装后冷启动 `ChatActivity` 正常，保存笔记后杀进程冷启动仍可见，未填接口时「检测连接」如实提示缺少接口地址 / 模型、没有假成功，整轮 logcat 无 `FATAL EXCEPTION` 与 `ANR in`。只覆盖主干路径，不等于全面功能验收；未接真实供应商。

### 桌面端

- **1.64.1 单元测试**：`npm ci --ignore-scripts --no-audit --no-fund` 安装 332 个包（跳过 Electron 二进制下载）；`npm test` **302 项通过，0 失败**，`tool/smoke-main.js` **8/8 通过**。这是纯函数与模块级冒烟，**不启动**窗口与渲染进程。
- **1.64.1 界面验收（两轮）**：先跑源码（隔离临时用户数据目录、复用 Electron `37.10.3`），再跑最终 zip 解出的 `Ling.app`；两轮都用真实鼠标 / 键盘操作，模型请求全部指向本机临时 fixture 服务。走通的路径：新手卡 → 设置页填接口 → 获取模型列表 → 检测连接 → 保存 → 退出重启后设置与引导状态仍在 → 双击截屏流式答案 → 追问（打包产物那轮带新截图并成功）→ 历史记录重启后仍在、可选中、星标可切换 → 存知识卡且存后按钮不可点 → 知识卡评分「良好」后到期顺延 2 天、当日到期队列为空、卡库计数 1 → HTTP 500 时面板显示错误且窗口可操作，干净配置下该轮「存为复习卡」不可点、主进程回 `notsaveable`，失败后再成功的一轮可以正常存 → 流式挂起时关闭面板后 busy 清除且不再自行弹回 → 退出码 0，无残留进程或单实例锁。
- 边界：fixture 只证明**协议与接线**（请求构造、SSE 解析、错误显示、取消恢复、存卡资格、复习调度），**不证明**真实模型对截图的识别能力，也不证明答案质量。未验收：语音云链路（腾讯 / 豆包 / 阿里云）、局域网同步、MCP、手机常连、剪贴板翻译、专注守护、陪伴气泡、更新检查与下载、Windows 真机；桌面端没有笔记界面。这些是「未验证」，不是「不可用」。
- **1.64.1 打包**：macOS arm64 zip 已构建（`Ling-1.64.1-mac-arm64.zip`，102,879,005 字节，`sha256 56fed7dc97bd12632752520917ca0fb0a5593ce4babfdc9958e641f0f2347216`），**未签名、未公证**（`identity: null`，`codesign` 为 ad hoc、无 TeamIdentifier），首次打开需在系统设置里选「仍要打开」。核对过最终产物的 `Info.plist`：`com.ling.opensource` / `1.64.1`，麦克风与系统音频用途说明存在，摄像头与蓝牙三项用途串已不在 plist 中；`app.asar` 顶层与 `build.files` 逐项一致，包内图标与 `desktop/build/icon.png` 逐像素相同。Windows zip 也在修复复习队列 / 存卡之后构建（`Ling-1.64.1-win-x64.zip`，134,543,299 字节，`sha256 52a9e2fb79330311273d127f742583decadf84d2941807b621de7b6c85cce055`，`unzip -t` 通过，`app.asar` 含上述修复），但**从未在 Windows 上启动过**。两个 zip 都未做代码签名，**没有签名的 release 包，也没有发布任何产物**。

## 已知限制

- **没有真实供应商验收**：本轮没有提供第三方 Key / 腾讯云凭据，Android 语音识别、语音合成与桌面端语音对话均未跑真实云端链路；没有用假 Key 冒充成功。文本识别与翻译也未调用真实供应商做质量验收。
- **没有 AEC 硬件保证**：两端都只是「请求系统回声消除 + 软件兜底判断」，未在任何物理设备上按机型验证；外放时仍可能把自己的声音识别成用户输入。两端界面都如实显示系统回报的状态，不承诺「外放不会自己打断自己」。
- 桌面端 Windows 包**未做代码签名，也从未在 Windows 上启动过**（归档已构建且 `unzip -t` 通过，但没有 Windows 运行记录）；专注守护依赖 macOS 的前台应用查询，在 Windows 上不生效。
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
