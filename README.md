# Ling

Ling 是一款常驻桌面的悬浮小球工具：双击截屏识别屏幕内容，选中文字后按快捷键翻译，结果在半透明面板中流式显示。本仓库以 MIT 许可公开 Android 客户端与 macOS / Windows 桌面端的源码、资源和测试。

## 仓库性质

- 仓库地址：<https://github.com/Nirxzzz/Ling>，已公开（`private=false`、`visibility=public`），默认分支为 `main`。
- 采用**单仓库**结构：根目录即仓库根，`android/` 与 `desktop/` 都是它的子目录，不各自独立建库；`android/.githooks/pre-commit` 由仓库根通过 `git config core.hooksPath android/.githooks` 启用。
- 仓库自有源码以 **MIT** 许可公开，全文见根目录 [LICENSE](LICENSE)；第三方组件、字体与词表仍按各自授权分发，详见下文「许可与第三方资产」。
- 公开可见只说明仓库内容可被访问，不代表已发布成品、已提供安装包，也不代表下文验证记录之外还有额外验收。
- 不发布 Release，不提供 APK、安装包或桌面压缩包下载；仓库内为源码、测试、许可与构建配置。

## 包含内容

### Android

- 版本 `2.0.9`（`versionCode 217`），包名 `com.chun.lingzhu.opensource`。
- 使用独立 `applicationId`，不能覆盖、升级或替换官方 `com.chun.lingzhu`，数据目录与 SharedPreferences 与官方 App 隔离。
- Debug 构建使用 Android 调试签名，不是生产签名；Release 构建需要使用者自备签名配置。
- 不内置模型、DeepL 或翻译服务 Key；默认更新源为空，接口与更新清单由使用者自行填写。
- 仅保留 5 套已署名的 CC BY-SA 3.0 词表；授权链不完整的考研词库未纳入。
- 构建要求与方法见 **[android/README.md](android/README.md)**。

### 桌面端（macOS / Windows）

- 应用版本 `1.60.2`，Electron `37.10.3`，macOS arm64 与 Windows x64 共用同一份源码。
- 应用标识 `com.ling.opensource`，用户数据目录 `ling-opensource`，与官方客户端隔离。
- 功能包括屏幕识别、划词翻译、悬浮答案面板、知识卡与 FSRS 复习、Anki `.apkg` 解析模块。
- 不内置任何 API Key、内测配置、签名材料或发布模板；不配置更新源时更新功能保持禁用。
- 构建要求与方法见 **[desktop/README.md](desktop/README.md)**。

## 构建入口

| 目标平台 | 主要产物 | 命令 | 详细说明 |
| --- | --- | --- | --- |
| Android | Debug APK；Release 需自备签名 | `cd android && gradle testDebugUnitTest assembleDebug` | [android/README.md](android/README.md) |
| macOS arm64 | `.zip`（未签名、未公证） | `cd desktop && npm ci && npm run pack:mac` | [desktop/README.md](desktop/README.md) |
| Windows x64 | `.zip`（未签名） | `cd desktop && npm ci && npm run pack:win` | [desktop/README.md](desktop/README.md) |

两端的具体依赖版本、签名配置、可复现性边界和产物状态，均以上述子目录 README 为准。整理本根 README 时没有重新执行构建或测试。

## 已执行的验证

以下结果来自本仓库整理阶段的真实执行，按原记录保留；不因本文件的修改而重新验证，也不构成全面实测。

### Android

- `testDebugUnitTest` + `assembleDebug` 首轮 `15m27s` 通过；补齐 assets 许可文件后最终 `57s` 通过。
- JUnit XML 汇总 **1289 tests，0 failures / 0 errors / 0 skips**。
- APK 内含 MIT 全文、三份第三方许可全文与 4 份 NOTICE，不含 `kaoyan` 资产。
- 在隔离的 Android 14 专用只读模拟器上完成真实安装并启动 `ChatActivity` 成功，进程存活，截图已目视检查。
- 首次启动且未配置 API 时，提示缺少接口 / 权限；点击「去填写」进入首次配置引导，而不是直接跳转 API 页。
- 未接入真实模型，未验证物理振动，也未做全面功能验收。

### 桌面端

- `npm ci` 成功；`npm test` 共 **66 项，0 失败**；另有 Anki 自定义用例 **8/8 通过**。
- macOS arm64 与 Windows x64 均由 `electron-builder` `26.15.3`、基于 Electron `37.10.3` 打包成功，不依赖旧模板 zip。
- macOS 最终实现真实运行过：`baseUrl` / `model` / `key` 为空，未配置更新源，更新按钮禁用，设置页截图已目视检查。
- Windows 包未在真机运行，只验证了打包与 zip CRC。
- macOS 包未签名、未公证；Android Debug 包为非生产签名，不能覆盖官方安装。
- 打包脚本使用 `--publish never`，打包过程未上传任何产物。

## 已知限制

- 在 macOS 上用浏览器调试工具 `evaluate` 访问窗口时，隔离世界不暴露 `orbApi`；主世界 CDP 可用。这是调试工具限制，不是产品缺陷。
- 桌面端 Anki 新增导入 UI 已撤销，本仓库不提供、也不宣传该新功能；`anki.js` 仅保留解析能力。
- 测试数量少于上游生产工程，原因是排除版权语料相关用例、删除已移除的预设机制，以及调整过时的接线 / 默认断言。这是明确的范围差异，不是等价全量验收。
- iOS 按既定决定暂停，没有工程或 IPA。
- Android 目录不带 Gradle wrapper、依赖校验和或依赖锁定，不承诺字节级可复现。
- Windows 包未做代码签名，也未在 Windows 真机上运行。

## 许可与第三方资产

- 本仓库自有源码采用 **MIT** 许可，版权人为 **Ling contributors**，年份 **2026**，全文见根目录 [LICENSE](LICENSE)。Android 与桌面端两份 LICENSE 的版权人与年份与此一致，因此根 LICENSE 直接沿用，没有另行改写。
- 第三方组件、字体、依赖与参考资料不适用上述 MIT 许可，各自保留原有授权。Android 见 [android/THIRD_PARTY_NOTICES.md](android/THIRD_PARTY_NOTICES.md)，桌面端见 [desktop/THIRD_PARTY_NOTICES.md](desktop/THIRD_PARTY_NOTICES.md)。
- Android 内置的 5 套词表以 **CC BY-SA 3.0** 分发，署名与分级说明见 `android/app/src/main/assets/wordbank/LICENSE.txt`；代码的 MIT 许可不覆盖这些词表。
- 本文件不重写、不合并、不替代任何第三方许可证；如第三方条款与 MIT 冲突，以第三方条款为准。

## 安全与分发边界

- 仓库公开范围仅限仓库内容本身，不构成完整安全审计；静态检查不覆盖提交历史、构建环境和全部依赖。
- 不使用官方自动更新源，不内置生产签名与内测密钥；使用者需自行确认所填接口、Key 与更新清单的合法性和完整性。
- 源码与官方成品在标识、签名、资产和内置接口上存在明确差异。不要把本仓库的构建产物称为已发布的官方版本，也不要用它覆盖官方安装。
- 旧官方成品与历史记录不在本仓库范围内，也不因本仓库的存在而改变。
