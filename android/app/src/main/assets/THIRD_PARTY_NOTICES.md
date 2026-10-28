# Third-party notices

This file covers the Android open-source candidate in this directory. The
project's own code is licensed under the MIT license in [LICENSE](LICENSE).
Third-party components remain under their own licenses.

License identifiers below were checked against the artifacts' published
Maven Central POM metadata, not inferred from memory.

## Runtime dependencies bundled into the APK

| Component | Version | License | Verification |
|---|---|---|---|
| `com.github.luben:zstd-jni` | 1.5.7-3 | BSD 2-Clause | Maven Central POM |
| `org.yaml:snakeyaml` | 2.3 | Apache License 2.0 | Maven Central POM |
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | Apache License 2.0 | Maven Central POM |
| `org.apache.poi:poi-scratchpad` | 4.1.2 | Apache License 2.0 | Maven Central POM |
| `org.apache.poi:poi` | 4.1.2 (transitive) | Apache License 2.0 | declared by poi POM |
| `commons-codec:commons-codec` | 1.13 (transitive) | Apache License 2.0 | declared by poi POM |
| `org.apache.commons:commons-collections4` | 4.4 (transitive) | Apache License 2.0 | declared by poi POM |
| `org.apache.commons:commons-math3` | 3.6.1 (transitive) | Apache License 2.0 | declared by poi POM |
| `com.zaxxer:SparseBitSet` | 1.2 (transitive) | Apache License 2.0 | declared by poi POM |
| `org.bouncycastle:bcprov-jdk15to18` | 1.72 (transitive) | Bouncy Castle Licence (MIT) | declared by pdfbox-android POM |
| `org.bouncycastle:bcpkix-jdk15to18` | 1.72 (transitive) | Bouncy Castle Licence (MIT) | declared by pdfbox-android POM |
| `org.bouncycastle:bcutil-jdk15to18` | 1.72 (transitive) | Bouncy Castle Licence (MIT) | declared by pdfbox-android POM |
| `com.google.zxing:core` | 3.5.3 | Apache License 2.0 | Maven Central parent POM |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache License 2.0 | Maven Central POM |

`log4j:log4j` and `commons-logging:commons-logging` are explicitly excluded in
`app/build.gradle.kts`, so they are not part of the dependency graph.

## Test-only dependencies (not shipped in the APK)

| Component | Version | License |
|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 |
| `org.json:json` | 20240303 | Public Domain |

## Ported code

`app/src/main/java/com/chun/lingzhu/Fsrs.kt` is a Kotlin port written against
the `ts-fsrs` implementation (`open-spaced-repetition/ts-fsrs`).
`AgentContext.kt` follows the structure of `opencode`'s session-compaction
prompt, rewritten independently.

- `ts-fsrs` — MIT — full text in [licenses/MIT-ts-fsrs.txt](licenses/MIT-ts-fsrs.txt)
- `opencode` — MIT — full text in
  `app/src/main/assets/licenses/opencode-MIT.txt`

## Word lists (separate asset license)

`app/src/main/assets/wordbank/{junior,gaokao,cet4,cet6,ielts}.txt` are derived
from the Wiktionary / Project Gutenberg frequency list and are distributed
under **CC BY-SA 3.0**, not MIT.

- Source: <https://en.wiktionary.org/wiki/Wiktionary:Frequency_lists/PG/2006/04/1-10000>
- License: <https://creativecommons.org/licenses/by-sa/3.0/>
- Attribution and segmentation notes: `app/src/main/assets/wordbank/LICENSE.txt`

The 考研 word lists shipped by the upstream build are **not** part of
this copy: their rights belong to the original publisher and the upstream
notice states they are not covered by CC BY-SA.

## Launcher icon

`app/src/main/res/mipmap-*/ic_launcher*.png` are original artwork drawn for
this fork. No third-party icon set or generated image is used.

## Files packaged inside the APK

The Android build ships these files under `assets/`:

- `assets/LICENSE` — this project's MIT license
- `assets/THIRD_PARTY_NOTICES.md` — this notice
- `assets/licenses/Apache-2.0.txt` — Apache License 2.0 full text
- `assets/licenses/BSD-2-Clause.txt` — BSD 2-Clause full text (zstd-jni)
- `assets/licenses/Bouncy-Castle-MIT.txt` — Bouncy Castle Licence full text
- `assets/licenses/MIT-ts-fsrs.txt` — MIT license of the ported `ts-fsrs` code
- `assets/licenses/okhttp-Apache-2.0.txt` — okhttp license note and text
- `assets/licenses/opencode-MIT.txt` — opencode MIT text
- `assets/licenses/notices/` — verbatim `META-INF/NOTICE*` from the bundled jars
- `assets/wordbank/LICENSE.txt` — CC BY-SA 3.0 attribution for the word lists

## Packaging note

The packaging block excludes duplicate `META-INF/LICENSE*` and
`META-INF/NOTICE*` entries: several bundled jars contain colliding copies and
the APK packager fails on those duplicate paths. The `assets/` files listed
above replace them, so the notice and license texts still travel with every
APK.
