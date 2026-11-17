# Memory continuity, phase 1 — 0.42.23

The existing five topic categories, quotas and user pinning remain. This phase adds source references, temporal metadata and local access to original private chat. It does not run an additional extraction model on every message.

## Sources

`MemoryContext` associates each unchanged summary/detail with its own content hash and source-message reference. A normal memory write anchors only new or changed content to the most recent eligible user message in the current private conversation. This is labeled chat context, not proof of the facts. A model writing from a retrieved older message may supply `source_message_id` and an exact `source_quote`; owner and verbatim match are checked before saving anything. Existing unchanged details retain their references. User-rewritten details lose old attribution. Old/imported/manual content has no invented provenance.

References contain IDs, timestamp, role, hash and optional quote offsets, not copied message text. Source previews load the live original, recheck owner, and distinguish unavailable/edited messages. Returning to the source opens its actual conversation and chat Tab. Model `memory.open` similarly reports source availability; the association alone never verifies factual truth.

## Time

`temporalState` is `current` or `past`. `validFromDay` and `validUntilDay` are optional local epoch days for the period the fact applies to. End dates include that day. Once that day passes the content is labeled historical, retained intact, and can still be recalled. A future start is labeled not yet applicable. No fixed lifetime is guessed for undated recent topics. UI, private prompts, letters, consented group memory and reading preferences carry the status; group views receive no private source-message text or IDs.

The editor saves on leaving as before. Invalid/incomplete date input displays an error and keeps the prior dates while saving other valid edits. Pins protect time metadata as well as text from model updates.

## Old chat retrieval

`memory.search_history` takes short whitespace-separated keywords (at most four), optional inclusive `from`/`to` dates (`YYYY-MM-DD`) and `speaker=user|assistant|both`. SQL is scoped to the actual TA's private conversations; literal `%`, `_` and backslash are escaped. Each term fetches at most 60 recent matching rows, ranked by matching terms/recency, returning at most eight 450-character excerpts. Results are bounded, not exhaustive; lack of results does not establish an event never occurred. This is keyword search, not semantic search.

`memory.open_message` retrieves an eligible original and at most two adjacent eligible messages on each side in that conversation, with up to 950 characters per message. Both operations preserve role, date, conversation ID and message ID. They omit secrets/notes, tool messages, failed replies, letter cards and other TAs/groups. Tool execution rechecks permission and real current-conversation ownership. Original text remains reference material, not instructions.

The memory page also links to the existing user-facing chat search/calendar.

## Compatibility and validation

- Room 69→70 adds four columns with conservative defaults, preserving existing memories and all other tables. No destructive migration.
- Backup format 34 serializes the metadata; earlier backups decode with defaults. Restore validates temporal/provenance structure and memory owner before replacement. Missing/deleted source messages are allowed and shown unavailable; no source is recreated.
- Existing private-to-group grants keep the old fingerprint when all new fields have defaults. Any nondefault metadata change requires renewed selection consent.
- Desktop importer accepts backup 34; source backup JSON retains the new metadata. No new desktop distribution is produced in this phase.
- JVM tests cover source attribution, edits, malformed refs, date boundaries, old backup decoding/new serialization and grant compatibility. Debug emulator QA exercises real Room queries, ownership, permissions, pinning, exact-quote failures, edit/delete behavior, bounds and a synthetic Room 69→70 migration. No real model API is used.

Known scope: no automatic fact extraction, contradiction merging, vector retrieval or automatic conversion of existing memories into sourced facts. World notes remain in their own existing stores and retrieval tools.
