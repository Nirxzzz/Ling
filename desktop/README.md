# Ling 桌面端（macOS / Windows）

Ling 是常驻桌面的悬浮小球：双击截屏识别屏幕内容，选中文字后按快捷键翻译，结果在半透明面板里流式显示。macOS 与 Windows 共用同一份 Electron 源码，平台差异只在运行时判断。

本目录是**公开源码副本**，不是官方发布仓库：不含内置 API Key、内测配置、签名材料或发布模板；模型 / 翻译 / 语音接口都由使用者自己在设置里填写。

- 版本：`1.64.1`
- 应用标识：`com.ling.opensource`，程序名 `Ling`；设置页的版本行显示 `Ling v1.64.1 开源版`（渠道常量 `CHANNEL_LABEL` 为 `开源版`，不是上游的 `正式版`）
- 用户数据目录：`ling-opensource`（与官方客户端隔离）
- 自有代码许可：**GNU GPL v3 only**（SPDX：`GPL-3.0-only`），版权人 **Ling contributors**，年份 **2026**，许可证全文见 [LICENSE](LICENSE)（GNU 官方 GPLv3 正文逐字原文，`package.json` 与 `package-lock.json` 根条目同样声明 `GPL-3.0-only`）；第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，各自保留原有授权。此前以 MIT 公开的旧版本不因本次改用 GPLv3 而撤销：当时已按 MIT 取得的副本仍可按 MIT 使用，GPL 约束的是从当前版本及其衍生作品继续分发的情形。

## 功能

- 屏幕识别：双击小球截取当前屏幕（多显示器时逐屏送入），输出答案 / 解释 / 解题步骤
- 划词翻译：全局快捷键读取选区，支持 AI、DeepL、百度翻译三种引擎
- 悬浮答案面板：流式输出、Markdown / LaTeX 排版、历史记录、结果缓存
- 知识卡：把答案存为卡片，按 FSRS 间隔重复复习；`anki.js` 提供 Anki `.apkg` 解析，**未接入界面**
- 专注守护与陪伴气泡：前台应用检测走 macOS 的 `lsappinfo`，非 macOS 拿不到前台应用
- 互联：局域网同步、手机常连通道与本地 MCP 服务（`mcp-*.js` / `ling-link.js`）
- 实时转写：豆包（火山引擎）与阿里云百炼两家，音源可选麦克风 / 系统回环 / 两路混音
- 语音对话：腾讯云识别 → 已配置的大模型 → 腾讯云合成 → 本机扬声器

## 环境要求

- **Node.js 24 或更高**（`package.json` 的 `engines` 为 `>=24.0.0`；`anki.js` 依赖 Node 内置的 `node:sqlite`，本仓库只在 Node 24 上执行过安装与单元测试）
- npm（随 Node 24 附带）
- macOS（Apple Silicon / `arm64`）或 Windows 10/11（x64）；打包配置没有设 `minimumSystemVersion`，最低系统版本以上游 Electron `37.10.3` 的要求为准，本仓库未另行声明或验证
- 运行时为 Electron `37.10.3`，打包工具为 `electron-builder` `26.15.3`；`npm ci` 与首次打包都需要联网下载对应平台的 Electron 运行时

## 开发运行

```bash
npm ci
npm start
```

`npm start` 以开发模式启动，使用独立用户数据目录 `ling-opensource`，不会读写已安装的官方 Ling 客户端设置。

第一次启动先弹新手卡；已看过引导但没配接口时，设置卡会自动打开。在设置里填写 OpenAI 兼容接口的 **Base URL、模型名、API Key**，点「检测连接」确认模型能接收图片，再保存。未填写接口时，识别 / 翻译会提示先去设置，不会回落到任何预置服务器（`AI_BASE_URL` / `AI_MODEL` / `AI_KEY` 只作为默认值，设置里填写的值优先）。

### 可选环境变量

| 变量 | 作用 |
| --- | --- |
| `AI_BASE_URL` / `AI_MODEL` / `AI_KEY` | 预置默认接口（设置里填写的值优先） |
| `LING_USER_DATA` | 覆盖用户数据目录（测试 / 截图用，指到临时目录） |
| `LING_UPDATE_URL` | 自建更新 manifest 地址；不设置则更新功能显示「未配置更新源」 |

