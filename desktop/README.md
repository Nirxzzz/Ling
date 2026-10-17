# Ling 桌面端（macOS / Windows）

Ling 是一个常驻桌面的悬浮小球：双击截屏识别屏幕内容，选中文字后按快捷键翻译，结果在半透明面板里流式显示。macOS 与 Windows 共用同一份 Electron 源码，平台差异只在运行时判断。

本仓库是**开源候选构建**：不含任何内置 API Key、内测配置、签名材料或发布模板；所有模型接口由使用者自己在设置里填写。

## 功能

- 屏幕识别：双击小球截取当前屏幕（多显示器时逐屏送入），输出答案 / 解释 / 解题步骤
- 划词翻译：全局快捷键读取选区，支持 AI、DeepL、百度翻译三种引擎
- 悬浮答案面板：流式输出、Markdown / LaTeX 排版、历史记录、结果缓存
- 知识卡：把答案存为卡片，按 FSRS 间隔重复复习；`anki.js` 提供 Anki `.apkg` 解析，当前未接入复习页界面。本轮曾新增的 Anki 导入 UI 已撤销，本候选不提供、也不宣传新的导入入口。
- 右键菜单：模式切换、自动扫描间隔、API 档案、外观与主题

## 环境要求

- **Node.js 24 或更高**（`anki.js` 使用 Node 内置的 `node:sqlite`；Node 22 及更早版本无法运行该解析模块与相关测试）
- npm 10+
- macOS 13+（Apple Silicon）或 Windows 10/11（x64）

## 开发运行

```bash
npm ci
npm start
```

`npm start` 以开发模式启动，使用独立的用户数据目录 `ling-opensource`，不会读写已安装的 Ling 正式版设置。

首次启动会自动打开设置卡：填写 OpenAI 兼容接口的 **Base URL、模型名、API Key**，点「检测连接」确认模型能接收图片，再保存。未填写接口时，识别 / 翻译会提示先去设置，不会回落到任何预置服务器。

### 可选环境变量

| 变量 | 作用 |
| --- | --- |
| `AI_BASE_URL` / `AI_MODEL` / `AI_KEY` | 预置默认接口（设置里填写的值优先） |
| `LING_USER_DATA` | 覆盖用户数据目录（测试 / 截图用） |
| `LING_UPDATE_URL` | 自建更新 manifest 地址；不设置则更新功能显示「未配置更新源」 |

## 测试

```bash
npm test
```

运行 `test/*.test.js`（FSRS、知识卡存储、Anki 解析、提示词、主题、更新逻辑）以及 `tool/smoke-main.js` 的模块加载检查。测试需要 Node 24。

实测结果：`npm ci` 成功；`npm test` 共 **66 项，0 失败**；另跑的 Anki 自定义用例 **8/8 通过**。测试数量少于私有生产工程，差异来自排除版权语料相关用例、已删除的预设机制以及过时的接线/默认断言；这是范围差异，不是等价全量验收。

## 本地打包

```bash
npm run pack:mac   # macOS arm64 → dist/Ling-1.60.2-mac-arm64.zip
npm run pack:win   # Windows x64 → dist/Ling-1.60.2-win-x64.zip
```

打包使用 `electron-builder` 26.15.3，运行时为 Electron 37.10.3，配置写在 `package.json` 的 `build` 字段中；不依赖仓库外的模板 zip、维护者证书或硬编码路径。首次打包会从网络下载对应平台的 Electron 运行时。

实测：mac arm64 与 win x64 均已打包成功，zip CRC 校验通过。打包脚本带 `--publish never`，过程中未上传任何产物。

**产物状态（如实说明）**：

