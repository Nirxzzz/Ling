# Changelog

## 2.7.3（versionCode 273）— 未发布

> 本条只记录在公开副本里实际发生的结果：构建、单元测试与冒烟都已真实执行。没有 tag、没有 Release，
> 也没有对外分发的安装包；本机产物是 debug 签名 APK。

### 签名闸门

- 签名校验从**配置阶段**移到**执行阶段**：`app/build.gradle.kts` 在配置阶段只读取 `keystore.properties`
  并记录缺失原因，不再抛 `GradleException`。此前的写法让没有密钥的机器连 `assembleDebug` /
  `testDebugUnitTest` / `help` 都起不来，对公开仓库是常态性阻塞。
- 新增 `checkReleaseSigning` 任务，由 `androidComponents.onVariants` 只绑到 release 变体的四个产物出口
  （`package<Variant>`、`package<Variant>Bundle`、`package<Variant>UniversalApk`、`sign<Variant>Bundle`），不按命令行任务名匹配。
  材料检查做两次：配置阶段决定是否建 `signingConfigs.release`，执行阶段重读磁盘再验一次；以下任一情况都让该任务抛错、
  打包失败：`keystore.properties` 不存在；缺 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 任一项；
  `storeFile` 指向的文件不存在；配置阶段没建出 `signingConfigs.release`（例如 keystore 在配置之后才出现，重跑构建即可）。
- 仍然**不**退回 debug 签名、**不**产出未签名 release 包：`signingConfigs.release` 只在材料齐备时创建，
  `buildTypes.release` 只写 `signingConfig = signingConfigs.findByName("release")`。闸门保证的是「材料齐备且签名配置已绑定」，
  真正的签名由 AGP 完成；本仓库没有实际签出、安装或分发过 release 包。
- debug 与单测任务不依赖闸门。已在无 `keystore.properties` 的机器上实测：debug 构建与单测正常进行，
  缺签名材料的 release 出口（`packageRelease` / `signReleaseBundle` / `packageReleaseBundle`）由 `checkReleaseSigning` 报错失败。
- 修掉 `MainActivity.kt` 里 `apiReady()` 对已删除的 `prefs.presetId` 的引用（快照键改为只取 `prefs.baseUrl`）后，
  主源码与测试源码均编译通过。

### 构建与测试（实际执行结果）

- `gradle testDebugUnitTest assembleDebug` 构建成功（约 36 秒），主源码与测试源码均编译通过。
- `assembleDebug` 产出 debug APK：15,315,144 字节，`applicationId=com.chun.lingzhu.opensource`，
  `versionName 2.7.3` / `versionCode 273`，`sha256 972f938ab9706cf7f060a0d1495ff55493a2227be9270d7c81e9cbd175c229d2`。
  这是调试签名包，不是生产签名，不能覆盖官方 App。
- `testDebugUnitTest`：153 个测试类、**2380 项，0 失败 / 0 错误 / 0 跳过**。
- 设备冒烟（Android 14 专用只读模拟器）：安装后冷启动 `ChatActivity` 正常；保存笔记后杀进程冷启动仍可见；
  未填接口时「检测连接」如实提示缺少接口地址 / 模型，没有假成功；logcat 无 `FATAL EXCEPTION` / `ANR in`。
  仅覆盖主干路径，未接真实供应商。
- 没有产出签名的 release 包：本机没有 `keystore.properties`，release 产物出口由 `checkReleaseSigning` 拦下。

### 版本

- `versionName` 2.7.3，`versionCode` 273。
- 尚未发布：没有 tag、没有 Release，也没有对外分发的产物；上面的 APK 只在本机临时目录。

## 2.0.10（versionCode 218）— 预发布，待主脑确认

> 本条目记录已实施的源码改动，拟随预览 tag `preview-20261017-accuracy` 发布。
> 最终 Gradle 编译、单元测试与真机烟测由主脑统一执行确认；在验收和实际发布完成前，
> 不得对外声称本版本已通过测试或已发布。

### 做题与追问

- 首轮先给「## 答案」作为面板顶部锚点；答案之后按题型和实际需要写依据、公式和步骤，
  不再强制固定「答案 / 解析 / 考点」三段式，也不强制逐个分析所有错项。
- 末尾要求用一两句话核对结果（代回、验算、单位、范围、正负号、选项匹配）；
  题目条件缺失时明确说缺哪一项，不自己补条件编题。
- 追问移除 120 字硬上限；优先针对本轮、不重复，但允许指出并更正上一轮的错误后重算。
- 做题模式的追问不再受「追问省图」设置影响，始终保留原图和已有附图，方便重新核对原题；
  非做题模式仍尊重用户的省图选择。续图追问以最新截图为准，但不丢掉原图。
- 追问的 system 提示不再带关键词元数据规则；追问话术明确禁止补关键词清单行。

### 完整性与缓存

- `ApiClient` 区分正常 `[DONE]` / `finish_reason=stop` 与意外 EOF；`length`、`content_filter`、
  未执行的工具调用不算完成，合法的工具调用结束保留为正常终止。
- `Solver` 用独立 `Outcome` 状态传播完整 / 截断 / 未完成 / 取消，不依赖正文里的警告字符串；
  取消优先于 `stop` / `DONE`。
- 截断或意外结束时保留已显示的正文并明确标「这一轮未完成」，但不写正常结果缓存、
  不补关键词、不把半截回答记入追问对话；取消同样不污染已完成的对话。
- 识别和追问缓存键加入提示词版本，旧格式缓存不再命中。

### 预算与思考

- 做题识别预算 16384，做题追问至少 8192；纯小白仍按既有倍率。
- 做题不再自动关掉用户选择的思考强度重试。
- 接口明确拒绝 `max_tokens` 时保留 4096 兼容回退，并在状态行提示接口限制。

### 测试（已更新，结果待确认）

- `ApiTest` 补充本地 HTTP SSE 用例：做题追问在「省图」开着时仍带原图，且
  `finish_reason=length` / 意外 EOF 的半截回答不记入 `Solver.Conversation`。
- 删除只断言提示词原文、固定字数 / 预算档位的过时用例（`AnswerFreeformWiringTest`
  整体移除；`PromptsTest` / `CostPolicyTest` / `CacheTest` 只保留纯函数与真实行为契约）。
- 以上测试源码已更新；最终执行结果待主脑确认，本文件不预写通过数量。

### 版本

- `versionName` 2.0.10，`versionCode` 218。
- 拟发布 tag：`preview-20261017-accuracy`（验收通过后发布，尚未发布）。