## 测试

```bash
npm test
```

该命令两段：`node --test test/*.test.js`（FSRS、知识卡、Anki 解析、提示词、主题、更新、局域网、MCP、语音模块等）与 `node tool/smoke-main.js main.js`（用假的 electron 模块加载 `main.js`，拦模块级崩溃导致的装入即白屏）。测试需要 Node 24。冒烟脚本还会把加载后 300 ms 内出现的未捕获异常 / 未处理 Promise 拒绝判为失败——`main.js` 自己的处理函数只记日志、不改退出码。它的覆盖范围只到「同步加载 + 之后 300 ms 的异步尾巴」，**不启动真实 Electron 窗口与渲染进程**，所以通过它不等于界面可用；界面要自己跑 `npm start` 看。

已记录的执行：在这份公开副本上实际跑的是 `npm ci --ignore-scripts --no-audit --no-fund`（安装 332 个包，跳过 Electron 二进制下载），`node --test test/*.test.js` 的 **303 项全部通过（0 失败）**，`npm test` 整体（含 `tool/smoke-main.js`）也通过。界面启动复用源码树已有的 Electron `37.10.3` 运行时，以隔离的临时用户数据目录直接启动这份源码（不是打包产物），观察到首次启动的新手卡，且「配置接口」能打开设置页；只验证到启动与设置入口，语音页面、真实接口与云端链路尚未验证，打包与产物运行仍未执行。

## 本地打包

```bash
npm run pack:mac   # macOS arm64 → dist/Ling-1.64.1-mac-arm64.zip
npm run pack:win   # Windows x64 → dist/Ling-1.64.1-win-x64.zip
npm run pack       # 依次执行上面两条
```

打包配置写在 `package.json` 的 `build` 字段：`asar: true`、`npmRebuild: false`、输出目录 `dist`、`artifactName` 为 `${productName}-${version}-${os}-${arch}.${ext}`；mac 目标只有 `zip` / `arm64` 且 `identity: null`，win 目标只有 `zip` / `x64`。`pack:mac` 与 `pack:win` 都带 `--publish never`（`pack` 只是依次调用这两条），过程中不上传任何产物，也不产出安装器。

**产物状态（如实说明）**：

- macOS 包**未签名、未公证**（`identity: null`）。首次打开会被 Gatekeeper 拦截，需要在「系统设置 → 隐私与安全性」里选择「仍要打开」，或用自己的 Developer ID 重新签名后再分发。每次重新构建后，屏幕录制 / 辅助功能授权可能需要重新授予。
- Windows 包**未做代码签名**，本目录也没有 Windows 真机运行记录。跨平台打包与 zip CRC 的结论来自 1.60.3 时期的历史记录，不适用于 1.64.1；请自行在 Windows 上构建并实测后再分发。
- 打包资源不全（已核对本目录与 Git 历史）：`build.directories.buildResources` 指向的 `build/` 目录在当前树里不存在 —— `build/icon.png`、`build/icon.icns`、`build/icon.ico` 在上一版提交里存在，发布提交 `46c4cfb` 把这三个文件删掉了。此外 `build.files` 里的 `THIRD_PARTY_NOTICES.txt` 与 `chat-stream.js` 在本目录也不存在（实际存在的是 `THIRD_PARTY_NOTICES.md`，它本身也在 files 列表里）。按 `electron-builder` 的代码路径，图标候选全部落空时会退回 Electron 默认图标，缺失的 files 条目复制不到 asar 里；这两点本目录尚未实际打包，只有真正打包后才能确认。要用自己的图标，请补出 `build/icon.png` 或改配置指向实际存在的文件。
- **分发打包产物时的 GPL 义务**：`npm run pack:*` 产出的 zip 若交给他人，GPLv3 第 6 条要求同时提供与该二进制对应的完整源码（含构建所需的 `package.json`、`package-lock.json` 与脚本；修改版则提供修改版源码，并注意其依赖的是仓库外的 Electron `37.10.3` 运行时），或按第 6 条作出有效的书面提供承诺。仅给出 <https://github.com/Nirxzzz/Ling> 不能证明它与某个具体 zip 严格对应，也不自动满足第 6 条。`app.asar` 内的 `LICENSE` 与 `THIRD_PARTY_NOTICES.md` 只提供许可文本。

