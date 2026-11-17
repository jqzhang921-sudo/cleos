# Memory maintenance, phase 2 — 0.42.24

## Normal writing

`MemoryBook` now skips exact normalized duplicate topics/details. Normalization folds Unicode compatibility forms, case and repeated whitespace, but does not remove punctuation, numbers or negation. Same-category/same-name topics with a changed summary or time period require opening the existing topic instead of creating another contradictory copy. New distinct details can be appended to the existing topic, with original facts and provenance retained. Detail overflow is rejected instead of silently truncating the list.

`MemoryStore` is the normal production chat/editor mutation path. It compares complete before-snapshots inside the Room transaction, checks owner, pins, quotas and forgetting rules, and records bounded private history. `memory.open` returns a revision digest. Destructive updates (summary/category/name/time changes or removed details) require that current revision. Old model output cannot overwrite a newer user edit. Undo also advances the revision timestamp, so restoring old content does not reactivate an old model token or private-to-group grant.

A correction may apply immediately when new text is copied verbatim from a live, exact-quote-checked, sufficiently recent user message, with category/name/time unchanged. This verifies attribution, not semantic truth. Other destructive writes become pending suggestions. Their tool result explicitly says the memory is unchanged; ordinary responses need not pressure the user to process them. Pending duplicates are collapsed despite different proposal timestamps. Additions and safe duplicate handling need no extra model request.

## User maintenance and review

`记忆 → 整理与改动` contains:

- Local checking: same-name/same-category candidates. Equal summaries and time periods can produce merge previews; differing statements, periods or pins produce review notes.
- Optional model assistance: a user-requested call to this TA's current model, disclosed in the UI. Sends only this TA's bounded memory directory and selected complete details (not chats or other TAs). At most 100 topics, an 8,000-character shared detail budget and a 40,000-character total input limit. Missing details are labeled. No call runs periodically or on every chat turn.
- At most five model suggestions, each referencing 2–5 existing IDs. Only `merge` and `review` are accepted. Model output never directly changes facts. Ownership and full snapshots are checked again after the request.
- A before/after dialog to adopt, ignore or inspect existing topics; an applied event can be undone while its after-state is still current.

Merging keeps the first topic's title, summary and temporal fields. It unions all original complete details and other distinct summaries, preserving provenance even when a former summary becomes a detail. Different owners/categories/time periods, pinned topics or over-30-detail merges are rejected. A semantic contradiction is flagged for the user to inspect; there is no automatic guess about which statement is correct.

The editor saves through the same transactional store before leaving. A concurrent conflict stays visible with a discard option rather than silently overwriting the newer revision. Manual edits are also recorded and reversible. The source-message jump saves the editor first.

## Forgetting

Explicit deletion/`memory.forget` removes the selected topic, plus connected change snapshots (including histories sharing exact normalized facts). Such changes cannot be resurrected through the history undo UI. The store retains only SHA-256 digests of normalized facts to prevent identical automatic re-creation, including under a different title. These digests are private per TA. A user manually re-adding a fact releases the corresponding exact digest as an explicit choice.

This is scoped to the memory library. It does not delete original chats, recaps, exported backups or world notes, and hash matching does not detect semantic paraphrases. Existing/imported memories are not automatically batch rewritten or retroactively attributed. Explicit imports keep their existing import behavior; they are not logged as normal chat/editor changes.

## Storage and acceptance

- Room 70→71 adds `memory_changes` and `memory_blocks`, with TA ownership and cascade cleanup. No existing rows are rewritten. Original 69→70 migration remains in the chain.
- History retains at most 100 events per TA; it is never included in model chat context or group memory. Group access still requires consent to the selected current revision.
- Backup 35 includes history and hash rules, validates ownership/state/snapshots and restores conservative defaults for older files. Desktop importer accepts the version and retains original backup JSON; no desktop distribution is built here.
- JVM coverage includes normalization, negation/numeric distinctions, destructive edits, merge preservation/provenance, overflow/pins/time separation, bounded model input, strict output IDs and serialization.
- Emulator QA uses isolated Room, synthetic source messages and intercepted model output. It checks duplicate suppression, pending/current version handling, adoption/undo, concurrent corrections, exact quoted corrections, forget/history purge, hash-only rules, explicit re-add, merges, TA isolation, bounded history, backup validation and actual 70→71 migration. Live TA model behavior remains a separate observation after delivery.
