<div align="center">

<img src="desktop/build/icon.png" alt="Ling floating orb" width="104" height="104">

# Ling

### Your attention. Your AI. A little closer to real life.

An everyday AI companion that can step in rather than just wait for a prompt.
A bridge between you and your agents and the things happening on your screens.

**Android · macOS · Windows**

[Download](#download) · [Get started](#get-started) · [Agents and devices](#give-your-agent-a-way-in) · [Obsidian and Anki](#keep-the-tools-you-already-like) · [Build from source](#build-from-source)

</div>

---

## A very capable AI. In another window. Again.

You are halfway through something. A question comes up. You know an AI could help.

So you leave what you were doing. Open a chat. Carry over a screenshot. Explain the context. Carry the answer back. Somewhere along the way you become the integration layer.

Meanwhile everything else seems perfectly capable of coming to you. Messages arrive. Feeds keep moving. Another app has something very important to say about absolutely nothing.

**Ling starts with a simple question: what if something in that crowd was actually on your side?**

Not another destination to spend your day in. Something within reach that can help you understand a screen or keep a thought or hand a task to an agent. Sometimes you call it. Sometimes it steps in first.

## Yes. It is meant to interrupt.

“Intrusive” is usually a complaint. Here it is a deliberate design choice.

A floating orb stays nearby. A vocabulary card can enter the moment. Focus features can try to break a drift into something you did not mean to spend your evening on.

The idea is to **compete for your attention so you can take it back**. Not to win another hour of engagement for Ling.

There is an obvious catch. A tool that interrupts you can become part of the problem. So interruption is a means rather than the goal. The point is to put a choice back in your hands. It is not a promise to cure distraction.

And attention is only the starting point. Ling is not meant to decide that your whole life should now be a study session. You might be reading or making something or following a conversation or figuring out what to do next. The useful part should follow you into the situation. Not force the situation into a preset category.

## The last mile of AI

A model can know a lot and still be awkward to reach. An agent can plan a task and still lack a useful connection to your phone or your personal knowledge.

Ling is an attempt to close that gap.

**A nearby interface for you. A controlled tool surface for your agents. A path between your devices and the knowledge you want to keep.**

That is the product direction. A more capable everyday companion rather than one narrowly defined utility. The current implementation has limits. They are listed below without pretending the direction is already the destination.

## Give your agent a way in

You do not have to make Ling your only AI assistant. It can also provide tools to an agent you already use.

The desktop client includes a local **Model Context Protocol server**. MCP-capable clients can request tools for memory and knowledge cards. Other tools cover review context and reminders and saving handouts. A paired Android phone adds a route to supported phone actions.

- **For your agent:** search or add memory. Search or create cards. Retrieve review context. Save a handout.
- **For your phone:** request screen reading or open an allowed app through the optional phone link. Phone screen reading returns text rather than forwarding a raw screenshot to the external agent.
- **For your control:** token authentication and permission scopes. Sensitive phone actions require approval on the phone. This is not unrestricted remote control.

The MCP service binds to loopback on the desktop. A **stdio bridge** is included for clients that need that transport. Enable it in desktop settings and use the generated connection configuration. Keep the token private.

**What about Hermes or another agent?** The connection point is MCP rather than a vendor-specific integration. If your agent setup supports the required MCP transport and authentication then that is the interface to evaluate. There is no dedicated Hermes adapter in this repository. Hermes compatibility has not been tested.

The MCP and phone-link paths exist in source. They have not completed end-to-end acceptance for this release.

## Three platforms. A phone and computer that can talk.

Ling has an Android client and a shared desktop codebase for macOS and Windows. There are two distinct ways to connect Android with a desktop:

- **LAN sync:** manually exchange knowledge cards and starred answer history over a private network. Transfers use AES-256-GCM. This is a snapshot exchange rather than continuous cloud sync.
- **Phone Link:** explicitly pair the Android phone with the desktop. A persistent connection lets desktop MCP tools reach supported phone capabilities. Available actions depend on phone permissions and confirmation.

These are **Android-to-desktop connections**. They do not form an automatic three-way mesh between a phone and two computers. Neither path has completed end-to-end acceptance for this release. The Windows build has not been run on a Windows machine.

## Keep the tools you already like

### Obsidian-friendly Markdown on Android

Your notes do not have to become permanent residents of one application.

Android notes use **Markdown files with YAML frontmatter**. They support `[[wiki links]]` with Obsidian-style short paths. Folder import and export use the Android system file picker. This gives you a plain-file route into an existing Markdown workflow.

That is the sense in which Ling is Obsidian-friendly. It is **not an Obsidian plugin**. It does not provide live vault synchronization or promise full round-trip compatibility with every plugin and attachment convention. The desktop client currently has no notes interface. This workflow has not completed end-to-end acceptance for this release.

### Bring an Anki deck. Keep learning.

Android includes an actual **Anki `.apkg` import interface**. The parser handles `collection.anki21b` packages with zstd compression as well as older collection formats. Imported cards use Ling's own FSRS review progress. Anki review history is not imported.

The desktop source includes an `.apkg` parser but **no import interface**. Its parser does not support `collection.anki21b`. There is no AnkiWeb sync. Android import has not completed end-to-end acceptance for this release.

Ling also lets you save useful answers as knowledge cards. The idea is not to collect answers forever. It is to have a way back to something worth remembering.

## What else is within reach?

| Capability | Android | Desktop |
| --- | --- | --- |
| Screen questions | Screen recognition and chat with follow-up questions | Double-click the orb for capture and a streaming answer panel |
| Translation | Translation with your configured services | Selection translation through AI or DeepL or Baidu |
| Knowledge and review | Knowledge cards with FSRS and five vocabulary collections | Save answers as cards and review with FSRS |
| Records | Chat history and Markdown notes with tasks and reminders | Answer history with stars and caching. No notes interface |
| Voice | Real-time transcription and voice conversation | Transcription and voice conversation with microphone or system audio or mixed input |
| Focus and companionship | Focus features and companion bubbles | Companion bubbles and macOS foreground-app-based focus features |

Cloud transcription uses Doubao or Alibaba Cloud. Voice conversation uses Tencent Cloud speech recognition followed by your configured model and Tencent Cloud speech synthesis. These need separate credentials. A model API key alone does not unlock every service.

Windows does not have the macOS foreground-app-based focus capability. Cloud voice quality and the advanced paths above have not completed end-to-end acceptance for this release.

## Download

Source and downloads live in this same repository. The current archive is **Android 2.7.3 + desktop 1.64.1** at tag [`v2.7.3-1.64.1`](https://github.com/Nirxzzz/Ling/tree/v2.7.3-1.64.1).

| Platform | Download | Before you install |
| --- | --- | --- |
| Android 8.0+ | [APK 2.7.3](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/Ling-2.7.3-opensource-debug.apk) | Debug-signed. Separate package identity. Cannot replace the original official app |
| macOS Apple Silicon | [ZIP 1.64.1](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/Ling-1.64.1-mac-arm64.zip) | No Developer ID signing or notarization. The packaged app was run on macOS |
| Windows x64 | [ZIP 1.64.1](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/Ling-1.64.1-win-x64.zip) | Unsigned. Built but not run on Windows |

[Release notes](https://github.com/Nirxzzz/Ling/releases/tag/v2.7.3-1.64.1) · [SHA-256 checksums](https://github.com/Nirxzzz/Ling/releases/download/v2.7.3-1.64.1/sha256.txt) · [All releases](https://github.com/Nirxzzz/Ling/releases)

These are open-source build archives. They are not the original official distribution channel. There is no production-signed Android release package.

**This introduction is in English. The current client interfaces and platform development guides are primarily in Chinese.** Changing the repository language does not change the app language.

## Get started

1. **Install the appropriate archive.** Android uses the APK. On macOS extract the ZIP and open `Ling.app`. On Windows extract the ZIP and open `Ling.exe` with the unverified runtime status above in mind.
2. **Bring your model.** Enter an OpenAI-compatible Base URL and model name and API key in settings. Check the connection and save. Screen questions need a model that accepts images.
3. **Grant permissions for what you use.** Screen capture and overlays and selection access depend on platform permissions. If macOS blocks the app then verify the source and checksum before using Open Anyway in System Settings → Privacy & Security.
4. **Try something already in front of you.** Ask about the screen. Follow up. Keep the useful part. Agent connections and phone pairing are optional next steps rather than prerequisites.

Ling does not include service credentials or model credits. Missing configuration does not silently switch you to a bundled model server. Updates have no official source configured by default.

**Privacy and cost:** configured services may receive screenshots or selected text or audio depending on the feature. Providers may charge for requests. Use endpoints you trust and check their policies before sending sensitive content.

## What has actually been checked?

- **Android 2.7.3:** compilation and 2380 unit tests passed. An Android 14 emulator smoke run covered cold launch and note persistence across restart and missing-interface feedback. No fatal exception or ANR was observed in that run.
- **Desktop 1.64.1:** 302 unit tests and 8 module-loading smoke checks passed. Source and the final macOS package were run through configuration and screen questions and follow-ups. History persistence and card saving and review scheduling were exercised. HTTP 500 handling and cancellation recovery were also checked.
- **Published files:** all three binary archives were downloaded again from GitHub. Their SHA-256 hashes matched the local delivery artifacts.

The desktop question flow used a local fixture service. This checks protocol and UI behavior rather than real-model recognition quality. It does not certify the answers a provider will produce.

**Not end-to-end verified for this release:** Windows runtime. Real-provider quality. Cloud voice. LAN sync. MCP. Phone Link. Hermes compatibility. Android Anki import. Markdown folder exchange. Clipboard translation. Focus and companion features. Update downloads.

Detailed records remain in the [Android guide](android/README.md) and [desktop guide](desktop/README.md).

## Build from source

```bash
git clone https://github.com/Nirxzzz/Ling.git
cd Ling
```

### Desktop

Use **Node.js 24+** with npm. Initial installation and packaging require network access for Electron downloads.

```bash
cd desktop
npm ci
npm start
```

Run `npm test` for the existing suite. Use `npm run pack:mac` or `npm run pack:win` for ZIP packaging.

[Desktop development guide](desktop/README.md)

### Android

Use **JDK 17 + system Gradle 8.14 + Android SDK 35**. Configure your local SDK path first. This repository does not include a Gradle wrapper.

```bash
cd android
gradle testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Debug builds need no private signing material. Release builds require your own keystore and fail explicitly when it is missing. They do not fall back to debug signing.

[Android development and signing guide](android/README.md)

Neither client promises byte-for-byte reproducible builds. Android has no dependency lock or checksum configuration. Desktop locks npm dependencies but runtime downloads and archive metadata still affect output bytes.

## A few boundaries worth knowing

- **Separate identities:** Android uses `com.chun.lingzhu.opensource`. Desktop uses `com.ling.opensource` with `ling-opensource` as its default data directory. These builds do not replace the original official installation.
- **No automatic update installer:** desktop updates only download a ZIP and reveal the file. No update source is enabled by default.
- **No iOS build:** there is no iOS project or IPA here. macOS packaging targets Apple Silicon rather than Intel Macs.
- **Model answers can be incomplete:** desktop streaming does not separately label `finish_reason=length`. Verify important conclusions.
- **Voice is not guaranteed echo-free:** system echo cancellation and software checks have not been validated across physical devices.
- **Desktop security needs care:** renderer sandboxing is disabled and no CSP is set. The preload exposes a broad IPC surface. Load only the bundled local pages. Public source is not a completed security audit.

## Say hello or report a bug

[Open an issue](https://github.com/Nirxzzz/Ling/issues) with your platform and Ling version. Tell us what you did and what happened. A reproducible problem is much easier to fix than “it got weird.” Both are understandable. Only one gives us a starting point.

Remove API keys and personal content from screenshots and logs before posting. Contributions to compatibility and documentation are welcome alongside code fixes. Keep credentials and signing material out of commits.

## License and credits

Project-owned code is **GPL-3.0-only**. Copyright © 2026 Ling contributors. See [LICENSE](LICENSE) for the full terms and warranty disclaimer. The appendix examples do not change this project's only-version license choice.

Earlier copies distributed under MIT retain the permissions granted at that time. The current license does not revoke those grants.

Third-party components retain their own licenses. See the [Android notices](android/THIRD_PARTY_NOTICES.md) and [desktop notices](desktop/THIRD_PARTY_NOTICES.md). No blanket compatibility claim is made for third-party terms.

The five bundled vocabulary datasets are separate **CC BY-SA 3.0** data assets rather than GPL-covered project code. Attribution and sources are in the [word-list license](android/app/src/main/assets/wordbank/LICENSE.txt).

Binary redistribution must meet the corresponding-source requirements of GPLv3 Section 6 through an allowed distribution method. Provide the matching source and required build scripts. A generic repository link alone does not automatically meet that obligation. Modified binaries require their matching modified source.

Production signing material and internal service credentials are not included.