## 数据与隐私

- 用户数据目录：macOS `~/Library/Application Support/ling-opensource`，Windows `%APPDATA%\ling-opensource`
- `state.json` 保存界面设置、API 地址 / Key、DeepL / 百度凭证，文件权限 `0600`；**Key 为明文存储**，请自行保护账户与磁盘
- 历史记录、结果缓存、知识卡分别保存在同目录下的 `history.json`、`cache-*.json`、`knowledge.json`；更新下载落在 `updates/`
- 截图与文本会发送到你自己配置的模型接口；本应用不内置任何中转服务器，也不向维护者上传数据
- 更新功能默认无源；只有显式设置 `LING_UPDATE_URL` 时才会访问该地址，下载后校验 manifest 里的 sha256，再在文件管理器中定位该 zip，**不自动安装**

## 密钥与语音

- **实时转写的 Key 加密存放**：豆包 / 阿里云两家的 Key 由主进程用 Electron `safeStorage` 加密后写入 `userData/voice-asr.json`；`safeStorage` 不可用时**拒绝保存**并如实报错，不落明文
- **腾讯云凭据同样加密**：AppId / SecretId / SecretKey 整套存在同一个加密文件里，与转写两家的 Key 分开保存
- **语音参数不是秘密**：音色、`Speed`、音量写在 `userData/voice-dialogue.json`（明文 `0600`）。`Speed` 是腾讯官方 `[-2, 6]` 档位参数（0 为正常语速），**不是倍率**；音量是本机播放增益（dB，`[-10, 10]`，0 为原始音量），只作用于本应用自己的 `GainNode`，**不发给腾讯**
- 麦克风约束里请求了系统回声消除（`echoCancellation: true`），但**没有在任何物理设备上验证过 AEC 实际效果**；外放时仍可能把自己的播报识别成用户输入
- 凭据落盘只用注入的 `safeStorage` 替身做过离线检查（写入后可解密、文件里只有密文、权限 `0600`、删除后重启不复活、加密不可用时拒绝写入）。这**不等于**系统钥匙串 / 真实 Electron `safeStorage` 下的验证

## 已知限制

- 渲染进程 `sandbox` 为 `false`、未设置 CSP，`preload.js` 暴露的 IPC 面较大，只应加载仓库内的本地页面
- 流式解析不区分 `finish_reason=length`：整轮没有正文才报错，被截断的正文会照常显示，不会被单独标成「未完成」
- Anki 只有 `.apkg` 解析模块，未接入界面；`collection.anki21b`（zstd 压缩）不在支持范围，明确报错
- 专注守护的前台应用探测只实现 macOS 路径，Windows 上不生效；陪伴气泡用本地短句，未配置接口时不联网
- 没有自动更新安装器：更新只下载 zip 并由使用者自行替换
- 本仓库没有附带第三方 Key / 腾讯云凭据，实时转写与语音对话都**没有跑过真实云端链路**，也没有用假 Key 冒充成功；语音相关只有单元测试与本地离线检查，启动实测也只到新手卡与设置入口，语音页面未经界面验证
- 实时转写的「系统回环 / 混音」两档依赖平台的系统音频捕获能力（主进程通过 `setDisplayMediaRequestHandler` 授权音频来源），本仓库没有在 macOS 或 Windows 上实测过这两档

## 与私有构建的差异

公开副本只保留可公开的运行时与构建配置，已移除：内测 / 公测渠道注入、`preset.js` 与异或解混淆逻辑、任何内置 API / DeepL Key、`*.properties`、签名证书与内测安装包，以及依赖仓库外模板 zip 或维护者签名身份的打包脚本。另外，本轮短暂加入的 Anki 导入 UI 已撤销，当前源码不含该入口。
