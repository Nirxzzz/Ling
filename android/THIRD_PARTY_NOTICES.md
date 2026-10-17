# Third-party notices

This file covers the Android open-source candidate in this directory. The
project's own code is licensed under the MIT license in [LICENSE](LICENSE).
Third-party components remain under their own licenses.

License identifiers below were checked against the artifacts' published
metadata on 2026-10-17 (Maven Central POM files and the upstream repositories),
not inferred from memory.

## Runtime dependencies bundled into the APK

| Component | Version | License | Verification |
|---|---|---|---|
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | Apache License 2.0 | Maven Central POM |
| `org.apache.poi:poi-scratchpad` | 4.1.2 | Apache License 2.0 | Maven Central POM |
| `org.apache.poi:poi` (transitive) | 4.1.2 | Apache License 2.0 | Maven Central POM |
| `commons-codec:commons-codec` (transitive) | 1.13 | Apache License 2.0 | declared by `poi` POM |
| `org.apache.commons:commons-collections4` (transitive) | 4.4 | Apache License 2.0 | declared by `poi` POM |
| `org.apache.commons:commons-math3` (transitive) | 3.6.1 | Apache License 2.0 | declared by `poi` POM |
| `com.zaxxer:SparseBitSet` (transitive) | 1.2 | Apache License 2.0 | declared by `poi` POM |
| `org.bouncycastle:bcprov-jdk15to18` (transitive) | 1.72 | Bouncy Castle Licence (MIT) | declared by `pdfbox-android` POM |
| `org.bouncycastle:bcpkix-jdk15to18` (transitive) | 1.72 | Bouncy Castle Licence (MIT) | declared by `pdfbox-android` POM |
| `org.bouncycastle:bcutil-jdk15to18` (transitive) | 1.72 | Bouncy Castle Licence (MIT) | declared by `pdfbox-android` POM |

Full texts:

- Apache License 2.0: [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)
- Bouncy Castle Licence: [licenses/Bouncy-Castle-MIT.txt](licenses/Bouncy-Castle-MIT.txt)

`log4j:log4j` and `commons-logging:commons-logging` are explicitly excluded in
`app/build.gradle.kts`, so they are not part of the dependency graph.

## Test-only dependencies (not shipped in the APK)

| Component | Version | License |
|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 |
| `org.json:json` | 20240303 | Public Domain (Maven Central POM) |

These artifacts are only on the unit-test classpath and are not packaged into the
APK; the license identifiers above are listed for completeness.

## Ported code

`app/src/main/java/com/chun/lingzhu/Fsrs.kt` is a Kotlin port written against
the `ts-fsrs` implementation (`open-spaced-repetition/ts-fsrs`, `packages/fsrs/src`).

- License: MIT
- Copyright: Copyright (c) 2026 Open Spaced Repetition
- Full text: [licenses/MIT-ts-fsrs.txt](licenses/MIT-ts-fsrs.txt)

The Kotlin file notes the source files it follows and the three intentional
differences from the upstream implementation.

## Word lists (separate asset license)

`app/src/main/assets/wordbank/{junior,gaokao,cet4,cet6,ielts}.txt` are derived
from the Wiktionary / Project Gutenberg frequency list and are distributed
under **CC BY-SA 3.0**, not MIT.

- Source: <https://en.wiktionary.org/wiki/Wiktionary:Frequency_lists/PG/2006/04/1-10000>
- License: <https://creativecommons.org/licenses/by-sa/3.0/>
- Attribution and segmentation notes: `app/src/main/assets/wordbank/LICENSE.txt`

The three 考研 (kaoyan) word lists from the upstream build are **not** included:
their rights belong to the original authors/publisher and the upstream notice
states they are not covered by CC BY-SA. See the same LICENSE.txt.

## Launcher icon

`app/src/main/res/drawable/ic_launcher_foreground.xml` is an original geometric
vector drawn for this fork. No third-party icon set or generated image is used.

## Files packaged inside the APK

The Android build ships these files under `assets/`, so the notice and all
license texts travel with every APK rather than only with the repository:

- `assets/LICENSE` — this project's MIT license
- `assets/THIRD_PARTY_NOTICES.md` — this notice
- `assets/licenses/Apache-2.0.txt` — Apache License 2.0 full text
- `assets/licenses/Bouncy-Castle-MIT.txt` — Bouncy Castle Licence full text
- `assets/licenses/MIT-ts-fsrs.txt` — MIT license of the ported `ts-fsrs` code
- `assets/licenses/notices/NOTICE-poi.txt` — verbatim `META-INF/NOTICE` from
  `poi-4.1.2.jar` (`poi-scratchpad-4.1.2.jar` carries the identical file)
- `assets/licenses/notices/NOTICE-commons-codec.txt` — verbatim
  `META-INF/NOTICE.txt` from `commons-codec-1.13.jar`
- `assets/licenses/notices/NOTICE-commons-collections4.txt` — verbatim
  `META-INF/NOTICE.txt` from `commons-collections4-4.4.jar`
- `assets/licenses/notices/NOTICE-commons-math3.txt` — verbatim
  `META-INF/NOTICE.txt` from `commons-math3-3.6.1.jar`

`assets/wordbank/LICENSE.txt` covers the CC BY-SA 3.0 word lists.

The `assets/` copies are identical to the repository-root `LICENSE` and
`THIRD_PARTY_NOTICES.md` and to `licenses/`; the two locations must be updated
together.

## Packaging note

The packaging block still excludes duplicate `META-INF/LICENSE*` and
`META-INF/NOTICE*` entries: several bundled jars contain colliding copies and
the APK packager fails on the duplicate paths. Those excluded jar copies are
replaced by the `assets/` files listed above, which is what makes the notice and
license texts part of the APK.

The cached `pdfbox-android-2.0.27.0`, `SparseBitSet-1.2` and Bouncy Castle 1.72
artifacts contain no `META-INF` LICENSE/NOTICE entries, so no additional notice
text exists for them; the Apache-2.0 text above covers the Apache-licensed
artifacts that ship without their own NOTICE.
