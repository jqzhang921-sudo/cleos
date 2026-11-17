# 0.42.13 · Reading continuity and model-selected length

## Observed limits and scope

The former selector picked a book but could not choose length. `WorldReading.end` capped a segment at 3000 UTF-16 positions, often stopping at an earlier paragraph. There is no 11340, 20000 or 24000 total-progress ceiling. The shared five daily world opportunities and four-hour background cadence remain in place. This does not establish the cause of any particular device's week-long stall.

## Changes

- The existing study chooser may return optional `read_characters`, an integer from 1500 to 18000. The model can choose a short/long session or pause. Missing length retains the 3000 default for provider compatibility. No extra selector call, reading loop, or new global pace preference.
- The client chooses the actual end at a Unicode-safe paragraph/end boundary. Only a validated read acknowledgement commits that exact source range; pause, cancellation and invalid results do not invent progress. Outside summaries keep their existing segment size. A longer source segment costs more model input.
- Private chat and calls preload shelf facts only for a reading-related question, book title or short related follow-up. Background turns receive only reading within the last 24 hours that happened after the conversation's latest message. Old shelf state is not an instruction to report reading, and explicit `recall_world` remains available with existing permission and owner checks. No changes to old messages, recaps or memories.
- Busy study Workers retain their due ticket and use WorkManager backoff. A launch/pre-claim race schedules a replacement opportunity about 15 minutes later. Valid model pauses and ordinary failures still advance the usual cadence; no tight retry loop. Disabled/replaced tickets cannot be revived, and the durable advance-before-request replay guard remains.
- Diagnostics record only stages, busy deferrals and requested/actual lengths. No book titles, text, credentials or raw provider responses are added.

Database version 68 and backup format 32 remain unchanged. Existing book grouping, offsets, notes and local opt-ins are preserved. The bounds describe requested length; paragraph boundaries or remaining text can shorten it. Neither client acknowledgement checks nor synthetic tests can prove a model comprehended a passage. Android background constraints and a model's voluntary pauses can still delay completion.

## Verification

Unit tests cover variable lengths, invalid values, backwards-compatible selection, Unicode/paragraph endings beyond reported plateau offsets, relevant/private context, stale/future background activity, cancellation and existing ownership/backup safeguards. A debug-only emulator fixture uses an in-memory Room database, synthetic credential and an OkHttp interceptor: it exercises real selector/reader/library/scheduler code without external model requests or private data. Release evidence is stored separately under the task output directory.

## 0.42.18 · Invalid reading acknowledgement repair

Reading-result validation now runs inside the existing bounded structured-response request, as selection validation already did. Previously, valid JSON with a missing/wrong field or a nonmatching quote was rejected only after the request returned, so it bypassed the one compatibility retry. This was a client-side recovery gap; it does not establish the exact raw response produced by the reported GLM session.

One reading request may now make one extra JSON-only request after invalid acknowledgement/tool output. It receives the same source segment and safe category-specific guidance, never the failed output or reasoning. Schema fallback and repair share this single retry budget; network/auth/rate-limit/server failures do not acquire extra retries. The extra request can consume provider tokens, but is not another local reading opportunity.

Public notes/questions may use another language. An optional quote must still exactly match the current segment, including its language, punctuation and internal whitespace. The model can explicitly return an empty quote; the client never silently strips an invalid quote or infers read from a note. Explicit pause, cancellation, source/context changes and failed repair cannot commit new progress. Invalid results refund the local opportunity and retain prior notes/offsets. Final errors now distinguish field format, contradictory pause, excessive length and quote mismatch.

No provider-specific relaxation, database migration, new dependency, general chat/persona prompt change, daily quota or scheduling change is included. Synthetic protocol tests use the model label `glm-5.3-flash`; they do not call the live GLM service or prove comprehension.

## 2026-11-14 · 用户反馈暂时恢复，继续观察

用户反馈该读物问题“现在可能好了”，暂记为恢复待观察，不再要求反复重试。收到的原始截图均为 build=0.42.2：world_finish_reading 返回 HTTP 200 / tool_calls 后发生 stage=read reason=invalid_note；对应阅读请求 mcp=0。未取得同次新版失败记录，不能据此确认具体字段或宣称真实 GLM 已验收。复发时收集完整报错与匹配当时版本的「最近记录」，区分被保留的旧异常；保留原书、已确认进度和笔记。
