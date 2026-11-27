# v2.42 - multiple characters + outfits

Version: versionName 2.42, versionCode 35.

- **Multi-character catalog (`CharacterCatalog.kt`, `CharacterStore`):** Added 5 distinct original 2D anime characters (`Yasmin`, `Aanya`, `Zoya`, `Meera`, `Kaira`), each with her own body art, hair mask, background mask, calibrated face anchor points (eyes, eyebrows, mouth, cheeks, tears, sweat, anger, gloom), skin/lash/brow palette, hair/ear/ahoge deformer zones, and voice-friendly default mood. `Yasmin` remains the first and default character with her exact original artwork and look preserved.
- **Outfit system (4 outfits per character):** Added 4 outfits for every character (`casual`, `party`, `traditional` lehenga/saree/anarkali/salwar, and `winter`/`school`/`office`) using transparent mesh-aligned RGBA overlay PNGs (`outfit_<char>_<outfit>.png`) with hair masks preserved on top, plus optional `fullArtRes` support. Outfits deform seamlessly with body sway, breathing, and hair swing.
- **Preferences > Character & Outfit (`MainActivity.kt`, `Prefs.kt`):** Added a live animated preview, character thumbnail picker, and 2-column outfit picker saved under `Prefs.CHARACTER_ID` (`character_id`) and `Prefs.CHARACTER_OUTFIT` (`character_outfit`). Switching characters or outfits updates the Home screen immediately without restarting or interrupting the active voice session, and persists across app restarts.
- **Renderer & performance (`AnimeCharacter.kt`):** Refactored `AnimeCharacter` to render from `CharacterSpec` + `OutfitSpec` with `CharacterBitmapCache` (lazy LRU decoding, ref-counted safe bitmap recycling, cached hair-weight grids, and pre-allocated paths/arrays for smooth 24+ fps on budget phones). Lip-sync strictly follows `AppState.speechLevel()` (assistant speaker output only, silent while the user speaks) across all 11 `set_emotion` moods.
- **Tests & CI:** Added `CharacterCatalogTest.kt` unit tests, Gradle 8.9 wrapper, and `.github/workflows/build.yml` (`testDebugUnitTest` + `assembleDebug` uploading `YASMIN-AI-apk`).

# v2.41 - Camera Vision is LIVE (video-call mode), model names fixed, camera race fixed, clean stop

Version: versionName 2.41, versionCode 34 (the code number follows the existing pattern: 2.21 = 14, 2.33 = 26, so 2.41 = 34). build.gradle.kts still said 2.33 while the changelog and the code comments (v2.38 / v2.39 / v2.40) were newer: the files are now consistent again. There was no changelog entry for v2.39 / v2.40 in this zip.

- **Live video, not one photo.** While LIVE VISION is ON, the camera page sends one small JPEG frame (768 px long side, quality 70) every 1 s (setting: 1 / 2 / 3 / 5 s) into the running Gemini Live session as `realtimeInput.video`. This is the documented Live API input: discrete image frames, about 1 FPS, 768 x 768 recommended. The model answers follow-up questions from the newest frame, with no new scan per question. Files: `LiveVision.kt` (new: state, throttle, camera generation), `VisionUi.kt`, `VisionBridge.kt`, `GeminiLive.kt` (`sendFrame` returns true/false and skips a frame when the socket has more than 600 KB unsent), `AssistantService.kt` (hooks).
- **Voice flow.** "Yasmin, camera open karke dekho": she says one short line ("Haan, dekhti hoon. Thoda sa waqt do."), calls `look_camera`, the page opens and binds the camera, and the tool answers only after the FIRST live frame really reached the voice session. Then she answers questions directly (no "I am looking" narration). New tool `stop_camera` ("camera band karo"). Left / right rules for the back camera and the (not mirrored) front camera are in the prompt (`Tools.kt`); if unsure she asks one short question. She cannot move the camera: she asks him to turn the phone.
- **Camera race fixed.** `ImageCapture` + `takePicture` + a fixed 900 ms delay are gone. CameraX now runs Preview + ImageAnalysis; every (re)bind gets a new "generation" and the camera is READY only when a real frame of the current binding arrives, so a stale `camReady` or a frame of the previous lens can never be sent after a lens switch. No front / back camera, permission denied and a camera that never delivers frames (7 s watchdog) show a real message and a RETRY button.
- **Model names.** `gemini-2.0-flash` (shut down 1 Jun 2026) and `gemini-2.5-flash` (scheduled 16 Oct 2026) were removed from the fallback list. `VisionModels` in `VisionAi.kt` now tries the chosen model (default `gemini-3.8-flash`) and then `gemini-3.5-flash`, at most two, and only on "model not found". Thinking: `thinkingLevel: low` only for `gemini-3.8-flash` (the old `thinkingBudget` is not used for 3.x); `temperature` is no longer sent to 3.x models. One retry on 429 / 5xx, per-call timeout 25 s (fast) / 90 s, readable network errors. The Live voice model is untouched (the one selected in the selector).
- **Background launch.** `scanBlocking` no longer assumes `startActivity` worked: a locked phone (system or Yasmin lock) is reported at once; if the app is not in front after 2.5 s a "Tap to open Camera Vision" notification is shown and the tool waits for the tap (22 s). Nothing bypasses Android's restrictions; the camera is only used by the visible page.
- **Stop and cleanup.** END CAMERA (button or voice) stops the frames, tells the model the camera is off, releases the camera and returns to the home page (or back to the app that was in front when the camera was opened by voice). Leaving the page in any way does the same. The voice assistant itself keeps running as before, except when LIVE VISION started it (then END CAMERA stops it again). Going to the background pauses the stream (Android closes the camera) and the model is told. The session is NOT closed after one answer.
- **Preview.** `PreviewView` uses COMPATIBLE mode (no SurfaceView glitches inside the scrolling column with rounded corners). The screen stays on while the camera streams.
- **Honest status.** The page shows camera state (off / starting / active back|front / paused / error), voice state (off / connecting / connected / muted / sleeping), frames sent, a red "CAMERA ON - MICROPHONE ON" line, and the one-shot TTS stays silent while the Gemini voice is connected (no double speech).
- **Unchanged on purpose:** voice, voice settings, wake / sleep, lock, auto-reply, memory, API key handling (the key is only sent in the request header and never logged), permissions list, Chat page. Manifest and Gradle dependencies are unchanged (ImageAnalysis is part of camera-core 1.3.4).
- Not verified here (no Android SDK / Gradle / network in the authoring environment): compilation, the unit tests, and every runtime behaviour. See TESTING-v2.41-camera.md.

# v2.38 - "Yasmin dekho" no longer hangs: picture first, short wait

- The old `look_camera` waited for the whole AI report (up to 45 s) inside the tool call; a Live session that waits that long for a tool answer drops and Yasmin stayed silent. Now: the Camera Vision page takes the picture FIRST (`VisionBridge.deliverPhoto`), the picture is put straight into the live voice session as an image (`VisionBridge.sendFrame`, set by `AssistantService`), the written description (fast model `gemini-2.5-flash` without thinking time, 700 tokens) is added only if it is ready within 6.5 s, and the tool answers within about 20 s at most. If the camera cannot start the tool says why.
- The Conversation log now shows lines `Camera: look requested`, `Camera: picture taken and sent to Yasmin`, `Camera: no picture (...)` so a problem is visible.
- The captured picture stays visible on the Camera Vision page with the written report under it.

# v2.37 - Yasmin can look by herself + camera / chat buttons on the home screen

- **Voice assistant uses the camera:** new tool `look_camera`. "Yasmin dekho", "ye kya hai", "camera se dekho", "scan karo", "what is this", "read this": Yasmin opens Camera Vision by herself, takes a picture, the AI reads it and she tells him what she sees in her own voice (`quick` short answer or `full` report; front or back camera; his question is answered first). The prompt now tells her she CAN see. Files: `VisionBridge.kt` (tool <-> page), `Tools.kt` (tool + prompt note), `VisionUi.kt` (scan on request), `MainActivity.kt` (opens the page).
- **Home screen:** a round camera button on the left of the mic opens Camera Vision, a round chat button on the right opens Chat (same style as the other round buttons).
- Vision / Chat requests fall back to gemini-2.5-flash and gemini-2.0-flash if the chosen model name is unknown. Pictures in Chat get all their text read and a full report when sent without a question.

# v2.36 - Camera Vision + Chat

- **Camera Vision** (menu > Camera Vision): the in-app camera (CameraX) opens inside the app. "What do you see?" takes a picture and the AI says what is in front of the camera (objects, colours, text, what is happening) and speaks it; AUTO looks again every 8 s; front / back camera; VOICE on / off. "Pick image" analyses any picture from the gallery and gives a full report (overview, objects, people, text, setting, details). Does not name real people from their faces.
- **Chat** (menu > Chat): typed conversation with Yasmin (answers in the language you write; Hinglish by default), with an optional picture ("Photo").
- New files `VisionAi.kt`, `VisionUi.kt`; `MainActivity.kt` only got two pages and two menu entries; `build.gradle.kts` got CameraX and ExifInterface. Both pages use the Gemini key and the "code model" of the API Key page. Nothing else was changed.

# v2.35 - no license lock (full version)

This build has no license system: every feature works at once, no code needed. (`License.kt` is only a stub that always says "unlocked".)

# v2.34 - renamed to YASMIN AI (owner: Mehdi) + auto-reply answers in the sender's language, default Hinglish

- **Rename:** the app is now Yasmin / YASMIN AI and the owner is Mehdi everywhere (code, prompts, docs, scripts). Package and applicationId are `ai.yasmin.assistant` (installs as a NEW app; old settings and data are not carried over). Wake word is "Hey Yasmin" / "Okay Yasmin"; sleep is "Yasmin sleep". **Wake samples trained before this update do not match: record new ones in Preferences > Wake word training.**
- **Auto-reply language (WhatsApp / Messenger):** the reply follows the sender: English -> English, Hindi (Devanagari) -> Hindi, Hinglish (Hindi in English letters) -> Hinglish, Bengali / Banglish -> Bengali script. `BrainLanguage`: new `HINGLISH` style / reply language with its own word list. **Default when the language is unknown: Hinglish** (it was Bengali): first-message introduction, voice-message apology and the model instruction.

# v2.33 - Gemini 3.1 + Leda by default, no second AI page, steadier connection, better listening

- **Defaults:** the default Live model is now Gemini 3.1 Flash Live and the default voice is Leda. Done once on the first start after the update (`Prefs.migrateDefaults`) so it also applies to phones that had another model / voice saved; after that the selector and Settings work as before.
- **No second page:** tapping the mic on the home screen starts the assistant right there (asks for the microphone first if needed). `AiActivity` and `OrbView` are removed. While running, the mic button mutes / unmutes.
- **Reconnect loop fixed:** `setupRejected` treated "closed 1011" (server busy) and "no answer" (slow network) as a rejected setup, so the setup was cut down for the rest of the session (no context compression, no session resumption, other API version). The conversation then filled the model's memory, the model got confused and the connection broke again and again. Now only real "invalid argument / unsupported" answers cut the setup down, and a healthy session (60 s+) goes back to the full setup. A resume token is also dropped when the connection died within 15 s of getting ready (a bad token made it connect and die again in a loop).
- **Deaf-session guard:** Gemini 3.1 sometimes connects and then never hears the caller. If the microphone carried speech for 8 s and the server did not react at all, the session is replaced (max once per 90 s).
- **Understands better:** 3.1 now runs with thinking level LOW (its built-in "minimal" answers fast but often misunderstands); end-of-speech sensitivity is LOW with 800 ms silence (before: HIGH / 550 ms, which cut sentences in the middle and made her answer half of what you said); start padding 300 ms; the 3.1 model note tells her to wait for the whole sentence, never answer to her own echo / noise, and ask to repeat instead of guessing.

# v2.32 - character lip-sync (she looks like she is the one talking)

While the assistant speaks, the anime character's mouth now opens and closes with the loudness of her own voice (quick attack, slower release, small width wobble for a / o / e shapes; the drawn smile line is covered by a soft skin patch and replaced by an open mouth with tongue and teeth) and the head nods a little. When she stops, the mouth goes back to the smile. `AudioIO` now publishes the loudness of the played voice (`AppState.outLevel`, `AppState.outAt`, `AppState.speechLevel()`), separate from the microphone level, so the mouth never moves while YOU talk. Works for both Gemini and ElevenLabs voices (both play through `AudioIO`). `AnimeCharacter(art, modifier, speech = { AppState.speechLevel() })`.

# v2.31 - new 2D anime character on the home page (Live2D style animation)

The home page character is replaced by the new pink-hair cat-ear anime girl (`res/drawable-nodpi/yasmin_character.webp`). New `AnimeCharacter.kt` draws the picture as a deformable mesh (`Canvas.drawBitmapMesh`) and moves it like a 2D anime / VTuber model: eyes blink (two irregular timers), body sways, breathing, both hair tails swing, the raised hand waves "hi" for a few seconds then rests, cat ears twitch, the ahoge wobbles, and while the assistant speaks the head nods with the voice level. Cherry petals fall and sparkles twinkle over the scene. The picture is fitted (never cropped) and its edges melt into the app background. No new libraries or assets. `MainActivity.Home` just calls `AnimeCharacter(art, Modifier.fillMaxSize(), talk = level)`.

# v2.30 - offline wake check (trained on your voice)

Sleeping YASMIN AI now verifies "Hey Yasmin" on the phone, no internet (`WakeDsp.kt` MFCC + dynamic time warping, `WakeTemplates.kt` stores the feature numbers of your trained samples, `WakeTrainer.kt` records a sample). Preferences > Wake word training: record 5 samples, Match Strict / Normal / Relaxed, Reset. A wrong sound is wiped; the status line shows score and limit. Without training the old online check is used (maxOutputTokens raised 24 -> 256: a thinking model used the whole budget and returned empty text, which showed as "not the phrase:").

# v2.29 - developer identity + pet names only in Care mode

Persona (`Tools.kt`): new WHO MADE YOU section for every mode: asked who made / developed her, YASMIN AI answers that Mehdi developed her (Bengali / Hindi / English), never another company; the auto-reply writer says the same for other people. Care mode pet names are now only "babu" and "jaan" (Hindi: jaan), rare (about once in 5-6 replies); Boss and Friend never use them, even if old chat notes show them (recall note says so too). "আরে বাবা" removed from the sound lists.

# v2.28 - wake check: quieter-voice detection + live diagnostics

SleepMicListener: lower start threshold (the old one could be above a normal speaking level), microphone AGC / noise suppression, fallback to the plain MIC source, recording is normalised to a clear level before the check, prompt now maps names that sound like Yasmin/Yasmin to Yasmin. The status line while asleep shows `mic <level> | sounds <n> | <last result>` (HTTP code if the check fails, or what the check heard) so a failing step is visible on screen.

# v2.27 - microphone stays on while asleep + wake-phrase verification

Sleep mode no longer depends on Android SpeechRecognizer restarts (the mic kept closing and the wake phrase was missed). `SleepMicListener` keeps its own 16 kHz recorder open the whole time YASMIN AI sleeps; `UtteranceSegmenter` cuts the stream into short sounds (0.3-3 s). Each one is checked once against the strict list in `WakeCommandParser` (via a short Gemini transcription with the saved API key): right phrase -> YASMIN AI wakes; anything else -> the audio is overwritten with zeros and forgotten. Nothing is stored or logged. Without an API key the old SpeechRecognizer listener is used. WakeWordListener also falls back to the system recogniser when the on-device one has no model for bn-BD.

