# Third-party notices

This file covers the desktop (Electron) build in this directory. The
project's own code is licensed under the MIT license in [LICENSE](LICENSE).
Third-party components remain under their own licenses.

The table below is generated from `package-lock.json`: it lists every
production dependency that ships inside `app.asar`, with the version and
license identifier that the package itself declares. Regenerate it whenever
the lock file changes.

## Runtime dependencies bundled into app.asar

| Component | Version | License |
|---|---|---|
| `@hono/node-server` | 2.1.5 | MIT |
| `@modelcontextprotocol/client` | 2.4.0 | Apache-2.0 |
| `@modelcontextprotocol/core` | 2.4.0 | Apache-2.0 |
| `@modelcontextprotocol/node` | 2.1.2 | Apache-2.0 |
| `@modelcontextprotocol/server` | 2.4.0 | Apache-2.0 |
| `cross-spawn` | 7.0.6 | MIT |
| `eventsource` | 3.0.7 | MIT |
| `eventsource-parser` | 3.1.1 | MIT |
| `hono` | 4.13.13 | MIT |
| `isexe` | 2.0.0 | ISC |
| `jose` | 6.2.13 | MIT |
| `path-key` | 3.1.1 | MIT |
| `pkce-challenge` | 5.0.1 | MIT |
| `shebang-command` | 2.0.0 | MIT |
| `shebang-regex` | 3.0.0 | MIT |
| `which` | 2.0.2 | ISC |
| `ws` | 8.22.0 | MIT |
| `zod` | 4.6.7 | MIT |

## Electron (application shell, not part of app.asar)

- Electron 37.10.3 — MIT — <https://github.com/electron/electron>
- Chromium is bundled by Electron and ships its own `LICENSES.chromium.html`
  with every packaged build; keep that file when redistributing.

## License texts

Full text of each license follows. MIT and ISC cover the packages listed
above; the Apache-2.0 text applies to the `@modelcontextprotocol/*` packages.

### Apache-2.0

Applies to: `@modelcontextprotocol/client`, `@modelcontextprotocol/core`, `@modelcontextprotocol/node`, `@modelcontextprotocol/server`

The Apache License 2.0 applies; the full text ships inside each
`node_modules/<package>/LICENSE` file in `app.asar`, as required by
section 4 of that license.

### ISC

Applies to: `isexe`, `which`

```
ISC License

Copyright (c) respective package authors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
PERFORMANCE OF THIS SOFTWARE.
```

### MIT

Applies to: `@hono/node-server`, `cross-spawn`, `eventsource`, `eventsource-parser`, `hono`, `jose`, `path-key`, `pkce-challenge`, `shebang-command`, `shebang-regex`, `ws`, `zod`

```
MIT License

Copyright (c) respective package authors

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
