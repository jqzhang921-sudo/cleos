# API Key draft inspection

0.42.12 adds a common `SecretKeyField` to private/chat and spoken-model connection settings, speech-to-text, and text-to-speech. It receives only the unsaved draft and a boolean saying whether a saved key exists. It never fetches or displays an encrypted stored credential.

- Drafts start masked. A deliberate eye action reveals the currently entered value; another action masks it. Revealed state is not saved across screen/process recreation.
- Activity pause, endpoint/service change and clearing the draft reset visibility. The existing successful-save paths clear the input: `EndpointFields.activate/load`, `saveVoiceKey` and `saveSpeechKey`. When empty, reveal is disabled, including when an encrypted key already exists.
- The input shows Unicode character count and advisory warnings for ellipsis, whitespace, controls or format characters. No provider prefix or length is assumed, no content is rewritten, and warnings do not reject input. A valid-looking length cannot prove the key is complete or valid; use the existing connection test to verify service acceptance.
- Character inspection returns metadata only. No key, fragment, clipboard contents or revealed-state value is added to diagnostics, backups, settings or logging. Keyboard remains a password keyboard without autocorrect even when the user reveals the draft.
- This change does not alter credential encryption, endpoint association, connection testing or save behavior. It does not expose existing MCP tokens or other stored credentials.

Acceptance uses only synthetic strings in the debug-only, emulator-guarded `SecretKeyQaActivity`; no real credentials are inspected or written and no model API is called. Evidence is under `outputs/Cleos-0.42.12-key-draft-preview` in the conversation workspace.

2026-11-09 validation: 1564 Android JVM tests, debug/release builds and release lint passed. Nine isolated native checks cover explicit reveal/mask, draft preservation across pause with visibility reset, endpoint reset, clear-after-save behavior, disabled reveal with empty saved state, new masked draft, advisory warnings and 145% font on a small display. The original 46 tables / 1 row and preference files were restored exactly; no test credential was stored. The emulator and Gradle daemon are closed.

The formal-signer APK is 0.42.12 / code 292, SHA-256 `D836391AF5DBA7FE71E2BF9A9D5B9C3D3EFBBDED5F073EBE0698BD3237F9313E`. Production manifest excludes QA entry points. The original endpoint went offline; deployment subsequently completed through the user's new endpoint after device, readiness and same-signer checks. Readback matched the candidate APK, and original first-install time remained unchanged. No saved phone credential was inspected, replaced or deleted, no permission was enabled, and the app was not opened by the installer.