# v2.26 - login removed, local auto-save memory (20 MB)

Google / Firebase sign-in and the cloud copy are gone (AuthManager, CloudMemory, Firebase + credentials dependencies, FIREBASE_SETUP.md). The app opens straight to the main screen.
Every chat exchange is saved to filesDir/memory right away; the cap is 20 MB (oldest whole file deleted first). On every AI start / restart / mode change the saved chats are read back (recent messages word for word + a digest of older topics, bigger than before) and the Conversation screen shows the last 40 lines again.
Old per-account folders (memory/u_*) from v2.22-2.25 are moved into the single memory folder once. Preferences: the Google account panel became a Memory panel with Clear memory.

# v2.14 - ElevenLabs emotion, pure Bengali/Hindi, faster voice, Gemini 3.1 Flash Live connect fix

ElevenLabs (`ElevenTts.kt`): Eleven v3 now gets audio tags ([happily], [excited], [sad], [angry], [gasps], [curious], [laughs], [sighs]) from the current mood (set_emotion) and from the sentence itself;
older models get mood-based stability / style / speed instead. Language hint (bn / hi) from the script, emoji and markdown removed before speaking.
Speed: the next sentence is fetched while the current one plays (2 parallel requests, ordered playback); short sentences are joined for natural flow; the first words still go out at the first comma.
Persona (`Tools.kt`): new EMOTIONS, PURE BENGALI AND HINDI, UNDERSTANDING HIM and SPEED sections (native script, native grammar, human sounds, quick first reaction).
Hearing (`GeminiLive.kt`, `AudioIO.kt`): start-of-speech HIGH, 200 ms prefix padding, 550 ms end silence, automatic gain control on the mic.
Gemini 3.1 Flash Live Preview: if the server rejects the setup message, the app retries with a simpler setup (level 1: no VAD / compression; level 2: no resumption / thinking and the other API version) before giving up; the exact server reason is shown in the log.

# v2.13 - settings visible everywhere + lock gate loop fix

Reply Delay / Reply Limit selectors (`ReplySettingsUi.kt`, one shared composable) now also appear at the TOP of Preferences and Additional Settings (and still on the Automation screen).
Lock: the accessibility lock gate no longer acts while the lock screen is in front (fingerprint prompt was treated as an open shade -> closed -> lock relaunched in a loop),
is rate limited to one action per 1.5 s (the HOME press caused a new gate event -> cover + HOME loop), and never draws the opaque cover when the lock screen cannot open
(no "display over other apps"). versionName 2.13 so the installed build can be recognised.

# v2.12 - Reply Delay / Reply Limit settings + lock state fixes

Reply Delay (Instant 0 s / Fast 15 s / Normal 30 s, default Normal) and Reply Limit (Unlimited / 20 / 50, default 20) are now user settings
(Automation screen > Automation settings). Both are stored in the existing preferences and loaded in `YasminApp.onCreate`, so they survive
an app restart and a reboot. Unknown or damaged stored values fall back to the defaults.

New file: `ReplySettings.kt`.
Changed (targeted): `OwnerWatch.kt` (`OwnerLimits.waitMs()` replaces the fixed 30 s in `admit`; the constant stays as the default),
`AutoReply.kt` / `NotificationReply.kt` (`AutoReplyLimits.max()` replaces `MAX_REPLIES` in the four limit checks; Unlimited = Int.MAX_VALUE so
no check can trigger), `AutomationPanel.kt` + `MainActivity.kt` (selectors and texts), `YasminApp.kt` (init).
Behaviour notes: a wait that already started keeps its old deadline; a conversation that already got its closing message stays closed even if
the limit is raised (unchanged rule). Instant = no owner wait; the other owner checks (notification still present, listener connected) still run.

Lock fixes (`LockStateManager.kt`, `YasminLockManager.kt`, `LockScreenActivity.kt`):
 1. One writer: the persisted "must lock" bit and the live state are written together in `commit()`, synchronously (was apply(): async).
 2. Process restarted while the screen was off (screen-off broadcast missed while the process was dead) no longer comes back "open".
 3. `unlocked()` is refused while the screen is off: a late/duplicate biometric callback can no longer open the lock.
 4. Reboot detection falls back to the uptime clock when `boot_count` cannot be read.
 5. `YasminLockManager.show()` re-checks the lock state at launch time and is synchronized: no lock screen re-opening after a valid unlock, no duplicates.
 6. `LockScreenActivity.unlock()` ignores duplicate callbacks and only finishes when the manager really opened the lock.
Unchanged: credentials, lockout, biometric, accessibility gate, device admin.

# v2.9.3 - Custom Lock (YASMIN AI's own software lock)

Adds an optional lock for YASMIN AI. Off by default. Nothing existing was removed or redesigned.

New files: `LockStateManager.kt` (UNLOCKED / LOCKED / SCREEN_OFF), `LockCredentialManager.kt` (PBKDF2 hash + Android Keystore AES-GCM, attempts, lockout),
`PatternLockView.kt`, `BiometricUnlockManager.kt` (system BiometricPrompt only), `YasminLockManager.kt` (screen events, show / guard),
`LockScreenActivity.kt`, `YasminLockUi.kt` (settings page + shared credential input), `YasminApp.kt` (Application class that starts the lock listener).
Changed: `AndroidManifest.xml` (Application name, LockScreenActivity, USE_BIOMETRIC / USE_FINGERPRINT), `MainActivity.kt` (Page.LOCK, one panel in Additional Settings, `guard` in onResume), `AiActivity.kt` (`guard` in onResume).
Limits: this is a software lock inside the app, not the Android Keyguard. No service is started, stopped or restarted by the lock.

# v2.9.2 - Coding Agents: Error Inspection (Phase 2A-1)

Adds a read-only inspection layer on top of the Phase 1A/1B build, test and error-parser results. Nothing else changed (Main Brain, voice, WhatsApp, Messenger, phone control, accessibility, root, API-key stores, Uploaded Sources, ZIP import/export, keyboard/IME untouched).
No automatic fixing, repair loop, skills, planner, research, visual AI or rollback. No project source file is ever modified by this phase.

## New files
- `CodingInspectionModels.kt`: plain data models. `InspectionContext` (one per finished run: project, run, errors total, one `ErrorInspection` per error, project-wide `ManifestInfo` / `GradleInfo` when needed, notes; `toJson()`, `toAgentText()`),
  `ErrorInspection`, `CodeWindow`, `ImportEntry`, `SymbolLookup`, `ResourceRef`, `ManifestInfo`, `GradleInfo` and small enums.
- `CodingInspector.kt`: `CodingInspector.inspectLatest(ctx, projectId)`, `inspect(ctx, ExecutionResult)` and the pure `inspectWith(result, InspectionSource, secret, now)`; `InspectionSource` (+ `DiskInspectionSource` on top of `CodingFiles`).
  For each `ParsedError` and `TestFailure` (at most 25, errors that name a file first): (1) the affected file, resolved only when exact or unique (bare name / path suffix / test class name), absolute or escaping paths are NOT opened;
  (2) line / column as printed, the identifier at that column; (3) a numbered code window (12 lines before, 8 after) and the enclosing declarations; (4) every import of the file resolved to PROJECT / PROJECT_UNRESOLVED / PLATFORM / LIBRARY (with a dependency hint);
  (5) declarations of the names the error mentions in the project's Kotlin/Java sources; (6) resources named by the error or the line and where `res/` defines them (values entries, resource files, `@+id`);
  (7) AndroidManifest: package, SDK levels, permissions, application attributes, components with exported / intent-filter / the project class that implements them; (8) Gradle: build files, namespace, SDK levels, plugins, dependencies, repositories, language level,
  settings, wrapper version, selected `gradle.properties` keys, version catalog; dependency coordinates named in the message are looked up in those files.
  Handles Kotlin, Java, Gradle, dependency, resource, manifest, duplicate class / resource, unresolved reference, type mismatch, package / import and SDK / API-level errors.

## Changed files
- none (additive only). Nothing calls the inspector yet: like the Phase 1 operations it is an API for the next phase (error analysis).

## Safety
- Read-only: reads go through `CodingFiles` (path cleaning, canonical-path check, binary / size limits) from the real project folder; no write, no process, no network.
- Code windows, declarations and Gradle lines are redacted with the same rules as build output (`BuildRedactor`, `CodingExport.scrub`, plus `*Password = "..."` values). `local.properties` is never read.
- Findings are facts read from the project (for example "declared at app/build.gradle.kts:25"), never a diagnosis.

## Known limits
- NOT COMPILED, NOT RUN: no Kotlin compiler, Android SDK or network here. Checked: bracket balance (Kotlin-aware), imports and symbols used from existing files, and every regular expression (the real literals, run with Java's regex engine against sample Kotlin, Java, import, resource, manifest, Gradle and message text: 48 checks).
  The control flow was checked by reading only. Please build in Termux and send any `e:` lines.
- The declaration index is line-based (regular expressions), not a compiler: no generics, inheritance, overloads or imports of imports; enum entries are not indexed.
- Files are read as they are on disk now. A file changed after the build finished is flagged (`changedSinceBuild`).

---

# v2.9.1 - Coding Agents: Test Execution + Error Parser (Phase 1B)

Extends the Phase 1A build core. Nothing else changed (Main Brain, voice, WhatsApp, Messenger, phone control, accessibility, root, API-key stores, Uploaded Sources, ZIP, keyboard untouched).
No repair loop, planner, skills, research, visual AI or rollback.

## New files
- `CodingExecutionModels.kt`: `ExecutionPhase` (Preparing, Running, Analyzing, Success, Failed), `ExecutionKind`, `ErrorCategory`, `ParsedError` (category, message, file, line, column, code), `TestFailure`, `TestSummary`.
- `CodingErrorParser.kt`: `ErrorParser`, a streaming parser: Kotlin (new + old `e:` formats), javac (column from the caret line, symbol/location detail), AAPT/resource, manifest merger, duplicate class, duplicate resource,
  unresolved reference, missing symbol, compilation, dependency, Gradle "What went wrong" blocks, SDK/toolchain/out-of-memory, lint text reports, failed unit tests, Gradle's test summary line.
  A file/line/column is set only when the output printed it; unmatched lines are ignored.
- `CodingExecution.kt`: `ExecutionResult` (+ `toJson()`, `toAgentText()`, `errorsByFile()`), `CodingExecution.from(BuildResult)` / `result(...)`: the structured result a future repair engine reads.
- `CodingTest.kt`: `runTest` / `runTestTask`, `readTestOutput`, `getExitCode` (+ `startTest*`, `cancelTest`, `isRunning`, `getResult`), `TestKind`, `CodingTestPolicy`. Only `test` and `test<Variant>UnitTest` (optional `:module:` prefix);
  all other safety is the Phase 1A policy (task allowlist, canonical project folder, argument list, clean environment, redaction, one run at a time, background threads).

## Changed files (small, additive)
- `CodingBuild.kt`: the parser is fed every redacted line; stages Preparing -> Running -> Analyzing -> Success/Failed are published; `awaitBuild`, `startRejected`, `runningTask` added; the cached last result also keeps parsed errors / test failures / summary.
- `CodingBuildModels.kt`: `BuildResult` and `BuildProgress` got defaulted fields (`parsedErrors`, `testFailures`, `testSummary`, `stage`). Existing callers are unchanged.
- `CodingBuildUi.kt`: the status line shows Preparing / Testing / Analyzing and "Tests succeeded / failed".

## Known limits
- NOT COMPILED, NOT RUN: no Kotlin compiler, Android SDK or network here. Checked: bracket balance, imports, symbols used from existing files, and the parser's regular expressions (the real literals, run with Java's regex engine against sample Kotlin, javac, AAPT, manifest, lint, duplicate and test output). The multi-line logic is checked by reading only.
- Nothing starts a test run yet (no button, no agent tool): the operations are an API for the next phase.
- `BuildResult.issues` (Phase 1A analyzer) and `parsedErrors` (Phase 1B parser) both exist; `ExecutionResult.errors` uses the parser only.
- Test failure file names are the bare names Gradle prints; stack-trace frames are not parsed (the first frame is often a library frame).
- A unit test is project code and runs with the app's permissions, like a Gradle script. The restrictions apply to how it is started.

---

# v2.9 - Coding Agents: Build Execution Core (Phase 1A)

Adds a controlled way to run a Gradle task in the selected Coding Agent project, asynchronously, and report what really happened. Nothing else changed:
Main Brain, its key, voice, WhatsApp, Messenger, phone control, accessibility, screen sharing, root, the two API-key stores, Uploaded Sources, ZIP import/export,
keyboard/IME, the 503 retry and the existing agent tools are untouched.

## New files
- `CodingBuildModels.kt`: `BuildResult` (task, status, exit code, duration, redacted stdout/stderr, errors, notes), `BuildStatus`, `BuildIssue`, `BuildKind`, `BuildOutput`, `BuildProgress`.
- `CodingBuildPolicy.kt`: task allowlist (`assemble*`, `bundle*`, `build`, `clean`, `check`, `lint*` except `lintFix*`, `test*UnitTest`, `compile*Kotlin/Sources`, optional `:module:` prefix;
  no options, paths, spaces, install/publish tasks), canonical project-folder check, Java/Gradle lookup, build-script warning scan, optional settings (`CodingBuildConfig`, no screen yet).
- `CodingBuildOutput.kt`: `BuildRedactor` (API key, tokens, private keys, passwords, bearer headers, URL credentials, absolute project path -> `<project>`; reuses `CodingExport.scrub`) and
  `BuildOutputAnalyzer` (Kotlin, javac, resources/AAPT, Gradle failure blocks, dependency/SDK/toolchain/out-of-memory, lint).
- `CodingBuild.kt`: the four operations `runBuild`, `runGradleTask`, `readBuildOutput`, `getExitCode` (+ `startBuild`/`startGradleTask`, `cancelBuild`, `isRunning`, `getBuildResult`).
  Background pool, process started from an argument list with the project folder as working directory and a clean environment, stdout/stderr read on their own threads, timeout (30 min),
  one build at a time. Success = the process ended by itself with exit code 0. The latest result per project is saved in the app cache (redacted).
- `CodingBuildUi.kt`: one status line (task, running clock, result). No buttons.

## Changed files
- `CodingAgentsActivity.kt`: one line, `item { BuildStatusLine(project.id) }`, above the error panel.

