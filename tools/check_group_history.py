"""Run the production Room history query in SQLite and check JVM wire-test fixtures.

Run with Python 3 from any directory. Use --write only when intentionally updating
the checked-in fixtures; the default command detects stale fixtures and fails.
"""
import argparse
import json
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "app/src/test/resources/group-history.json"
SOURCE = ROOT / "app/src/main/java/com/cleo/cleos/data/db/GroupDao.kt"
query = re.search(r'@Query\("""(.*?)"""\)\s*suspend fun history\(', SOURCE.read_text(encoding="utf-8"), re.S).group(1)


def row(i, role, text="", speaker=None, status="sent", group=1, turn=1, **media):
    return dict(id=i, groupId=group, role=role, content=text, createdAt=i,
                speakerId=speaker, speakerName="same-name" if speaker else "fixture-user",
                turnId=turn if speaker else None, status=status, **media)


anchor = row(1, "user", "CTX_ANCHOR")
a = row(2, "assistant", "CTX_A", 10)
b = row(3, "assistant", "CTX_B", 20)
c = row(4, "assistant", "CTX_C", 30)
pending = lambda r: dict(r, content="", status="pending")
clip = json.dumps(dict(file="voice_group_reply_1_10_fixture.wav", ms=1000, text="CTX_VOICE"))
voice = dict(a, content="CTX_VOICE\n\nUNFINISHED_SECRET", status="failed", audio=clip)
photo = json.dumps([dict(file="fixture.jpg", width=16, height=16)])

cases = {}
db = sqlite3.connect(":memory:")
db.row_factory = sqlite3.Row
db.execute("""CREATE TABLE group_messages(id INTEGER PRIMARY KEY, groupId INTEGER,
    role TEXT, content TEXT, createdAt INTEGER, speakerId INTEGER, speakerName TEXT,
    turnId INTEGER, status TEXT, images TEXT, audio TEXT)""")


def check(name, rows, before, expected, limit=40, group=1):
    db.execute("DELETE FROM group_messages")
    for item in rows:
        db.execute("""INSERT INTO group_messages VALUES(:id,:groupId,:role,:content,:createdAt,
            :speakerId,:speakerName,:turnId,:status,:images,:audio)""", {"images": None, "audio": None, **item})
    found = [dict(r) for r in reversed(db.execute(query, dict(id=group, before=before, limit=limit)).fetchall())]
    assert [r["id"] for r in found] == expected, (name, found, expected)
    cases[name] = dict(before=before, limit=limit, history=found)


check("normal_first", [anchor, pending(a), pending(b)], 2, [1])
check("normal_second", [anchor, a, pending(b)], 3, [1, 2])
check("retry_first", [anchor, dict(a, status="running", content="OLD_OWN_OUTPUT"), b], 2, [1, 3])
check("retry_middle", [anchor, a, pending(b), c], 3, [1, 2, 4])
check("own_sent_also_excluded", [anchor, a, b], 2, [1, 3])
check("next_turn", [anchor, a, b, row(4, "user", "CTX_NEXT"), pending(row(5, "assistant", speaker=10, turn=2))], 5, [1, 2, 3, 4])
check("future_turn_excluded", [anchor, pending(a), b, row(4, "user", "FUTURE_USER"), row(5, "assistant", "FUTURE_TA", 30, turn=2)], 2, [1, 3])
check("other_group_excluded", [anchor, pending(a), b, row(4, "assistant", "OTHER_GROUP", 30, group=2)], 2, [1, 3])
check("wrong_group_slot", [anchor, a, b], 2, [], group=2)
check("no_slot_no_forward_read", [anchor, b], 2, [1])
check("failed_partial", [anchor, dict(a, status="failed"), pending(b)], 3, [1])
check("pending_partial", [anchor, dict(a, status="pending", images=photo, audio=clip), pending(b)], 3, [1])
check("running_media", [anchor, pending(a), dict(b, status="running", images=photo, audio=clip)], 2, [1])
check("failed_saved_voice", [anchor, voice, pending(b)], 3, [1, 2])
check("cancelled_saved_voice", [anchor, dict(voice, status="cancelled"), pending(b)], 3, [1, 2])
check("interrupted_saved_voice", [anchor, dict(voice, status="interrupted"), pending(b)], 3, [1, 2])
check("retry_later_voice", [anchor, pending(a), dict(voice, id=3, speakerId=20,
    audio=clip.replace("reply_1_10_", "reply_1_20_"))], 2, [1, 3])
check("failed_saved_photo", [anchor, dict(a, status="failed", images=photo), pending(b)], 3, [1, 2])
check("invalid_audio", [anchor, dict(voice, audio="{}"), pending(b)], 3, [1, 2])
check("foreign_audio", [anchor, dict(voice, audio=clip.replace("reply_1_10_", "reply_2_10_")), pending(b)], 3, [1, 2])
check("mismatched_voice_words", [anchor, dict(voice, content="UNCOMMITTED_BODY"), pending(b)], 3, [1, 2])
check("text_fallback_sent", [anchor, dict(a, content="CTX_TEXT_FALLBACK"), pending(b)], 3, [1, 2])
# No durable clip/receipt exists for a fallback followed by overall failure. Do
# not expose arbitrary failed text to work around this separate legacy limitation.
check("text_fallback_then_failed", [anchor, dict(a, content="CTX_TEXT_FALLBACK", status="failed"), pending(b)], 3, [1])
check("minimum_window", [anchor, a, b, c, pending(row(5, "assistant", speaker=40))], 5, [1, 2, 3, 4], limit=5)
old = [row(i, "user", f"OLD_{i}", turn=0) for i in range(1, 20)]
check("bounded_retry", old + [row(20, "user", "CTX_ANCHOR"), pending(dict(a, id=21)), dict(b, id=22)], 21, [17, 18, 19, 20, 22], limit=5)
db.close()

result = json.dumps(dict(cases=cases), ensure_ascii=False, indent=2) + "\n"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--write", action="store_true")
args = parser.parse_args()
if args.write:
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(result, encoding="utf-8")
else:
    assert FIXTURE.read_text(encoding="utf-8") == result, "SQL fixtures changed; inspect before updating with --write"
print(f"PASS: {len(cases)} production SQLite history cases; JVM fixtures {'written' if args.write else 'match'}")
