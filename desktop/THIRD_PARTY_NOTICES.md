# Third-Party Notices

Ling 桌面端（Electron）包含或参考了以下第三方作品。本文件不改变这些作品各自的许可条款。

## Electron

- 组件：Electron 37.10.3（运行时 / 打包基础）
- 用途：桌面应用容器（Chromium + Node.js）
- 许可：MIT
- 来源：https://github.com/electron/electron

```
Copyright (c) Electron contributors
Copyright (c) 2013-2020 GitHub Inc.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Chromium

- 组件：Electron 内置的 Chromium
- 许可：BSD 3-Clause 及若干其他开源许可（逐项见 Electron 发行包内的 `LICENSES.chromium.html`）
- 来源：https://www.chromium.org/

打包产物会由 Electron 自带 `LICENSE` 与 `LICENSES.chromium.html`。分发构建产物时请保留这两个文件。

## ts-fsrs

- 组件：ts-fsrs（FSRS 间隔重复调度算法）
- 用途：`fsrs.js` 是该算法在 JavaScript 中的独立实现，逐行对照 ts-fsrs 的公开实现与公式；本项目不包含 ts-fsrs 的源码文件。
- 许可：MIT
- 来源：https://github.com/open-spaced-repetition/ts-fsrs

```
MIT License

Copyright (c) 2026 Open Spaced Repetition

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## fabric（提示词风格参考）

- 组件：danielmiessler/fabric 的若干 prompt patterns（`explain_terms`、`write_essay`、`analyze_claims`）
- 用途：仅作为提示词写作风格的参考；本项目的提示词文本为自行撰写，未直接复制上游文件。
- 许可：MIT
- 来源：https://github.com/danielmiessler/fabric

```
MIT License

Copyright (c) 2012-2024 Scott Chacon and others

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Anthropic Prompt Engineering Interactive Tutorial

- 组件：anthropics/prompt-eng-interactive-tutorial
- 用途：提示词设计思路参考（例如「证据不足时明确说不知道」）。
- 状态：上游仓库当前未声明开源许可证，因此本项目**没有复制或再分发**该教程的任何文本；仅作为公开写作原则的参考。
- 来源：https://github.com/anthropics/prompt-eng-interactive-tutorial