- macOS 包**未签名、未公证**（`identity: null`）。首次打开会被 Gatekeeper 拦截，需要在「系统设置 → 隐私与安全性」里选择「仍要打开」，或使用自己的 Developer ID 重新签名后再分发。每次重新构建后，屏幕录制 / 辅助功能授权可能需要重新授予。
- macOS 最终实现已在本机真实运行：baseUrl / model / key 为空，未配置更新源，更新按钮为禁用状态，设置页截图已目视。这属于启动与设置页验收，不覆盖全部功能。
- Windows 包**未做代码签名**，且**未在 Windows 真机上运行**；只验证了跨平台打包与 zip CRC。请在 Windows 上自行构建并实测后再分发。
- 仅提供 macOS arm64 与 Windows x64 两个目标；不产出安装器、不提供自动发布。

## 数据与隐私

- 用户数据目录：macOS `~/Library/Application Support/ling-opensource`，Windows `%APPDATA%\ling-opensource`
- `state.json` 保存界面设置、API 地址 / Key、DeepL / 百度凭证，文件权限 `0600`；**Key 为明文存储**，请自行保护账户与磁盘
- 历史记录、结果缓存、知识卡分别保存在同目录下的 `history.json`、`cache-*.json`、`knowledge.json`
- 截图与文本会发送到你自己配置的模型接口；本应用不内置任何中转服务器，也不向维护者上传数据
- 更新功能默认无源；只有显式设置 `LING_UPDATE_URL` 时才会访问该地址

## 与私有构建的差异

开源候选只保留可公开的运行时与构建配置，已移除：

- 内测 / 公测渠道注入、`preset.js` 与异或解混淆逻辑
- 任何内置 API / DeepL Key、`*.properties`、签名证书与内测安装包
- 依赖仓库外 1.59 模板 zip、维护者签名身份、绝对路径的打包脚本
- 旧版 README、技术分析报告、机器专属 smoke 脚本
- 原 Minecraft 草方块风格图标与旧版探针图（均替换为几何版本）

另外，本轮短暂加入的 Anki 导入 UI 已撤销，当前源码不包含该入口，也不应作为新功能宣传。

应用图标与 `probe.png` 均为本项目用纯几何图形程序化生成，不含第三方美术素材。`probe.png`（128×128）不是一次性文件：设置卡里的「检测连接」每次都把它作为最小图片请求发给用户填写的模型，用来确认该模型能收图；1×1 PNG 会被部分网关判为无效图片，所以保留了这张实体图。界面小球本身由 CSS 绘制，未使用位图。

打包时会把 `LICENSE` 与 `THIRD_PARTY_NOTICES.md` 一并放进应用包；Electron 运行时自带的 `LICENSE` 与 `LICENSES.chromium.html` 由 electron-builder 随框架保留。

## 安全模型与已知限制

- 渲染进程启用 `contextIsolation`，但 `sandbox` 为 `false`，页面未设置 CSP；`preload.js` 暴露的 IPC 面较大。请只加载仓库内的本地页面，不要把远程内容嵌入窗口。
- 更新 manifest 只接受 `http(s)` 地址与 64 位 SHA-256；下载完成后校验通过才落盘为最终文件。即便如此，更新源仍由使用者自行负责。
- 屏幕识别依赖系统截屏权限；划词翻译在 macOS 上需要辅助功能权限（或改用「复制即译」）。
- 仓库内的 Anki 解析模块（`anki.js`，当前未接入界面）只支持 `collection.anki2` / `collection.anki21`；`collection.anki21b`（zstd 压缩）不在支持范围内。新增导入 UI 已撤销，未作为本版本功能发布。
- 不包含自动更新安装器：更新只会下载 zip 并在文件管理器中定位，由使用者自行替换。
- 在 macOS 上通过浏览器调试工具 `evaluate` 访问窗口时，隔离世界不暴露 `orbApi`，而主世界 CDP 可用。这是调试工具的限制，不是产品缺陷；不以该现象推断接口缺失。

## 许可

本项目自有代码以 MIT 许可证发布，全文见 [LICENSE](LICENSE)。第三方组件与参考来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