## Known limits
- NOT COMPILED, NOT RUN: no Kotlin compiler, Android SDK or network was available. Checked: bracket balance, imports, the symbols used from existing files, and the regular expressions
  (run against sample output with Java's regex engine). Please build in Termux and send any `e:` lines.
- Gradle needs a JDK 17 and Gradle (or the project's own gradlew + wrapper jar) that THIS APP is allowed to run. Termux's Java/Gradle live in Termux's private folder and cannot be run by
  this app, and Termux cannot read this app's private project folder. Without a usable toolchain a build is refused with a clear message (status "not started"), not faked.
- Nothing starts a build yet (no button, the agent has no build tool). The agent tools and a trigger belong to a later step.
- Gradle build scripts and a project's gradlew are code. The restrictions apply to how a build is started, not to what a script does once it runs.

---

# v2.8.1 - renamed to YASMIN AI (name change only)

The old name is gone from the whole project: app label, screens, voice/persona prompts, Coding Agent prompts, automation text, notifications, docs, scripts.
- Display name: "YASMIN AI". File and style names use "YASMIN-AI" / "Yasmin" where a space is not allowed (APK `YASMIN-AI.apk`, theme `Theme.Yasmin`, export notes `YASMIN-AI-EXPORT.md`).
- Code: package and applicationId are now `ai.yasmin.assistant` (folder moved), classes `Yasmin*` (YasminUi, YasminAccessibilityService, YasminNotificationService, YasminBackgroundService ...), preference and key-store names `yasmin_*`, the chat-memory label "YASMIN AI:".
- IMPORTANT: a new applicationId is a NEW app for Android. It installs next to the old one; saved API keys, settings, chats, projects and the Accessibility / Notification-access / overlay permissions of the old app are NOT carried over. Enter the keys again and grant the permissions again, then uninstall the old app.
- No behaviour changes. NOT COMPILED: mechanical rename, please build and send any `e:` lines.

---

# v2.8 - Coding Agents: tool-based agent core (Phase 1)

The Coding Agent now works through Gemini function calling on its project instead of receiving a dump of files and answering with file blocks.
MODEL -> tool call -> CodingTools.execute (on CodingWorkspace) -> result -> MODEL -> ... -> final answer. Gemini retry, reasoning config, Uploaded Sources and the
keyboard fix are untouched.

## New files
- `CodingWorkspace.kt`: the per-task workspace and security boundary. Reads go through the existing `CodingFiles` (path cleaning, canonical-path check, binary/size limits). The agent
  NEVER writes to the project folder: its edits are staged in the existing pending-change list (`PendingChange`), so the review-and-apply flow stays the only way a file changes.
  Reads see staged edits. Tracks CREATED / MODIFIED / DELETED / RENAMED, line diffs vs. disk, in-task checkpoints (snapshot of the staged layer), a cached project index.
- `CodingIndex.kt`: file types, important Android/Gradle files (settings/build gradle(.kts), gradle.properties, AndroidManifest.xml, libs.versions.toml, ProGuard/R8), source and resource
  folders, recent files, package + top-level names of Kotlin/Java files, a short tree, import -> project-file resolution.
- `CodingTools.kt`: 19 tools: getProjectTree, analyzeProject, listFiles, inspectDirectory, readFile (line ranges), searchFiles (plain text), createFile, updateFile (full content or exact
  find/replace edits), deleteFile, renameFile, createDirectory, inspectGradle, inspectManifest, inspectResources, inspectDependencies, getPendingChanges, getDiff, createCheckpoint,
  restoreCheckpoint. Arguments are validated, results capped, unknown/disabled tools refused. A checkpoint is made automatically before the first write of a task. EXPLAIN mode gets read tools only.
- `CodingAgentState.kt`: `AgentPhase` (ANALYZING, PLANNING, INSPECTING, EDITING, WAITING_FOR_TOOL, VERIFYING, COMPLETED, FAILED, CANCELLED), `AgentTask` (task/project/agent/model, phase, iteration,
  tool calls, files inspected/changed, checkpoint, errors, final status; no reasoning, no file contents, no key), saved after every step; an unfinished task loads as FAILED (interrupted).
  `CodingAgentConfig`: tools on/off, thinking level, max iterations (24), tool-result budget (350k chars), output tokens, disabled tools (defaults only, no new UI).
- `CodingAgentLoop.kt`: the loop. Limits: iterations, context budget, repeated identical call (3x), Stop. At a limit the model gets one last turn without tools to summarise.

## Changed files
- `GeminiChatApi.kt`: the HTTP/retry code moved into one private `execute()` shared by `chat()` (behaviour unchanged) and the new `agentTurn()` (tools, returns Calls / Final / Fail). The model's own content
  is sent back unchanged (thought signatures). `thinkingConfig` takes the level (chat() still uses "high").
- `CodingProtocol.kt`: new `agentSystemPrompt()`. The old prompt and parser are kept (used when tools are off and as a fallback if a model still writes file blocks).
- `CodingEngine.kt`: `agentTasks` flow; `runAgentTask()`; staged edits are merged into the pending list per touched path only (what the user skips meanwhile stays skipped).
- `CodingAgentsActivity.kt`: the "is thinking..." line shows the phase text ("Inspecting relevant files...", "Updating files...") and the changed-file count.

## Not done on purpose
No build/compile/run/fix loop (Phase 2). No chain-of-thought is requested or shown.

## Known limits
- Checkpoints cover the staged layer only (real files never change before Apply); they live for one task and are not persisted across app restarts.
- The task state is saved, but nothing resumes an interrupted task.
- Tools on/off, thinking level and limits have no settings screen yet.
- Applying changes is unchanged, so a half-finished agent task leaves its staged changes in the normal review card.

NOT COMPILED OR RUN: no Kotlin compiler, Android SDK or network here. Only a bracket-balance check was done. Please build in Termux and send any `e:` lines.

---

# v2.7.1 - Coding Agents only: 503 retry, deeper reasoning, Uploaded Sources, keyboard fix

- `GeminiChatApi.kt`: transient errors (HTTP 408/429/500/502/503/504 and connection errors, not read timeouts) are retried up to 3 times inside `chat()` with backoff
  about 1 s / 2 s / 4 s + jitter, Retry-After respected (waits over 30 s are not retried). The engine sees ONE result, so no message is duplicated and the final
  answer is saved once. Permanent errors are returned at once; the Retry button is unchanged. Thinking: `thinkingLevel: high` for Gemini 3 models (not Flash-Lite /
  custom names); thoughts are never requested or shown; if a model rejects the setting with HTTP 400 the same request is sent once without it.
- `CodingProtocol.kt`: the system prompt now has a "HOW TO WORK" section (silent analysis, no invented files/APIs, no claims of building/testing).
- NEW `CodingUploads.kt` + helpers in `CodingFiles.kt`: every ZIP / file import is recorded (name, type, time, files + SHA-256) outside the project folder. "Uploaded Sources"
  chip in the Files tab lists uploads; "Remove Upload" asks for confirmation and really deletes only files the upload still owns and that are unchanged since import.
  Edited / AI-rewritten / later-imported files are kept, hand-made and AI-generated files are never touched, empty folders are pruned, stale pending changes for deleted
  files are dropped. Chats are untouched. Imports made before this version have no record.
- `CodingAgentsActivity.kt`: the bottom bar (attachments, input, Send) is outside the weighted area so it can never be pushed off screen; rarely used rows fold away while typing with
  the keyboard open. Uses IME insets (`imePadding`, existing `adjustResize`), no hardcoded heights.
- Not touched: Main Brain, its key, WhatsApp, Messenger, phone control, automation, voice, export, pending-change system, the two API-key stores.

NOT COMPILED OR RUN: this environment had no Kotlin compiler, Android SDK or network. Please build and send any `e:` lines.

---

# v2.1 - the Main Brain writes every automatic reply (WhatsApp + Messenger), silently

## What changed
- NEW `MainBrainModel.kt`: one short, silent text turn on the Main Brain's OWN model = the model selected in the model menu (`Prefs.liveModel`, read on every
  reply, nothing hardcoded; 3.8 Live, 3.8 Thinking, 3.1 Flash Live or a custom Live name), through the same `GeminiLive` client and the same API key as the voice
  assistant. The audio the Live model produces is dropped (never given to `AudioIO`); the reply text is the model's output transcription. It does not touch
  `AppState`, does not start `AssistantService`, needs no microphone and declares no tools. It is an online model: no internet / key = an error code, never an invented reply.
- The old separate reply client `GeminiTextAi` (fixed to the code model `gemini-3.8-flash`) is removed. `AiResponseGenerator` is now `MainBrainReplyWriter`
  (file renamed), owned by the Main Brain. Prompt rules, privacy guard, cleaning and limits are unchanged.
- Agents cannot generate: `AgentContext.ai` is gone; `AgentContext.responses` is a `BrainReplyPort`, i.e. the Main Brain. A Master Agent sends the Brain its app facts
  (placeholder detection, app notes, history) and receives the Brain's text, which it queues for sending through the existing hook, queue and notification Reply sender.
- First reply: the Brain writes the introduction with the default meaning "Raj is currently unavailable. I am Raj's AI assistant. You can tell me what you need, and I will try to help."
  in the sender's language/script (it no longer also answers the message in the same reply).
- Later replies: written fresh by the Brain from the message and the chat. New memory-only `BrainReplyMemory` keeps the last replies YASMIN AI itself sent in each conversation
  (the chat store only holds what other people wrote) and shows them to the Brain with "do not repeat their wording". Recorded when a send is confirmed, or unverified (it may have gone out).
- Silent: `AssistantService.announce` no longer reads a message out loud while automatic replies are active for that app. This is a behaviour change: with the
  Automation switches on (default), WhatsApp / Messenger message notices are not spoken.
- `GeminiLive.kt`: a session with no tools omits the `tools` field (voice sessions are unchanged).
- Version 2.1 (versionCode 9).

## Unchanged on purpose (already did what the task asks)
Notification detection, 30 s owner wait, owner-reply cancellation, task queue / single lane, 3-5 s pause after a confirmed send, duplicate protection and persistence
(task database), per-conversation per-app reply counter (20, counted from confirmed / unverified sends only), closing message, failed sends not counted, bounded retries,
send verification by the app's own notification line, root integration, UI, permissions.

## Not verified
Nothing in this version was compiled or run: the authoring environment had no Kotlin compiler, Android SDK, network or phone. No test has been run, so no feature is claimed to work.
Please build it and send any `e:` lines. Things only the phone can show:
1. Whether the selected Live model returns an output transcription for a text-in turn (the reply text depends on it). If not, the Auto reply log shows `ai_blocked_or_empty`.
2. Whether Extended Thinking ("3.8 Thinking") ends the turn as expected (the code waits for the server's IDLE).
3. Whether the notification carries a Reply action and the app re-posts it (unchanged from v2.0).
Test list to run by hand: detection, 30 s wait, owner reply cancels, Brain reply generated, right agent chosen, no sound / no screen, send verified, 20 limit + closing, queue / duplicates, error handling. Use the Auto reply log on the Automation screen to see each step.

---

# v2.0 - automatic replies actually run (WhatsApp + Messenger)

Problem reported: messages were detected and read out loud, but 30 seconds later no automatic reply was sent.

## What was wrong (found by reading the whole path, notification -> reply)
The notification listener and the message announcement (AssistantService) are NOT part of the reply pipeline. The pipeline
(MessageProcessor -> MainBrain -> OwnerWatch 30 s -> Master Agent -> AiResponseGenerator -> TaskManager -> NotificationReplySender) only runs
inside `YasminBackgroundService`, and only while the master switch is on. Both were OFF by default and were separate opt-in switches in
two different screens, while announcing messages needed neither. So "detected and spoken, never answered" was the normal result of a fresh install.
Also found:
1. The AI rules said "do not say that you are an AI", the opposite of the required "Raj's AI assistant" introduction.
2. `Inbox` (the announcer) treated a line written under the owner's own display name, or YASMIN AI's own sent text, as an incoming message, so a
   sent reply could be read out loud.
3. No way to see which step stopped a reply: every refusal only went to logcat.

## Changes
- `BackgroundService.kt`: `isEnabled` default ON (a stored OFF still wins). A refused start is written to the log.
- `AutoReply.kt` / `Prefs.kt`: master switch default ON (`Prefs.AUTO_REPLY_DEFAULT`); a stored OFF (Automation screen) still wins.
- `AssistantService.kt`, `YasminNotificationService.kt`: bring the background service up when the voice service starts and when the listener connects.
- NEW `AutoReplyLog.kt` + a log panel at the bottom of the Automation screen (`AutomationPanel.kt`): time, step, short code. Red = the step that stopped or
  failed a reply. No message text, no names, no reply text. Hooks: `MainBrain.kt`, `TaskManager.kt`, `AutoReply.kt`.
- `AiResponseGenerator.kt`, `WhatsAppActions.kt`, `MessengerActions.kt`, `WhatsAppMasterAgent.kt`, `MessengerMasterAgent.kt`: YASMIN AI answers as "Raj's AI assistant";
  the FIRST reply to a person starts with a short introduction (decided from the stored reply counter), later replies do not repeat it.
- `YasminNotificationService.kt` + `OwnSends`: owner-name lines and YASMIN AI's own sent text are never announced (no voice when replying).

## Unchanged on purpose
Notification parsing, root permission / root gateway, both Master Agents, Control Panel, owner check, lifecycle, 20-reply limit + closing message,
one send per message, one queue. Sending is still only the notification's own Reply action.

## Not verified (no phone and no compiler were available)
Written and read through, not built or run. Build it and send any `e:` lines. Whether WhatsApp / Messenger notifications on this phone carry a Reply
action, and whether the owner check calls a chat "uncertain", can only be seen on the phone: the new Auto reply log shows it.

---

# Root connected to the Main Brain and the Automation panel (no version bump)

- No new root system. `Root.kt` (`su`, blocklist, read-only check), `Device.setupSelf` and the voice tools `root_status` / `run_root` / `setup_self` are unchanged in behaviour. New file `RootAccess.kt` is a thin gateway over them: no command string of its own, no permission, no service, no process.
- ONE shared root status (`RootAccess.status`). `Root.available()` reports every real `su` answer to it (one added line), whoever asked. `Automation.root` is now that same value (it used to be a separate copy that only the panel button updated). The panel and the Main Brain only READ it, so neither causes another root manager prompt.
- Closed list of actions (`RootAction`). Today exactly one: `SELF_SETUP` = the existing `Device.setupSelf` (notification access, background use, accessibility, declared permissions). It takes no parameter: nothing from a message or from AI output can reach a shell. `when` over the enum is exhaustive, so a new action does not compile until it is wired to existing code.
- Pipeline: `MainBrain.askRootHelpIfNeeded` -> `RootAccess.request(MAIN_BRAIN, SELF_SETUP)` -> checks: caller allowed -> confirmation only from the panel -> root already known AVAILABLE (UNAVAILABLE = safe stop, NOT_CHECKED = stop, never probes) -> recorded as pending -> Automation panel shows "Allow / Not now" -> `Automation.approveRootAction` -> `RootAccess.request(CONTROL_PANEL, ..., ownerConfirmed = true)` -> runs once at a time on the IO dispatcher.
- The brain asks only when Notification access is OFF while the master automation switch is ON, and only when root is already known to be available. It never asks because of message content.
- WhatsApp and Messenger agents: no root access (`AgentContext` has no handle, `RootCaller` has no agent entry; documented in the `AppAgent` rules). No implemented feature of either agent needs root.
- Panel: root row shows the shared status; "Check root" is still the only owner-driven forced check. `PhoneControl.status` now reuses a known root instead of forcing `su` again.
- Files: new `RootAccess.kt`. Changed: `Root.kt`, `MainBrain.kt`, `AutomationControl.kt`, `AutomationPanel.kt`, `Agents.kt` (comment only), `PhoneControl.kt` (one call). No manifest change, no new permission, no library.
- Not compiled in the authoring environment (no Kotlin compiler or Android SDK there): please build it and report any `e:` line.

# Automation Settings and Control Panel (step 13, no version bump)

- New screen **Automation** (side menu, and a row on the home screen). Everything on it is read from the real state: `BackgroundState` (service), `MessageAccess` (notification access: OFF / allowed but not connected / connected), `BrainState` (Main Brain, registered agents), the task database (pending / running / uncertain sends) and the message store (private chats). A value that cannot be read is shown as a problem, never as OK. The screen re-reads the system every 3 s while it is visible and the app is in the foreground, and at once when the service, the permission or the agents change. No message text is shown, only chat names.
- Master switch, WhatsApp switch, Messenger switch: the same stored values the engine already used (`Prefs.AUTO_REPLY`, `WA_AUTO`, `MSGR_AUTO`), so they survive restarts. New: `Prefs.AUTO_PAUSED`. `AutoReplySettings` (AutoReply.kt) is still the ONE place that answers "may YASMIN AI send now?" (`blockReason` / `enabled`: master on, not paused, app on). Every check in the pipeline uses it.
- Rechecked at: task creation (`MainBrain.decide`, new: no task, no AI call while automation is off for that app), the agents' hooks, `AutoReply.consider` / `preflight`, the sender's `precheck`, the sender's `send`, and (new) once more immediately before the reply action is fired.
- Turning a switch off writes the value first (everything sees it at once), then cancels the waiting tasks (`TaskManager.cancelForAutomation`): master = both apps, app switch = that app only. Reply drafting in progress is cancelled too. A send that is already running is NOT cancelled (a message may already be on its way; cancelling would make it unverified): the sender's own checks stop it before the attempt, and one already fired finishes and is verified as usual.
- Owner control: **Pause** (value kept, switches untouched, waiting tasks cancelled, nothing scheduled or sent), **Resume** (new messages only; cancelled tasks are not revived, so no stale reply goes out), **Stop all** (master off, pause cleared, waiting tasks and drafting cancelled; asks for a second tap). Turning the master switch on or off also ends a pause.
- Manual owner replies still pause that conversation (step 9/11, unchanged); the panel shows it as "Paused: you replied".
- Per conversation (WhatsApp and Messenger listed separately, private chats only, newest 20 per app, held ones first): **Pause**, **Resume**, **Stop**, **Allow again**. They use the existing conversation lifecycle (`conversation_lifecycle`): pause = PAUSED, stop = COMPLETED with reason `stopped_by_owner` (nothing is deleted, unlike Block). Waiting tasks of that conversation are cancelled. A state is only ever raised by these buttons (a blocked conversation is not lowered to paused). Resume only lifts PAUSED; Allow again only lifts a stop Raj set himself; a conversation closed by the 20-reply limit stays closed. Each row shows replies used (n/20).
- Settings shown: owner wait 30 s and 20 replies per conversation are read-only (fixed engine rules, `OwnerLimits.WAIT_MS`, `AutoReplyLimits.MAX_REPLIES`). The pause between confirmed sends (3 / 4 / 5 s) is the existing `Prefs.SEND_DELAY_MS`, the same setting as in Additional Settings.
- Permissions: buttons for Android's notification-access screen, "Check again", App info (with the Android 13+ "restricted settings" steps) and "Start background service". Access is shown as enabled only when Android reports it and the listener is connected.
- Root: shown as "Not checked" until Raj presses "Check root" (the root manager may prompt). Messaging automation does not use root.
- "Active conversations" = private chats with a message in the last 24 h that are not paused / stopped / closed. "Pending tasks" = waiting tasks in the queue (reply drafting and sending).
- Additional Settings: the old three switches in "Automatic replies" were removed (one set of switches only) and replaced by a link to the Automation screen; its status line and rules text stay.
- Behaviour change to know about: with automation off / paused / the app's switch off, new messages no longer become tasks, so nothing is drafted and no message text is sent to Gemini (before, drafting still ran with the master switch off).
- Files: new `AutomationControl.kt`, `AutomationPanel.kt`. Changed: `Prefs.kt`, `AutoReply.kt` (`AutoReplySettings`), `NotificationReply.kt` (reason codes, last recheck), `Tasks.kt` (`cancelPendingOfSources`, `pendingBySource`, `lifecycleRows`, `lifecycleResumeOne`, `LifecycleRow`), `TaskManager.kt` (`cancelForAutomation`, `cancelWaitingOfConversation`), `MainBrain.kt` (task gate), `MainActivity.kt` (page, drawer, home row, foreground flag). No manifest change, no permission, no library, no new service, no second engine, no database version change.
- Not compiled in the authoring environment (no Android SDK there): please build it and report any compiler message.

# Automatic Reply System (step 10, no version bump)

- Off by default. Settings > Additional Settings > "Automatic replies": a master switch plus one switch per app. Nothing is ever sent while the master switch is off; the sender checks the switches again right before it sends, and waiting send tasks then end as `auto_reply_off`.
- Flow: new message -> owner check (30 s wait, unchanged) -> `INCOMING_MESSAGE` task -> `AutoReply.preflight` (person already at the limit? then no AI call, see below) -> Master Agent drafts with the shared `AiResponseGenerator` -> the agent's automation hook (`WhatsAppAutoReply` / `MessengerAutoReply`, the sockets of steps 5 and 6) calls `AutoReply.consider` -> `TaskManager.submitSend` -> `SEND_REPLY` task in the SAME queue and single lane (one send at a time for both apps, 3-5 s pause after a CONFIRMED send, strict order per conversation) -> owner gate again -> `precheck` -> `markAttempting` -> `send`.
- Sending method (the only one): the chat notification's own Reply action (RemoteInput + PendingIntent), through `NotificationReplySender` (`NotificationReply.kt`), one sender per app (`WhatsAppReplySender`, `MessengerReplySender`); each reads the draft only from its own app's agent. The notification is used only if it belongs to the task's own conversation (same package, chat name resolves to the task's conversation id). No Reply action, notification gone, listener off or reconnected during the wait, plain notification without message list: nothing is sent (`no_reply_action`, `notification_gone`, `listener_off`, `listener_gap`, ...).
- Not implemented on purpose: an Accessibility-Service fallback. It would have to open the chat on Raj's screen and type into another app, cannot prove delivery any better, looks to the owner check like Raj opening the chat, and is restricted by Android (restricted settings on 13+, background launch limits). Chats without a Reply button are therefore not answered.
- Verification: a send is CONFIRMED only when the app re-posts the chat's notification with a NEW own line whose text hash equals the reply's hash and whose time is after the send began (checked every 0.5 s for 15 s). Otherwise it ends UNCERTAIN (task FAILED, phase UNCERTAIN): never retried, never counted as confirmed. A cancelled reply action (`CanceledException`) is a certain NotSent.
- Owner control: before the attempt (`precheck`) and again right before the PendingIntent is fired, the sender requires `OwnerWatch.eligibility == ELIGIBLE`, an unbroken listener, and re-reads the conversation's notifications: any own line newer than the wait that is not YASMIN AI's own, or any unclear line, stops the send. If Raj replies while a task waits, the existing cancellation (step 9) ends it. If the owner's status cannot be determined, nothing is sent.
- YASMIN AI's own lines vs Raj's: `OwnerReplyDetector` now records a hash of each own line's text (`OwnLine`, no text). `OwnSends` (memory only) remembers the hash and start time of sends in progress; `OwnerWatch.handleSignal` drops own lines that match, so YASMIN AI's reply never counts as a manual reply. While a send runs (and for 15 s after), the APP removing or re-posting the notification (`REASON_APP_CANCEL`, `REASON_APP_CANCEL_ALL`) is not read as "Raj opened the chat"; a click or a dismissal by the user still is. After a restart this memory is empty, which can only make the next wait stricter.
- Who is answered: private chats only (groups and chats of unknown kind never), only after the other person wrote, only the person's newest message (an older one ends `superseded`). YASMIN AI never starts a conversation: a send needs a stored incoming message.
- Limit: 20 automatic replies per person per app. The counter is the table `reply_counter` in the task database (version 4), keyed by a hash of (app package, normalised chat name), so WhatsApp and WhatsApp Business, Messenger and Messenger Lite, and every person have separate counters, and deleting the message history does not reset them. It is changed in the SAME transaction that finishes the send task (`TaskStore.finish`), so every end path (normal, cancel, timeout, recovery after a restart) counts exactly once and a crash cannot separate "sent" from the count. Counted: CONFIRMED sends. A send that ended UNCERTAIN may have reached the person, so it also counts against the limit (the limit can therefore only be reached a little early, never exceeded). Checked at decision time, in the Main Brain before the AI call, and again in the sender's precheck and send, from disk.
- Closing message: when the limit is reached, the person's NEXT incoming message gets "Sorry, my time to reply has ended. Please message again later when Raj is back." (no AI call; sent through the same queue and checks; not one of the 20). After it (confirmed or uncertain) the conversation is never answered again. Never queued twice (`hasOpenClosing`, `closing` flag).
- Duplicates: one `SEND_REPLY` task per (conversation, message) by database key (also after restarts and repeated hooks); `ATTEMPTING` is saved before the sender is called; an interrupted attempt is UNCERTAIN and never repeated; a send that never started may run again after a restart but its draft lives in memory only, so it ends `draft_gone` instead of sending something stale.
- Privacy: `PrivacyGuard` (English, Bengali script, Bengali in English letters) answers "who messaged Raj / what did others send" with the fixed text "Raj has not permitted me to share that information." without using the AI; the shared generator has the same rule too. The AI only ever sees the one chat's own history with anonymised labels (unchanged). No message text, names or chat titles are logged or stored; only codes, ids and hashes.
- Errors: every refusal, failure or exception is turned into a short code and a task end state; nothing can crash the Background Service. The UI shows counts of confirmed, closing, not sent and uncertain, and the last reason code.
- Files: new `AutoReply.kt`, `NotificationReply.kt`. Changed: `Tasks.kt` (database version 4: `send_kind`, `counter_key`, `reply_counter`; counting inside `finish`), `TaskManager.kt` (`precheck` before the attempt, send kind and counter key, counter pruning), `MessageSender.kt` (`precheck`), `SharedTaskManager.kt` (`SendRequest.closing/counterKey`), `OwnerWatch.kt`, `MainBrain.kt` (registers the two senders, preflight), `Agents.kt` (hooks), `WhatsAppAutomation.kt` / `MessengerAutomation.kt` (`Queued`, `Skipped`), both Master Agents (record the decision), `AiResponseGenerator.kt` (privacy rule), `Prefs.kt`, `MainActivity.kt` (settings panel and texts), `WhatsAppActions.kt` / `MessengerActions.kt` (capability lists). No manifest change, no permission, no library, no new service, no second AI client or task manager.
- Known limits: the check cannot see a reply Raj sends from another device that leaves the notification untouched; a chat opened on this phone holds automation (uncertain) instead of being answered. If a device never re-posts the notification after an inline reply, every send ends UNCERTAIN, shows up in the red warning and eventually uses up the limit: that is the safe behaviour, not a bug. Not compiled in the authoring environment (no Android SDK there): please build it and report any compiler message.

# Owner Availability Check and the 30-second waiting system (step 9, no version bump)

- `OwnerWatch.kt` (new): the owner check. One saved wait window per conversation (table `owner_state`), one waiting task per message, one running task at a time. There is no timer thread: a timer is the saved deadline plus a task whose `not_before` equals it, and the Task Manager's dispatcher already sleeps until the earliest `not_before`. Nothing blocks the main thread or the Background Service.
- Window rules: the first eligible message opens a 30 s window; later messages join the SAME window (no second timer) and may move the deadline to their own 30 s, but never beyond 120 s after the first message. Older waiting tasks of the conversation are moved to the same deadline, so the order of messages is kept. Admitting the same message twice (repeated hand-over after a crash) changes nothing. A message that is not fresh (older than 5 min, or found in the notification shade when the listener connected) is not waited for: it is held as uncertain.
- Manual reply detection: `OwnerReplyDetector` reads the same MessagingStyle notifications as `MessageParser`, which drops the owner's own lines on purpose. A line by the device owner with its own time, newer than the window start (minus 10 s clock slack), is a SEEN reply: the conversation's waiting AND running tasks are cancelled at once with `owner_replied`. Authorship: person keys decide when both exist; otherwise no sender / "You" / "Me"; a sender carrying the owner's display name counts only in a private chat with somebody else (in a group, or in a chat named like the owner, it is unclear). Incoming lines from other people are never read as replies.
- Uncertainty is never turned into a guess: the chat's notification opened, dismissed or removed by the app (this is also what replying inside WhatsApp / Messenger looks like), an unclear or untimed own line, a plain notification without MessagingStyle, the listener off or reconnected during the wait, the notification gone when the timer ends, or no notification key all end as `UNCERTAIN`: automation is held and the waiting tasks are cancelled as `owner_uncertain`. Only "no sign of a manual reply, notification still there, listener connected the whole time, exact times" ends as `ELIGIBLE` (`no_reply_seen`). That means no sign was observed, not proof: a reply from another device that leaves the notification untouched cannot be seen by any notification-based check.
- Timer end: `TaskManager.pickAndClaim` asks `OwnerWatch.gate()` before it starts any task: Wait (deadline not reached), Proceed (ELIGIBLE, answer younger than 10 min), Cancel (owner replied / uncertain / no owner record / stale). The check at the deadline is made once and its answer saved, so an expired timer cannot start a second task. Conversations never influence each other (state, signals, cancellations are all per conversation id; groups and private chats of the same name are different conversations).
- Restart: windows and deadlines are in the task database (version 3, one `CREATE TABLE`, nothing deleted). After a restart the dispatcher resumes from the saved `not_before`. A window that was open while the whole process died is held as uncertain (`listener_gap` / `listener_off`) instead of being assumed safe, because notifications may have been missed. Housekeeping decides windows that ran out without a task and deletes decided records after 3 days.
- `Tasks.kt`: database version 3 (`owner_state`), `delayPendingOfConversation`, owner read/write/count/prune. `TaskManager.kt`: the gate, `ownerAdmit`, `cancelForOwner` (cancel without a coroutine, for the message worker), `openStore`, `ownerCounts`, housekeeping hook. `MainBrain.kt`: admits the message before creating its task; owner-replied / uncertain messages get no task (counted as ignored). `MessageProcessing.kt`: owner signals travel through the same worker queue as incoming messages (so a reply and the message before it are seen in order); `MessageStore.findConversation` finds an existing conversation without creating one. `YasminNotificationService.kt`: feeds the detector, reports removals with their reason (`super` is still called, so the existing handler keeps working), records `connectedSince`. `IncomingMessages.kt`: `GENERIC_TITLES` is `internal` (one word). `MainActivity.kt`: one status line in the Main Brain panel. No manifest change, no permission, no library.
- Behaviour change to know about: drafting now starts after the owner check. A draft appears about 30 s after the message instead of at once, and no draft is written (and nothing is sent to the AI) for a conversation where Raj replied or may have taken over. Waiting tasks that existed before this update have no owner record and are cancelled once (`owner_check_missing`).
- Privacy: owner records hold ids, times, a hash of the notification key and short codes. No message text, no sender or chat names are stored or logged; chat names pass through memory only to find the conversation id.
- Rule for the next step: outgoing lines YASMIN AI itself will write look like Raj's lines; the sender step must not let them count as manual replies and must check `OwnerWatch.eligibility()` right before sending. `submitSend` and every send path are unchanged; still no sender exists, nothing is sent.
- Not done, by design: sending, automatic replies.

# Shared Task Manager and message queue (step 8, no version bump)

- `TaskManager.kt` (new): ONE task manager for WhatsApp and Messenger. It now owns what used to sit in the Main Brain: the task database, the queue, the dispatcher, recovery after a restart and cancellation. One lane (`LANES = 1`): one task runs at a time for both apps together, oldest first; inside a conversation the order is strict (a waiting task blocks the newer ones of its conversation); new messages are queued while another task runs; every task ends in exactly one final state, so a failed task never blocks the queue and never marks a later task as done.
- `Tasks.kt`: database version 2. New task type `SEND_REPLY` and new column `send_phase` (`NONE`, `ATTEMPTING`, `CONFIRMED`, `NOT_SENT`, `UNCERTAIN`); existing databases are upgraded with one `ALTER TABLE`, nothing is deleted. One task per (type, conversation, message) is enforced by the database key, so a message can have at most one send, also after a restart.
- Sends (queue side only): `SEND_REPLY` tasks hold ids, never text. Before the sender is called the phase `ATTEMPTING` is saved; `COMPLETED` is written only when the sender returns `Confirmed`; a timeout, an exception or a cancel after that point is an **uncertain** send (FAILED, phase UNCERTAIN) and is never repeated automatically. After a restart an `ATTEMPTING` send stays uncertain unless the sender's `verify()` can tell (the default says no). Only a send that is certainly not sent and marked retryable goes back to waiting.
- Pause between sends: 3, 4 (default) or 5 s (`Prefs.SEND_DELAY_MS`, chips in the Main Brain panel). It counts from the finish time of the last CONFIRMED send (read from the database, so it survives a restart), applies to send tasks only, and never delays drafting. Failed, uncertain and cancelled sends start no pause.
- Cancellation: `cancelTask`, `cancelConversation`, `cancelAll`, now with an optional reason (`TaskManager.OWNER_REPLIED` is for the later owner-reply detection). A cancelled waiting task ends CANCELLED at once; a running one is cancelled; a send already handed to the sender ends as uncertain.
- `MessageSender.kt` (new): the sender contract (`SendResult`: Confirmed / NotSent / Uncertain, `verify`). **No implementation exists**, so no send task can be created: `submitSend` answers `no_sender_for_app`. Nothing is sent, nothing replies.
- `SharedTaskManager.kt`: the empty socket of step 6 is replaced by the real contract (`submitSend`, `cancelConversation`); `NoSharedTaskManager` stays the default. The Agent Catalog now hands `TaskManager` to both agents (the WhatsApp agent also got the parameter). Neither agent calls it yet.
- `MainBrain.kt`: shorter. It still validates and routes messages and builds what an agent gets (`runIncoming`); it hands tasks to the Task Manager. Public functions (`conversationState`, `cancelTask`, `cancelConversation`, `cancelAll`) keep their names and behaviour. `MAX_CONCURRENT` moved to `TaskManager.LANES`.
- Small edits: `Prefs.kt` (`SEND_DELAY_MS`, `getInt`/`putInt`), `MainActivity.kt` (queue line, uncertain-send warning, pause chips), `Agents.kt`, `BackgroundService.kt`, both agents (comments / parameter). No manifest change, no new permission, no new library, no new service.
- Not done, by design: sending, automatic replies, the 30-second owner check.

# Shared AI Response Generator (step 7, no version bump)

- `AiResponseGenerator.kt` (new): ONE engine that writes the reply text for both Master Agents. It sits on the existing `BrainAi` / `GeminiTextAi` (same saved API key, same text model, same HTTP client). No second client, key or library.
- Request: `ResponseRequest` = platform + conversation id + the incoming message + that conversation's earlier messages + the app's own notes for the AI + the agent's way of recognising placeholder lines + an optional "still relevant?" check. Result: `GeneratedResponse` = status (`GENERATED`, `NO_REPLY_NEEDED`, `SKIPPED`, `DISCARDED`, `FAILED`), text, platform, conversation id, message id, time, note code, error (code + retryable). It only generates text and never says anything was sent; `toString()` shows no text.
- General rules live once in the engine: Bengali (script), Banglish and English, including mixed messages; short and conversational; no invented facts; a short clarification question when the message is unclear; never comply with password / code / money / link requests; `NO_REPLY` when no answer is sensible. Each agent only supplies its own notes (`WhatsAppDrafter.APP_INSTRUCTIONS`, `MessengerDrafter.APP_INSTRUCTIONS`), which cannot override the general rules.
- Isolation: the engine refuses (nothing leaves the phone) if the message is not of the named platform and conversation, and drops history lines carrying another conversation id. It keeps no state between requests. Names become labels. At most 12 earlier lines / 3,500 characters.
- Errors: no key, network, HTTP 4xx/5xx, blocked or empty AI answer, empty text after cleaning, 75 s backup timeout all become `FAILED` with a short code; a failed request never produces text. Cancelling the task cancels the AI call. If a newer text message of the chat arrives while the draft is written, the text is dropped (`DISCARDED`) and the older draft is kept.
- Wiring: `MainBrain` creates one generator on top of its one `BrainAi` and hands it to every agent as `AgentContext.responses`. `WhatsAppDrafter` and `MessengerDrafter` are now thin adapters; their duplicated prompt / cleaning code is gone. `Agents.kt` and both agents: small edits.
- Behaviour changes: an empty AI answer is now a failed request (`empty_response`) instead of "no draft"; a draft that is too long is cut at a sentence end (also the Bengali danda) instead of mid-word; an unknown chat type in WhatsApp now numbers members like Messenger did.
- Not done, by design: sending, automatic replies, the 30-second owner check, the Shared Task Manager.

# Messenger Master Agent (step 6, no version bump)

- `MessengerMasterAgent.kt` (new): the agent (`AppAgent`, id `messenger-master`, serves Messenger and Messenger Lite only). Registered through `AgentCatalog`, so the Main Brain routes every Messenger task to it. A gate refuses anything that is not a Messenger message of the task's own conversation. `restartSafe` (a draft has no side effect outside the process). Returns short codes to the Main Brain: `draft_ready`, `no_draft:<reason>`, or a failure code (retryable only for temporary AI errors). If a newer text message of the same chat is already saved, the older one gets `no_draft:superseded_by_newer_message` and the newer message's own task writes the one draft.
- `MessengerKnowledge.kt` (new): knowledge only, no app control. Message types recognised from the notification text (text, emoji only, photo, video, voice message, sticker, GIF, file, location, reaction, unsent, call notice, hidden content, system notice), private / group as Android reports it (otherwise unknown), what the notification does and does not provide. Best effort: English wording and usual symbols only.
- `MessengerActions.kt` (new): action engine with exactly three actions: `IDENTIFY_MESSAGE_TYPE`, `REPLY_TO_INCOMING`, `CONTINUE_CONVERSATION` (the last two prepare a DRAFT). `MessengerCapabilities` is the honest list of what works and what does not. AI output is cleaned text, never executed. Media, notices, emoji-only lines, cut-off previews and empty messages get no draft.
- `MessengerConversationState.kt` (new): state per conversation id, memory only, at most 100 conversations, dropped when the service stops. Drafts expire after 1 hour. Separate classes from the WhatsApp agent's.
- `MessengerAutomation.kt` (new): interface for the future owner-availability check and automatic-reply workflow. Only `DisabledMessengerAutomation` exists and answers "not enabled". No waiting time, no automatic reply.
- AI: the existing `BrainAi` / `GeminiTextAi` (same key, same text model). The prompt holds this conversation's text only (new message + up to 12 earlier ones), names replaced by labels ("Contact", "Member 2"), message text is marked as data. Switch in Additional Settings > Messenger Master Agent (default on, own preference key `msgr_agent_drafts`); off = nothing is sent to the AI.
- Small edits: `Agents.kt` (catalog lists both agents, comments), `Prefs.kt` (`MSGR_DRAFTS`), `MainBrain.kt` / `BackgroundService.kt` (comments), `MainActivity.kt` (new agent panel with switch and counters; the "no Messenger agent yet" text corrected). No manifest change, no new permission, no new library.
- Nothing is sent, tapped or opened. Drafts are never sent. The WhatsApp agent is untouched. Both agents accept the future `SharedTaskManager` but do not call it yet.

# WhatsApp Master Agent (step 5, no version bump)

- `WhatsAppMasterAgent.kt` (new): the agent (`AppAgent`, id `whatsapp-master`, serves WhatsApp and WhatsApp Business only). Registered through `AgentCatalog`, so the Main Brain routes every WhatsApp task to it. Refuses anything that is not WhatsApp. `restartSafe` (a draft has no side effect outside the process). Returns short codes to the Main Brain: `draft_ready`, `no_draft:<reason>`, or a failure code (retryable only for temporary AI errors).
- `WhatsAppKnowledge.kt` (new): knowledge only, no app control. Message types recognised from the notification text (text, photo, video, voice message, audio, sticker, GIF, document, location, contact, poll, reaction, deleted, call notice, system notice), private / group as Android reports it (otherwise unknown), what the notification does and does not provide. Best effort: English words and usual symbols only.
- `WhatsAppActions.kt` (new): action engine with exactly three actions: `IDENTIFY_MESSAGE_TYPE`, `REPLY_TO_INCOMING`, `CONTINUE_CONVERSATION` (the last two prepare a DRAFT). `WhatsAppCapabilities` is the honest list of what works and what does not. AI output is cleaned text, never executed. Media, notices, cut-off previews and empty messages get no draft.
- `WhatsAppConversationState.kt` (new): state per conversation id, memory only, at most 100 conversations, dropped when the service stops. Drafts expire after 1 hour.
- `WhatsAppAutomation.kt` (new): interface for the future owner-availability check and automatic-reply workflow. Only `DisabledWhatsAppAutomation` exists and answers "not enabled". No waiting time, no automatic reply.
- AI: the existing `BrainAi` / `GeminiTextAi` (same key, same text model). The prompt holds this conversation's text only (new message + up to 12 earlier ones), names replaced by labels ("Contact", "Member 2"), message text is marked as data. Switch in Additional Settings > WhatsApp Master Agent (default on); off = nothing is sent to the AI.
- Small edits: `Agents.kt` (catalog lists the agent, comments), `MainBrain.kt` / `BackgroundService.kt` (comments), `Prefs.kt` (`WA_DRAFTS`), `MainActivity.kt` (corrected texts, new agent panel with switch and counters). No manifest change, no new permission, no new library.
- Nothing is sent, tapped or opened. Drafts are never sent. Messenger is untouched and has no agent yet.

# Main Brain / Agent Manager (step 4, no version bump)

- `MainBrain.kt` (new): `MainBrain` (the `MessageConsumer` registered with `MessageGateway`; sorts every processed message into a task, decides which app's agent is responsible, dispatches tasks, handles cancel / retry / restart recovery), `MainBrainModule` (hosts it inside the Background Service), `BrainRouting`, `BrainState` (real status for the UI).
- `Agents.kt` (new): `AppAgent` (the one interface every app-specific agent implements), `AgentManager` (register / unregister / look up / invoke with time limit), `AgentContext` (what an agent sees: ONE conversation), `AgentResult`, `AgentIds` (reserved ids `whatsapp-master`, `messenger-master`) and `AgentCatalog` (the list of agents of this build). **No agent is implemented: the catalog is empty.**
- `Tasks.kt` (new): `AgentTask`, `TaskStatus` (PENDING, RUNNING, COMPLETED, FAILED, CANCELLED), `TaskType` (closed enum, no free-form commands), `ConversationState`, and `TaskStore` (own SQLite file `yasmin_tasks.db`, private, not backed up). Task records hold ids, app, status and timestamps, never message text or names.
- `BrainAi.kt` (new): `BrainAi` text-in / text-out interface + `GeminiTextAi`, which reuses the saved API key, the text model setting and the existing OkHttp client. The Main Brain does not call it itself; it is for the future agents. Output is a plain string and is never executed.
- Routing: WhatsApp and WhatsApp Business tasks go only to the agent that serves WhatsApp, Messenger and Messenger Lite only to the Messenger agent. One agent per app (enforced), a reserved id cannot be registered for the wrong app, unknown / inconsistent sources are dropped before a task exists.
- Tasks: one per (conversation, message), unique in the database, so a repeated hand-over never makes a second task. Claimed with an atomic PENDING -> RUNNING update, one running task per conversation, oldest first, order kept inside a conversation. Retries only when the agent asks for it (max 3 attempts, 5 s / 20 s delay). Time limit per agent call (default 90 s). Waiting tasks expire after 6 h, at most 300 wait.
- Restart: tasks are stored. A task that was RUNNING when the process died is marked failed ("interrupted") unless its agent declares `restartSafe`. Messages stay pending in the message store while the Main Brain is not running (background service off) and are handed over when it starts. Tasks for an app without an agent wait.
- Small edits: `MessageProcessing.kt` (`MessageGateway.message()`: read one message, only through its own conversation; row mapping shared), `BackgroundService.kt` (module list), `CodeGen.kt` (`http` is shared instead of private), `MainActivity.kt` (corrected pending-messages text, new "Main Brain" status panel). No manifest change, no new permission, no new library.
- Nothing replies, sends or automates anything yet.

# Message Processing System (step 3, no version bump)

- `MessageProcessing.kt` (new): `MessageProcessor` (queue + one worker thread, order kept, no I/O on the main thread), `MessageStore` (SQLite from the Android framework, no new library; `yasmin_messages.db` in private storage, not backed up), `MessageGateway` + `MessageConsumer` (the one hand-over point for the future Main Brain / Agent Manager; nothing is registered yet), `ProcessedMessage`, `ConversationInfo`, `StoredMessage`.
- Conversations: one per (app package, private/group, chat name), random unique id (`c_...`). WhatsApp, WhatsApp Business, Messenger and Messenger Lite never share a conversation. A message whose kind Android did not reveal joins the existing conversation of that name; a known kind later upgrades it.
- Duplicates: second, persistent filter in the database (stable message key; messages without their own time: same key within 60 s). Re-posted / updated notifications and restarts add nothing twice.
- Hand-over: at-least-once, in time order per conversation, failures retried with 5-60 s back-off (max 5 attempts, then marked failed). Until a consumer registers, messages stay stored as pending. Messages found in the notification shade at connect are flagged `isBacklog`.
- Limits: newest 500 messages per conversation, 20,000 in total. Message text, sender and chat names are never logged.
- `IncomingMessages.submitAll` now returns the accepted messages. `YasminNotificationService`, `BackgroundService` (module starts the processor), `MainActivity` (counters, delete button, corrected privacy text) got small edits. No manifest change, no new permission.

# Notification Listener (step 2, no version bump)

- `IncomingMessages.kt` (new): `IncomingMessage` data model (app, package, chat title, group flag + group name, sender, text, message time, notification id / key / tag, live-or-backlog flag), `MessageSource` (WhatsApp / Messenger kept apart, Business / Lite included), `MessageParser`, `IncomingMessages` (duplicate filter, 200-message in-memory history, `flow` for future consumers, counters) and `MessageAccess` (OFF / WAITING / CONNECTED state, settings screen, rebind).
- `YasminNotificationService`: only WhatsApp / Messenger notifications are parsed (other apps are dropped before anything is read). The existing Inbox, reply tool and voice announcements are untouched. Access revoked or listener disconnected is recorded and clears the in-memory messages.
- `BackgroundService.kt`: first real module, `MessageAccessModule`: watches the access setting and asks Android to rebind the listener when access is granted but the listener is not connected.
- `MainActivity.kt`: Additional Settings > Message detection panel (real status, button to Android's notification-access screen, counters); the existing Permissions row opens the same screen. 
- No manifest change, no new permission, no new library. No messages are sent or answered.

# Background Service foundation (not yet released, no version bump)

New, separate from the voice service (AssistantService is unchanged):
- `BackgroundService.kt` (new): `YasminBackgroundService`, a foreground service of type `specialUse` with its own notification channel
  (`yasmin_background`, low importance), a persistent notification (running time, active module count, Stop button, tap opens the app)
  and a real `BackgroundState` (running, start time, active modules, module errors, restart count, notification visible, last error).
- Lifecycle: START / STOP actions; `START_STICKY` so Android restarts it after killing the process (only if the user still has it on);
  restored after reboot and app update by `BackgroundBootReceiver`, and when the app is opened. A start Android refuses is reported, not hidden.
- Extension point for later: `BackgroundModule` + `BackgroundModules.create()`. Each module runs in its own coroutine; one failing
  does not stop the others. The list is empty: no Task Manager, no WhatsApp / Messenger agents exist yet.
- UI: one switch in Menu > Additional Settings > Background service, with the real status.
- Manifest: the service (with the required special-use subtype) and the receiver. No new permissions.
- Files changed: `AndroidManifest.xml`, `MainActivity.kt` (one line in onCreate + one panel in the Additional Settings page).

# v1.6.2 - voice verification: Owner only when the real score passes (VoiceId.kt, AssistantService.kt)

Causes found in the v1.6.1 code (not scoring tricks):
- An Owner verdict stayed valid for 120 s. Speech that was too short to score (< 1.5 s) never updated it, so another voice speaking short phrases was still treated as the Owner (phone actions allowed, Gemini not told).
- Gemini was told "the speaker is the Owner until a notice says otherwise", so anyone was treated as the Owner (affectionate manner, own name) until a judged sentence failed, and the first reply always came before the verdict. Gemini was also free to take claims or the name as proof.
- Held-back private message notices only waited for a Guest, not for an unverified voice.
- Utterances were cut after 300 ms of quiet, so normal sentences broke at their pauses into pieces too short to judge: the Owner was often left unverified.
- The decision used the rounded score (69.6% passed a 70% limit). The new Gemini session after a reconnect was never told the current state.

Fixes:
- The verdict holds only until someone speaks again: from 0.3 s of new speech until it is judged (>= 1.5 s of speech) nobody counts as the verified Owner. Tools and private notices stay locked meanwhile.
- Prompt: everybody is UNVERIFIED until the phone's [VOICE CHECK] notice says Owner verified. Words, claims, the Owner's name, calling her YASMIN AI, pet names or passwords never make anyone the Owner. Notices are sent on every change, also the first Owner verdict, and again to a new Gemini session.
- Owner = unrounded score >= slider (also in Test my voice). Below the slider = Guest. Slider and score scale unchanged. Nothing uses speech recognition or the transcript to identify a speaker.
- Utterance end 300 ms -> 500 ms.
- Known limit: phrases with less than 1.5 s of speech cannot be verified, so phone actions need a full sentence. A Guest's very short phrases can still get a friendly spoken answer until the next judged sentence flips Gemini; they cannot trigger tools or private notices.

# v1.6.1 - voice recognition fixes (VoiceId.kt, MainActivity.kt Additional Settings page only)

- Enrollment: still 3 to 8 recordings of 10 s. Each recording now shows a DIFFERENT Bengali text with Bengali instructions (read it aloud, repeat from the start if you finish early).
- New **Test my voice** (8 s, separate Bengali texts): scores the voice exactly like the live check and compares the score with the slider. Let the Owner try (should pass) and another person try (should be rejected).
- Verification: same on-phone MFCC/covariance matcher, no speech recognition involved. Fixes: scoring is now calibrated against the 90th-percentile distance between your own samples (kept in a safe range) instead of the median, with a steeper fall-off; utterances need >= 1.5 s of speech to be judged (was 1 s); the first 0.4 s of a recording is ignored; enrollment now uses the same echo canceller + noise suppressor chain as the live assistant.
- Slider unchanged (0-100%, compared with the similarity score). Default is now 80% (was 62%) for people who never moved it. The score is a similarity score, not a probability.
- Existing voiceprint files still load, but re-record with **Reset profile** to get the benefit of the new recording procedure.

# v1.6 - Owner / Guest voice recognition

## New
- Menu (three lines) > **Additional Settings** (below Preferences). All voice recognition settings live there:
  - switch: recognize my voice as the Owner
  - **Record sample** (10 s each, 3 to 8 samples) and **Reset profile**
  - exactly ONE slider, 0-100%: minimum voice match to be treated as the Owner (default 62%). Below it the speaker is a Guest.
  - shows the last measured match so the slider can be tuned
- `VoiceId.kt` (new): real on-phone speaker verification, no new library, no network. MFCC features, mean-normalised covariance
  model of the voice, arithmetic-harmonic sphericity distance, calibrated against the distance between the Owner's own samples.
  The voice profile (statistics only, no audio) and the slider value are saved locally (`voiceprint.bin`, preferences).
- Guest behaviour: Gemini gets a short `[VOICE CHECK]` notice when the speaker flips between Owner and Guest and answers Guests politely
  (general questions, no pet names, nothing private). Phone actions are blocked in code for Guests / unverified voices (not only by prompt),
  and new-message notices are not read out while a Guest is the last speaker.
- `AudioIO.isPlaying()`: the check ignores the time YASMIN AI is speaking, so her own voice is never judged.
- `AssistantService`: starts / stops the check with the session, feeds it the same mic audio, gates tool calls.
- Nothing changes when the switch is off or fewer than 3 samples are saved.

# v1.5 - new blue glass UI (from the design mockups)

## New
- `Ui.kt`: shared design system (colours, drawn icons, glass cards, top bar, "Select Model" dropdown, voice bars, steps row). No new library needed.
- `MainActivity.kt`: new home screen. Top bar (menu, "YASMIN AI Online", bolt), logo, AI Assistant card, Voice Assistant / Screen Share / Conversation cards, footer with version.
  Side drawer: Permissions, Voice Pack, API Key, Conversation, Preferences. Every old setting is still there (name, languages, country code, both switches, custom model names).
- `AiActivity.kt`: now Compose. Back arrow, "YASMIN AI Online", bolt button opens "Select Model" (the 3 verified Live models, switching works exactly like before).
  Listening / responding show moving voice bars, thinking shows "Listening - Processing - Responding". MUTE and STOP are at the bottom.
- `OrbView.kt`: swirling energy ribbons inside the sphere (instead of globe lines).
- Optional: put your logo as `app/src/main/res/drawable/yasmin_logo.png` and it is used automatically in the header and drawer.
- No AI / service logic was changed.

# v1.4 - consistent voice

- `Voice` object (Prefs.kt): one central voice setting. All 3 Live models get the same `voiceName`.
- `AssistantService`: the voice is pinned when a session starts and reused for every reconnect (10-minute GoAway, errors) and model switch.
  Choosing another voice while running applies from the next START (a toast says so).
- `AudioIO`: a leftover odd byte of interrupted speech is dropped, and each answer ends with a marker, so the next answer can never start one byte off (that garbles every sample and sounds like a different voice).
- Persona: the messaging rule no longer tells her to "drop the girlfriend voice" (it now only applies to the written text); one extra line says the voice stays the same for quotes, read-aloud messages, names and foreign words.

# v1.3 - full-screen AI sphere screen (lightweight 2D)

## New
- `AiActivity.kt`: black full-screen screen with an animated energy sphere and the status text
  ("YASMIN AI is listening... / thinking... / responding..."). It contains no AI logic: it starts/stops the existing
  `AssistantService` (same checks as the main screen) and only displays `AppState`.
- `OrbView.kt`: plain Android Canvas view (no OpenGL, no bitmaps, no blur). Cached gradients, pre-allocated paints,
  time-based motion, adaptive quality (drops particles/trails if frames are slow), animates only while the screen is visible.
- Close (X) and Back leave the screen and keep YASMIN AI running. STOP ends the session (ACTION_STOP) and closes.
- Main screen: tapping the orb or the new "OPEN AI SCREEN" button opens it. START/STOP on the main screen are unchanged.
- `AppState.thinking` + small changes in `AssistantService` so "thinking" is a real state: user stopped talking,
  phone action running, or Extended Thinking in progress. Cleared when speech starts, on interrupt, on turn end, on stop.

# v1.2 - model selector, model switching, bigger conversation memory

## New
1. **Model selector** (top of the main screen, flat 2D, animated): only Gemini models that Google documents for the Live API on the Gemini Developer API (checked 1 Oct 2026 on ai.google.dev):
   `gemini-3.8-live` (default), `gemini-3.8-live-extended-thinking`, `gemini-3.1-flash-live-preview` (marked legacy by Google, still supported).
   The old code force-replaced 3.1 Flash Live with 3.8 Live; that rule is removed so 3.1 can be picked.
2. **Switch without restarting**: tap another model while running. Current session is closed, audio stopped, any running phone action finishes first (max 15 s), then it reconnects on the new model. If the new model is rejected before it connects, the app goes back to the previous one.
3. **ONLINE control** next to the selector: ONLINE / CONNECTING / SWITCHING / OFFLINE; tapping it starts or stops YASMIN AI (same as START/STOP).
4. **Per-model setup**: tools use `BLOCKING` on 3.8 Live (as before), `NON_BLOCKING` on Extended Thinking (required by Google), and no `behavior` field on 3.1 Flash Live (sequential only). Extended Thinking also sends `thinkingLevel: LOW`, uses its documented `v1alpha` WebSocket URL and follows `interactionStatus`.
5. **No overlapping sessions / tool calls**: one scheduled reconnect at a time, old session closed before a new one opens, late messages from a closed session are ignored, tool calls run one at a time and are dropped if their session was replaced.
6. **Conversation memory**: saved to disk after every exchange (fsync). New sessions (including after a model switch) get the most recent messages word for word plus a short digest of older topics (a local digest, not an AI summary).
7. **250 MB limit**: memory is stored in 256 KB files; when the total passes 250 MB the oldest whole files are deleted (files are never edited in place, torn lines are skipped on read). Old notes from v1.1 are kept and read as before.

# v1.1 - connection fix, faster replies, YASMIN AI builds apps

## Connection ("calling") fixes
1. **Wrong model was forced.** The old code replaced `gemini-3.8-live` with `gemini-3.1-flash-live-preview`. Google's docs (Sept 2026) now list `gemini-3.8-live` as the GA default and the 3.1 preview as a legacy model, so YASMIN AI was connecting to the old one. Default is now `gemini-3.8-live`; saved legacy names (3.1 preview, 2.5 native audio, 2.0) are upgraded automatically.
2. **Session resumption + GoAway.** The server ends every connection after ~10 minutes. YASMIN AI now keeps the resume token, reconnects at once (no wait) and continues the same conversation instead of dropping.
3. **Smarter retries.** 12 tries with 0.6 s -> 8 s back-off (was 6 tries), waits quietly while there is no internet, 10 s connect timeout, 12 s setup watchdog (no more hanging on "Connecting...").
4. **Clear errors.** Bad key, unknown model or exhausted quota stop immediately with a readable message instead of retrying forever. HTTP status and server text are shown in the log.
5. Tool declarations use `behavior: BLOCKING` (3.8 Live is asynchronous by default, which would let YASMIN AI talk on before a phone action finished).

## Speed
- `gemini-3.8-live` (low-latency default, no thinking delay).
- Voice activity detection: end of speech after 500 ms of silence (Google's recommended lower bound) with high end-of-speech sensitivity.
- Shorter waits after every tap / type / scroll (about 120 ms less per step, 200 ms less when opening an app).

## New: YASMIN AI builds apps by herself (`AppBuilder.kt`, `CodeGen.kt`, `assets/build-app.sh`)
Say for example: "YASMIN AI, make me a to-do app with a dark theme and build the APK".
1. YASMIN AI asks at most one question, reads the plan and waits for your yes.
2. A text model (`gemini-3.8-flash`, editable in Settings) writes the source code for the project.
3. The project is saved in Termux at `~/yasmin-apps/NAME`.
4. Termux compiles it (Gradle) into an APK -> `/sdcard/Download/YASMIN-AI-NAME.apk`.
5. If the build fails, the compiler errors are sent back to the code model and fixed automatically (3 tries).
6. Everything runs in the background. YASMIN AI tells you when it is done; say "install it" and the Android installer opens.

New voice tools: `build_app`, `app_rebuild`, `app_status`, `app_list`, `app_write_file`, `app_read_file`, `app_install`.
Two templates: `webview` (HTML/CSS/JS in a WebView, fast to build, best for most apps) and `native` (Kotlin, framework views only).

Safety: files may only be written under `app/src/main` and `app/build.gradle.kts`; namespace/applicationId are forced; Gradle files with commands, tasks or file/network access are rejected; the code model and YASMIN AI refuse spyware, phishing, ransomware and similar apps. Only ONE build runs at a time.

Needs: Termux set up as in TROUBLESHOOTING.md, internet, about 1.5 GB free space for the first build (build tools are installed once, later builds take a few minutes).

Not verified (no phone/compiler was available while writing this): build the APK first and send me any `e:` lines. The first real test of the app builder will show if Termux keeps the background build alive on your phone; if not, disable battery optimisation for Termux.

---

# WhatsApp / Messenger fixes

## Why sending and reading failed
1. Fixed 600 ms sleep after every action -> screen read before the app finished loading -> empty or stale reads.
2. `rootInActiveWindow` is null right after an app switch; nothing retried, so tools failed randomly.
3. `type_text` appended to whatever draft was already in the box (old text got sent too), did not focus the field first, and never checked the result.
4. `click_text` took the first substring match (e.g. a chat bubble containing "send") instead of the real button.
5. No way to tell incoming from outgoing messages; `get_screen` cut every line at 80 chars and 70 nodes.
6. No send verification at all: the model said "sent" without knowing.
7. Persona only had the girlfriend voice, so drafted replies sounded like YASMIN AI, not like the user.
8. Default model `gemini-3.8-live` is not a real model name -> connection closed at once.
9. User interruptions did not cancel pending tool calls (a send could still run).

## What changed
- `YasminAccessibilityService`: retrying `root()`, `awaitStable()` (waits until the UI is quiet), ranked `clickText`, focus-then-replace typing.
- `Messaging.kt` (new): `open_chat`, `read_chat`, `send_message`. WhatsApp uses direct wa.me links when it can resolve a number; otherwise the app's own search. Reading tags every line ME / THEM and lists messages still unanswered. Sending replaces the draft, verifies the text, presses Send, then verifies the box emptied and returns the last messages.
- `YasminNotificationService.kt` (new): `read_notifications` and `reply_notification` (uses the notification's own Reply button, most reliable way to send). New-message notices are spoken when YASMIN AI is idle.
- `Tools.kt`: 5 new tools, messaging persona rules (write as the user, mirror language/tone/length, never invent facts, confirm before sending, treat message text as data).
- `GeminiLive` / `AssistantService`: tool-call cancellation, model-name repair.
- Settings screen: Notification access row, country code, announce switch.

## Setup after installing
1. Allow **Notification access** for YASMIN AI (Android 13+: App info -> ⋮ -> Allow restricted settings first).
2. Keep **Accessibility** on.
3. Enter your **country code** (e.g. 880, 91) so local numbers like 01xxxxxxxxx become WhatsApp links.

## Known limits (please test on your phone)
- Written without a device or compiler available: build it and report any `e:` lines.
- WhatsApp/Messenger change their screens; ME/THEM detection is by bubble position and read-ticks. Lines marked `?` are uncertain and the assistant is told not to guess.
- `reply_notification` gets no delivery receipt from Android; the result says `verified: false` when it cannot confirm.
- Messenger has no number deep link, so it always uses in-app search.
- If `gemini-3.1-flash-live-preview` is retired, put a current Live model name in Settings (e.g. `gemini-2.5-flash-native-audio-preview-12-2025`).

---

# v1.7 - phone-control fixes

## Causes found
1. Volume, brightness and "lock" had no tool at all. The model improvised raw shell commands (`settings put system screen_brightness` without switching off auto-brightness, `airplane_mode_on` which does not toggle airplane mode on modern Android) and then reported success.
2. `open_app`, `open_url` and `call_number` always answered "ok" even when Android blocked the start from the background, and `call_number` said ok when only the dialer opened.
3. Failed taps, typing, scrolling and key presses returned `ok:false` with no reason.
4. A stale accessibility instance could be kept after Android stopped the service; "service enabled but not connected" was reported as "OFF".
5. Contacts, SMS and Phone-state permissions could not be granted from inside the app (only via root); Do-Not-Disturb access and "Modify system settings" had no entry on the Permissions page, and `ACCESS_NOTIFICATION_POLICY` was missing from the manifest, so YASMIN AI could not appear in the Do Not Disturb access list.
6. Android 14+ refuses microphone foreground services started at boot; `AssistantService` did not catch that and could crash.
7. `setup_self` hid every error (`2>/dev/null`) and returned only a count; it did not wait for the accessibility service to connect.
8. `su` missing threw a raw IOException message.

## Changes
- NEW `PhoneControl.kt`: `set_volume`, `set_brightness`, `lock_screen`, `phone_status`; verified `open_app`, `open_url`, `call_number`. Each reads the state back from Android and returns `ok:false` + `error` + `fix` on failure.
- `Tools.kt`: new tool declarations, honest error text for every accessibility action, `press_key lock_screen` routed to the verified lock, prompt rules for honest reporting, wrong airplane-mode hint removed.
- `YasminAccessibilityService.kt`: clears its instance in `onDestroy`/`onUnbind`.
- `Root.kt`: `su` start failure returns a clear message.
- `Device.kt` (`setup_self`): also grants Do Not Disturb access, waits for the accessibility service, returns the real `phone_status`.
- `MainActivity.kt`: asks for Phone state, Contacts and SMS; new rows for "Change system settings" and "Do Not Disturb access"; accessibility check now accepts both component name forms.
- `AssistantService.kt`: a refused foreground-service start no longer crashes.
- `AndroidManifest.xml`: `ACCESS_NOTIFICATION_POLICY`.
- Version 1.7 (code 6).

## Not verified
Written without a phone or compiler in the sandbox. Build it and send any `e:` lines.

---

# v1.9 - step 11: privacy protection and conversation management

Built on steps 3-10. No new system, no new dependency: the task database (version 4 -> 5), the Task Manager, the two Master Agents, the shared AI engine and the notification sender are reused.

## What was already there (unchanged)
- Per-conversation state and drafts in each agent; the shared AI engine only gets the one conversation it answers (other lines dropped, names replaced by labels).
- `PrivacyGuard` (phrase match for "who messaged Raj / what did others send") and the fixed refusal text in the sender.
- The reply count per person and app, kept in the task database (read from disk on every check, so a restart cannot reset it).
- Owner check: a manual reply seen by the notification listener cancels pending and running tasks of that conversation.

## What this step adds
1. **Conversation lifecycle** (`Privacy.kt`, table `conversation_lifecycle`): ACTIVE / PAUSED / COMPLETED / BLOCKED per person and app, under the same hash as the reply counter (no names stored). ACTIVE = no row. Automatic changes only go up; only Raj lowers a state.
   - PAUSED: set by `OwnerWatch` when a manual reply is seen while YASMIN AI is handling the conversation (a wait is open, or the reply came right after the message that opened it). Stays until Raj resumes it in Settings. "May have replied" (opened/dismissed notification, unclear line) is still only a hold on that one wait, not a pause.
   - COMPLETED: set in the same database transaction that counts the closing message (and also when a conversation is found at the limit with its closing message already done).
   - BLOCKED: set by Raj in Settings; also deletes the saved messages of that chat.
2. **State is checked at six places**: `MainBrain.decide` (no task for a held conversation), `TaskManager.pickAndClaim` (before any task starts; waiting tasks of the conversation are cancelled), `AutoReply.consider`, `AutoReply.preflight` (no AI call), `NotificationReplySender.prepare`, and once more right before the reply action is fired. A state that cannot be read counts as "do not send".
3. **Output check** (`PrivacyChecks`): an AI reply with an API key, a run of 6+ words copied from the AI's own instructions, the name of another chat or contact, or 6+ consecutive words of another conversation's messages is not used. Run in `AiResponseGenerator` (result FAILED `privacy_blocked_<reason>`) and again on the exact text in the sender (NotSent `privacy_blocked_<reason>`).
4. **Privacy questions never reach the AI**: the generator returns the fixed refusal text itself.
5. **Retention**: handled messages are deleted after 30 days (`MessageStore.RETENTION_DAYS`); blocking a person deletes that chat's messages.
6. **Settings**: new "Privacy and conversations" panel (counts, resume paused, block / unblock by chat name, privacy-check counters).

## Limits (please read)
- The output check compares exact words. It cannot catch a leak that is reworded, translated or hinted at. The protection that does not depend on it is isolation: the AI never receives another chat.
- It can block a harmless reply (for example one that happens to contain a contact's full name). A blocked reply is simply not sent.
- A paused conversation stays paused until Raj taps "Resume all paused conversations". There is no automatic resume.
- Block / unblock needs the chat name exactly as the app shows it. A person who is in the saved list under a different name is not matched.
- A manual reply made inside WhatsApp / Messenger itself (not through the notification) is only detected indirectly; see step 9. That limit is unchanged.
- Written without a compiler or phone in the sandbox: build it and send any `e:` lines.

## v2.2 - language detection, voice-message apology, fixed introduction (NOT compiled/tested on a device)
- BrainLanguage.kt (new, part of the Main Brain): offline detection of Bengali script / Banglish / English / mixed; the result is given to the Main Brain's own model as an explicit LANGUAGE line. Banglish gets Bengali script.
- MainBrainReplyWriter: language rule rewritten (Banglish -> Bengali script, was "Bengali in English letters"); owner name Raj -> Mehdi in the rules; first reply is now the exact fixed introduction in the sender's language (no AI call).
- WhatsAppDrafter / MessengerDrafter: a voice message / audio file gets the fixed apology in the conversation's language (Bengali by default); no AI call, nothing is guessed about the audio. First reply = introduction + apology.
- Bengali-UI voice placeholders added to WhatsAppKnowledge / MessengerKnowledge.
- Untouched: agents, task queue, 30 s wait, owner detection, 20-reply limit, root, background service, voice assistant. Replies are text only (MainBrainModel already drops audio).

## v2.3 - auto-reply personality only (NOT compiled/tested on a device)
- BrainLanguage: the fixed first reply of every new conversation now starts with "আসসালামু আলাইকুম! 😊" followed by the YASMIN AI introduction (Bengali text as specified; the English variant also starts with the same greeting).
- MainBrainReplyWriter (CORE_RULES): new Personality rule - always polite, kind, friendly, respectful, never insulting or judging, gentle and positive even with rude or angry people, no repeated introduction after the first reply. Shared by WhatsApp and Messenger.
- Nothing else changed.

## v2.4 - auto-reply language, greetings and personality only (NOT compiled/tested on a device)
- BrainLanguage: Hindi (Devanagari) detection added next to Bengali / Banglish / English; mixed messages are answered in the dominant language (ties: Bengali, then Hindi). Fixed first replies per language: Bengali/Banglish "আসসালামু আলাইকুম! 😊" + Bengali intro, English "Hello! 😊" + English intro, Hindi "नमस्ते! 😊" + Hindi intro. Voice-message apology also available in Hindi.
- MainBrainReplyWriter (CORE_RULES + prompt): language rule covers Hindi and mixes; stronger Personality rule (calm and kind even with abuse); no greeting or introduction after the first reply.
- Nothing else changed.

## v2.5 - Coding Agents, phase 1: foundation (NOT compiled/tested on a device)
- New screen CodingAgentsActivity (own Activity, opened from Additional Settings -> "Open Coding Agents"): chat view + Gemini API key settings.
- Coding Agents API key: CodingKeyStore.kt, AES-256-GCM with an Android Keystore key, own storage file, never touches the Main Brain key (Prefs.KEY). One key for all coding agents. Show/Hide, Save, Update, Remove, Get API Key; a saved key is checked with a real request to Google.
- Agents: CodingModels.kt (gemini-3.8-flash, gemini-3.7-flash, gemini-3.1-pro-preview, gemini-3.5-flash-lite, plus a Custom model name).
- Chat: GeminiChatApi.kt (real generateContent call, header key, readable error messages), CodingEngine.kt (per-session state, Stop / Retry), CodingStore.kt (projects and one chat session per project + agent, saved in app-private storage).
- Changed existing files: MainActivity.kt (one panel in Additional Settings), AndroidManifest.xml (one activity). No other feature touched.

## v2.6 - Coding Agents, phase 2: real coding, project files, uploads (NOT compiled/tested on a device)
Only the Coding Agents section changed. Main Brain, WhatsApp / Messenger automation, voice assistant, phone control and the Phase 1 API key system (CodingKeyStore) are untouched.
- Projects now have a type (Android app / Website / General programming) and a description (purpose, UI design, features). A new project with a description shows a "Generate project from description" button.
- Each project owns one private folder (filesDir/coding_agents/projects/<id>/). Every path is cleaned (no "..", no absolute paths, no odd characters) and must resolve inside that folder, so a project cannot read or overwrite another project. Deleting a project deletes its files.
- Files tab: folders, new file / folder, open, edit + save, rename / move, delete (folders recursively), file and size limits (400 KB per file, 800 files, 25 MB per project).
- Import: ZIP projects (zip-slip safe, size / entry limits, skips binaries, build output and .git, strips a single top-level folder) and single source files (never replace an existing file). A report lists what was skipped.
- Source viewer with line numbers and syntax highlighting (Kotlin, Java, JS/TS, Python, C-family, Go, Rust, Swift, PHP, shell, SQL, CSS, JSON, YAML/TOML, HTML/XML). The chat code blocks use the same highlighter.
- Modes in the chat: Chat, Generate, Modify, Fix errors, Explain, Add feature. Every request sends the real project files (up to ~140k characters, the focus file and files named in the message first) to the selected Gemini agent with the Coding Agents key.
- The agent answers with file blocks. They become "Proposed changes": review (view each file), Skip, Apply all or Discard all. Nothing is written to the project before Apply. A file block that was cut off, has an unsafe path or is too large is dropped and reported; it is never half-saved. Explain mode ignores file blocks.
- Reference images (UI screenshots): up to 4 per message, shrunk to JPEG and sent as image parts of that message.
- Chat history stays per project and agent (Phase 1). Viewer buttons Explain / Fix errors / Modify jump to the chat with that file as focus.
- Changed: CodingAgentsActivity.kt, CodingEngine.kt, CodingStore.kt, GeminiChatApi.kt. New: CodingFiles.kt, CodingProtocol.kt, CodingHighlighter.kt, CodingProjectUi.kt. No manifest, Gradle or dependency change.
- Limits: reference images are sent only with the message they are attached to; mode / attached images are lost when you switch between the Chat and Files tabs; the highlighter is a tokenizer (approximate); files over 120k characters are shown without colours and over 150 KB cannot be edited in the app; one answer is limited to 32768 output tokens, so very large projects must be generated in several steps. Building the APK is NOT part of this phase.
- Written without a compiler or phone in the sandbox: build it and send any `e:` lines.

## v2.7 - Coding Agents, phase 3: Export Source ZIP (NOT compiled / tested on a device)
Only the Coding Agents Files tab changed. Main Brain (Prefs.KEY), WhatsApp / Messenger automation, voice assistant, phone control, CodingKeyStore and the phase 1-2 file logic are untouched. No manifest, Gradle or dependency change; no new permission.
- Files tab: new "Export Source ZIP" (becomes "Export again"), "Download ZIP" (Android file picker, CreateDocument) and "Save to Downloads" (MediaStore, Android 10+ only; on 8-9 use Download ZIP and pick the Download folder). Export can be repeated; it always rebuilds from the current project files and says when the project changed after the last export. Unapplied proposed changes are not exported (the card says so).
- ZIP content: the project's actual files from filesDir/coding_agents/projects/<id>/, inside one top-level folder named after the project. Written to app cache as .part, re-opened, every entry read back (CRC) and entry count + byte total compared, then renamed. Progress bar + Cancel while packing and while saving; a failed or cancelled save removes the half-written target.
- Secrets: files like .env, local.properties, keystore.properties, *.jks / *.pem / *.key, google-services.json, credentials.json, id_rsa* are left out. Inside the other files, secret VALUES are replaced with REDACTED_BY_YASMIN_EXPORT (Google/OpenAI-style/GitHub/AWS/Slack tokens, private-key blocks, quoted values after api key / secret / token / password names, unquoted values in .properties/.env/.yml files, and the saved Coding Agents key if it appears). Placeholders such as YOUR_API_KEY are kept. Pattern based: it can miss unusual secrets and can occasionally replace a harmless string; the card and YASMIN-AI-EXPORT.md list every file that was changed.
- Android projects: the ZIP gets build-termux.sh (assets/export/build-termux.sh: installs Java 17, aapt2, Gradle 8.9, builds assembleDebug, copies the APK to Download) unless the project has its own. If settings.gradle(.kts), root build.gradle(.kts) or gradle.properties are missing they are added to the ZIP only (AGP 8.7.3, Kotlin 2.0.21, as in phase 2 rules). A missing app/build.gradle(.kts) or AndroidManifest.xml cannot be invented: the export still succeeds but shows a "Not buildable yet" warning. No gradlew / wrapper jar (binary); use the script or a system Gradle 8.9. ZIP entries carry no Unix permissions, so run the script with `bash build-termux.sh`.
- Websites and general projects: plain file export plus YASMIN-AI-EXPORT.md (what was included, left out, replaced).
- Limits: nothing new beyond phase 2 (800 files, 25 MB per project); free cache space is checked first and "storage full" errors are reported in plain words.
- New: CodingExport.kt, CodingExportUi.kt, assets/export/build-termux.sh. Changed: CodingProjectUi.kt (one line: ExportCard in FilesTab).
- Written without a Kotlin compiler or phone in the sandbox: build it and send any `e:` lines.

## Lock fixes
- PatternLockView: pattern confirm now works (the drag handler kept an old callback, so the confirm step re-ran the first step).
- Lock gate (YasminAccessibilityService): while the YASMIN lock is closed and the screen is on, opening any other app (e.g. tapping a notification) brings the lock screen back; notifications still show. Screen-off automation is not affected, and YASMIN's own app launches have a 20 s grace.
- Lock gate also closes the notification shade / Wi-Fi & quick-settings panel while locked (back + lock screen).

## Lock fixes 2 - speed, touch, strict gate, YASMIN always allowed (NOT compiled/tested on a device)
- Speed: PBKDF2 rounds 120k -> 12k (the 5-try lockout is what stops brute force); old credentials are re-hashed faster automatically after the next correct unlock; Keystore key cached in memory; Keystore + hash code warmed up in the background when the lock screen opens; no close animation on unlock.
- Touch: PatternLockView has its own gesture loop (reacts on first touch, consumes moves, checks the points between two samples, bigger hit radius); the lock screen no longer sits inside a vertical scroll (it stole pattern / swipe drags) except for password type; the keyboard is no longer mistaken for "another app" by the gate.
- Strict gate: an opaque cover view (accessibility overlay) is drawn the moment another app comes to front while locked, then HOME + lock screen; tapped notifications cannot be seen or touched. notificationTimeout 100 -> 0 ms.
- Control panel: detected as a big System UI window (also from package-less "windows changed" events), closed with GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE (API 31+, BACK before). Lock screen is immersive (status bar hidden).
- YASMIN always allowed: every YASMIN action (tap, swipe, click, type, paste, key, open app) keeps the gate open 8 s (open app 12 s) and the lock comes back by itself when YASMIN is quiet; the lock screen is not popped up while YASMIN works. Screen-off automation and notification auto-reply never depended on the lock and are unchanged.

## Lock fixes 3 - open app itself needs lock, uninstall protection (NOT compiled/tested on a device)
- MainActivity already called YasminLockManager.guard() on every resume, so opening YASMIN AI while locked already showed the lock screen; this is unchanged and confirmed, not new.
- New: YasminDeviceAdmin.kt + res/xml/device_admin.xml + manifest receiver. Settings > Security / Custom Lock has a new "Uninstall Protection" panel: "Turn on" opens Android's own device-admin screen; while active, Android itself refuses Uninstall / App info > Uninstall for YASMIN AI. "Turn off" asks for the lock secret (requireAuth, same as every other change here) before removing the admin. Needs a Pattern/PIN/Password lock set first (Swipe has no secret to guard it).
- Honest limit: this uses Android's standard device-admin uninstall block, the same mechanism parental-control and MDM apps use on their own app - it is not a secret hook and a technical user can still remove it from Settings > Security > Device admin apps without opening YASMIN AI, Android always allows that. It stops the ordinary "long-press icon > Uninstall" / "App info > Uninstall" path without the lock secret.

## v2.14
- Fix: the voice no longer stops in the middle of a sentence. The server's "goAway" notice used to drop the connection at once and cut the speech; now the current answer is finished first, the speaker drains, then the session is refreshed. If a connection still ends mid-answer, the new session continues the answer without being asked.
- New: ElevenLabs voice (female voices only) next to the Gemini voice. API key field on the API Key page, voice list on the Voice Pack page, wave button at the top of the screen to switch between Gemini and ElevenLabs at any time. Gemini remains the brain; ElevenLabs only speaks the text. Model defaults to eleven_v3 (Bengali). If ElevenLabs fails (key, voice, model, credits) the app falls back to the Gemini voice.

## v2.15 (latency)
- Session start: microphone and speaker start while the network connects; the prompt and memory recall are built off the main thread in parallel (the main thread could be blocked up to 2 s before).
- One shared HTTP client for all Gemini sessions: reconnects reuse DNS and the TLS session.
- End of speech detected after 400 ms of silence (was 500). Audio threads run at audio priority. Offline re-check every 1 s (was 2 s).
- ElevenLabs: connection opened ahead of the first sentence, first words spoken at the first short clause.

## v2.16
- ElevenLabs: API key alone is enough (default female voice Sarah). Voice ID can be typed. Wrong voice/model retries with safe defaults. Real error is shown when it falls back to Gemini.

## v2.17
- Persona: adult woman (about 22), realistic, smarter reasoning rules. "Babu" is no longer a habit (at most about once in ten replies, never at the start of a reply).
- Emotions: persistent mood across turns; real fun/playful, sad, angry, jealous, sulky, worried. New set_emotion values: jealous, sulky, playful, worried (orb colors and ElevenLabs tags added).
- Per-model guidance for 3.8 Live, 3.8 Thinking and 3.1 Flash Live (Persona.modelBoost).
- Voice Pack: pitch slider 70-130 percent (Gemini and ElevenLabs), changes live. Low-latency audio path is used only at 100 percent.
- Permissions: "Allow" now requests every runtime permission the phone tools use (calendar, call log, camera, location, SMS, media, Bluetooth).
- NOT compiled or tested on a device: build it and send any `e:` lines.

## v2.21
Strict wake word + sleep mode. See README-v2.21.md.

## v2.22 - Google sign-in (Firebase) + per-account memory
- First screen is "Continue with Google" (account picker: every Google account on the phone or add another). App opens only after sign-in.
- Memory (ChatMemory) is separate per Google account; sign-out keeps it, another account starts fresh. Services use the cached account, nothing is saved while signed out.
- Sign out button in Preferences. Config: assets/firebase_config.json, see FIREBASE_SETUP.md.

## v2.23 - Guest mode + Firebase cloud memory
- "Continue as guest" on the login screen: no memory at all (nothing saved, nothing recalled). Sign in later from Preferences.
- Each Google account's conversations are also saved in Firestore (users/<uid>/memory); sign out/in, reinstall or a new phone brings them back. Another account stays separate.
- Clear memory also deletes the cloud copy. Needs Firestore rules from FIREBASE_SETUP.md.

## v2.24 - wake / sleep
- Sleep words: "Sleep Yasmin" / "Sleep Yasmin" (also "স্লিপ ইয়াসমিন/ইয়াসমিন") now work besides "Yasmin Sleep" / "Yasmin Chup Koro". Wake: "Hey Yasmin" / "Hey Yasmin" (and "Okay ...").
- Wake word and sleep mode is now ON by default (Preferences switch). Asleep = nothing is answered or sent to Gemini; only the exact wake phrase wakes it.

## v2.25 - three personality modes
- Care mode (default, the girlfriend persona with all feelings: love, anger, jealousy, sadness), Boss mode (FRIDAY-style: short, precise, no extras, calls him Boss), Friend mode (best friend with the same feelings, no romance).
- Pick in Preferences > Personality mode, or say "switch to boss / care / friend mode" (tool set_mode). A running chat reconnects with the new prompt; memory is kept.
- Tone changes with the mood as before (set_emotion); Boss mode is always calm.

## Fix: radio-static noise while YASMIN AI speaks
- ElevenTts: every audio chunk now holds whole 16-bit samples; an odd trailing byte of a sentence is dropped instead of shifting the next sentence by one byte (that shift = loud FM-style static).
- ElevenTts: a chunk taken just before cancel() (barge-in) is no longer played.
- AudioIO: speaker buffer 4x min / 800 ms capacity (jitter safety, no added delay); no time-stretch params forced on the low-latency track at normal pitch.
- AudioIO (Gemini voice): 150 ms jitter buffer at the start of every answer, 20 ms silence tail after it, normal (non low-latency) AudioTrack path, and underruns are written to the on-screen log ("speaker ran dry").

## Fix: "Yasmin sleep" only turned the screen off; loud noise bursts on Gemini 3.1
- AssistantService: if the model answers a "sleep" request with lock_screen, and the transcript has a sleep word but no lock / screen word, YASMIN AI goes to sleep and the lock is ignored.
- Tools prompt: "Yasmin sleep" is handled by the app; never lock the phone for it. More spellings of the sleep word accepted (স্লীপ, শ্লিপ).
- AudioIO: a loud, noise-like chunk of model audio (hiss instead of a voice) is replaced by silence and logged.

## New: Voice Changer (custom voice)
- Voice Pack page -> "Voice Changer": choose a voice sample (audio file), confirm permission, "Create custom voice". ElevenLabs Instant Voice Cloning builds the voice and YASMIN AI speaks with it at once.
- Several custom voices can be kept; tap one to use it, "Remove" deletes it (also from the ElevenLabs account), "Back to default voices" returns to the Gemini voices.
- Needs an ElevenLabs API key with "Voices: Write" and a plan that includes Instant Voice Cloning. New file: CustomVoices.kt.

## Fix: auto replies blocked by the privacy guard (PRIV_other_identity_full_len_3)
- Privacy: another chat's contact name that is a single word of up to 4 letters is no longer treated as a leak (everyday words like "bhai"/"mom"/"tom" blocked normal replies, and the retry failed the same way). First-name check now starts at 5 letters. Longer names and full names are still blocked.
- IncomingMessages: WhatsApp Channels / Status / broadcast notifications are ignored (one-way). Groups were already never answered.

## Fix: YASMIN AI did not really sleep / did not wake
- SleepMicListener: the microphone is opened with retries (up to ~4 s). Right after the assistant released it, the system could still report it busy; that counted as a fatal error and woke YASMIN AI up again immediately ("never sleeps").
- Sleep command is recognised more reliably: "Yasmin sleep", "go to sleep yasmin", "yasmin chup koro", "ইয়াসমিন ঘুমাও" or just "sleep" at the end of a SHORT utterance (max 6 words). Strict parser and its tests are unchanged; the tolerant check is WakeCommandParser.isSleepLoose.
- Wake: common spellings (neeshu, nisoo, ইয়াসমীন, hay/hi/হাই ...) accepted by WakeCommandParser.isWakeLoose; "nisha" is still NOT accepted.
- New SLEEP button on the main screen and a "Sleep" action in the notification: sleeps at once. While asleep nothing is answered, spoken or played; only "Hey Yasmin" wakes it.

## Faster wake-up (about 3 s)
- The wake listener closes an utterance after 0.3 s of quiet (was 0.6 s); the Google connection is opened in advance; a check times out after 5 s (was 12 s, which could block the next "Hey Yasmin"); the answer is limited to 48 tokens.
- Fastest of all: Preferences > Wake word training (offline, no internet, decided on the phone in a fraction of a second).

## Auto reply: only groups and channels are excluded; every direct chat is answered at once
- New ReplyPolicy.OPEN (ReplySettings.kt). The ONLY chats that are never answered: WhatsApp / Messenger groups, and WhatsApp channels / status / broadcast (one-way).
- Every direct chat (saved name or plain number, also a chat whose kind Android did not reveal) is answered: no 30 s owner wait, no "owner replied / uncertain / notification gone-after-owner-check" hold, no limit, no pause when Raj writes by hand. Many people at once are answered one after the other (2 s between sends).
- Privacy guard on the reply text now only blocks a leaked API key. Names / other chats' words no longer block.
- First start after this update sets Reply Delay = Instant and Reply Limit = Unlimited once (you can change them again in Settings). Raj's own switches (master switch, Pause, Stop, Block a person) still work.

- Build fix: a comment swallowed .build() in SleepMicListener (http client).

## v2.34
- Home character: the raised waving hand is gone, both arms now hang down (artwork `yasmin_character.webp` edited, wave animation removed).
- Home character: her face follows the AI's mood (`set_emotion`): happy, excited, calm, sad, love, surprised, angry, jealous, sulky, playful, worried.
  Eyebrows, eyelid droop/slant, mouth shape (smile, frown, gritted teeth, pout, cat smile, open), blush, heart eyes, sparkling eyes, tears,
  sweat drop, anger mark and gloom lines, all eased smoothly when the mood changes.
- Home character: new artwork (both arms down, supplied by the owner) replaces the edited one.
- Animation polish: hair swing now moves only real hair (a per-vertex hair mask), so jacket, arms and skirt no longer wobble; body sway, hair and ear-twitch amplitudes softened; lip-sync wobble is less mechanical; eyebrows slightly more transparent.
- Home: voice and model buttons are now two small round icon buttons in the top-right corner (v2.36).
- v2.37: background colour filter around the character: slowly drifts pink > lavender > sky > mint > peach > rose (mask asset yasmin_bg_mask.png moves with the character mesh).

## v2.38
- New Edge Glow (drawer > Edge Glow): a neon light around the screen edge while YASMIN AI is on, drawn as a system overlay so it keeps glowing over other apps / the home screen until switched off. Master toggle; mode Always on or Only while she speaks (fades in slowly and follows her voice); colour (rainbow, 8 colours, custom hue), light, thickness, rainbow speed; Test glow button. Needs 'Display over other apps'.

## v2.39
- Automation fix: YASMIN AI no longer sends several replies one after another. One reply per burst: it reads the person's message(s), sends ONE reply, then stays silent in that chat until the person writes something NEW.
  - An older message is skipped when a newer message of the same chat is already stored (the newer one answers everything).
  - A message that was already in the chat before the last reply was sent (or is on its way) gets no reply of its own; the same check runs again right before the send.
  - An old message shown again by the app (re-posted notification) is not treated as new.

## v2.40
- Sleep command fixed and made reliable: besides the transcript check ("Yasmin sleep", "Sleep Yasmin", "ইয়াসমিন স্লিপ", "ইয়াসমিন ঘুমাও", "yasmin ghumao", "go to sleep", "yasmin chup koro" ...), the model now has a go_to_sleep tool, so any wording it understands puts YASMIN AI to sleep (connection closed, only the wake word listens). "Hey Yasmin" wakes it again as before.
- Sleep check works on the end of a long transcript (only the last sentence counts, only name + sleep word + filler words), more spellings of the name (nisha, neshu ...) and sleep words (slip, ঘুমাও ...).
- Wake word accuracy: many more spellings of the greeting and name (hello, হ্যালো, ও ইয়াসমিন, neshu ...), an optional filler after it ("hey yasmin wake up"), a short pause between "Hey" and "Yasmin" no longer splits the phrase (0.44 s silence + the two sounds are joined within 3.5 s), and a sharper recogniser prompt.

## v2.41 (Gemini 3.1 Flash Live only)
- Language lock: the reply language follows the last full sentence of the user and stays pure (no Hindi words/sounds inside Bengali, no Bengali inside Hindi); fillers and interjections must belong to the language being spoken; one word or a noisy transcript never switches the language.
- Voice realism: a "voice acting" brief (breaths, pauses, soft laughs, sighs, stress, pitch, pace, whisper for love, matching his energy) so the mood is heard in the voice itself and matches set_emotion. Boss mode keeps it subtle.
- Both blocks are appended only for gemini-3.1-flash-live-preview (Persona.modelBoost); 3.8 Live / 3.8 Thinking are unchanged.
