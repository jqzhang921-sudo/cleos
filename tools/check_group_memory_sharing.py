"""Exercise generated Room 43->44 migration and production memory-sharing SQL in SQLite.

Run :app:compileDebugKotlin first, then this script with Python 3. Uses synthetic,
in-memory data only; never opens the phone database or changes checked-in fixtures.
"""
import json
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/com.cleo.cleos.data.db.AppDatabase"
GENERATED = ROOT / "app/build/generated/ksp/debug/kotlin/com/cleo/cleos/data/db/AppDatabase_AutoMigration_43_44_Impl.kt"
if not GENERATED.is_file():
    raise SystemExit("Run :app:compileDebugKotlin first to generate the actual Room migration")

before = json.loads((SCHEMAS / "43.json").read_text(encoding="utf-8"))["database"]
after = json.loads((SCHEMAS / "44.json").read_text(encoding="utf-8"))["database"]
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys = ON")
for entity in before["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for index in entity.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

for owner in (10, 20):
    db.execute("INSERT INTO companions(id,name,persona,apiBaseUrl,apiModel,createdAt) VALUES(?,?,?,?,?,?)",
               (owner, "same-name", "synthetic", "https://example.invalid", "synthetic", 1))
for group in (1, 2):
    db.execute("INSERT INTO chat_groups(id,title,createdAt,updatedAt,nextSpeaker,draft,mentions) VALUES(?,?,?,?,?,?,?)",
               (group, "synthetic", 1, 1, 0, "DRAFT_PRESERVED", "[]"))
    for position, owner in enumerate((10, 20)):
        db.execute("INSERT INTO group_members(groupId,companionId,position,name) VALUES(?,?,?,?)",
                   (group, owner, position, "same-name"))
for ident, owner in ((101, 10), (102, 10), (201, 20)):
    db.execute("INSERT INTO memories(id,companionId,kind,name,summary,details,pinned,source,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?)",
               (ident, owner, "rapport", "synthetic", "PRIVATE_" + str(ident), "[]", 0, "me", 1, 1))

snapshots = {table: db.execute(f"SELECT * FROM {table} ORDER BY 1,2").fetchall()
             for table in ("chat_groups", "memories")}
migration = re.findall(r'connection\.execSQL\("([^"\n]+)"\)', GENERATED.read_text(encoding="utf-8"))
assert len(migration) == 2 and all(sql.startswith("ALTER TABLE `group_members` ADD COLUMN") for sql in migration)
for sql in migration:
    db.execute(sql)
for table, rows in snapshots.items():
    assert rows == db.execute(f"SELECT * FROM {table} ORDER BY 1,2").fetchall(), table
assert db.execute("SELECT sharePrivateMemories,privateMemoryGrants FROM group_members").fetchall() == [(0, "[]")] * 4

columns = db.execute("PRAGMA table_info(group_members)").fetchall()
expected = next(e for e in after["entities"] if e["tableName"] == "group_members")["fields"]
assert [(r[1], r[2], bool(r[3])) for r in columns] == [(f["columnName"], f["affinity"], f.get("notNull", False)) for f in expected]


def query(path, method):
    source = (ROOT / path).read_text(encoding="utf-8")
    found = re.search(r'@Query\("([^"\n]+)"\)\s*suspend fun ' + method + r'\(', source)
    assert found, method
    return found.group(1)


update = query("app/src/main/java/com/cleo/cleos/data/db/GroupDao.kt", "memorySharing")
select = query("app/src/main/java/com/cleo/cleos/data/db/Daos.kt", "selectedFor")
params = dict(groupId=1, companionId=10, enabled=True, grants='[{"id":101,"fingerprint":"' + "a" * 64 + '"}]')
assert db.execute(update, params).rowcount == 1
assert db.execute("SELECT sharePrivateMemories FROM group_members WHERE groupId=2 AND companionId=10").fetchone() == (0,)
assert db.execute("SELECT sharePrivateMemories FROM group_members WHERE groupId=1 AND companionId=20").fetchone() == (0,)
assert db.execute(update, dict(params, groupId=99)).rowcount == 0
db.execute("UPDATE group_members SET active=0 WHERE groupId=2 AND companionId=10")
assert db.execute(update, dict(params, groupId=2)).rowcount == 0
assert db.execute(select.replace(":ids", ":id0,:id1"), dict(companionId=10, id0=101, id1=201)).fetchall()[0][0] == 101
assert len(db.execute(select.replace(":ids", ":id0,:id1"), dict(companionId=10, id0=101, id1=201)).fetchall()) == 1
assert db.execute(select.replace(":ids", "NULL"), dict(companionId=10)).fetchall() == []
assert db.execute(select, dict(companionId=20, ids=101)).fetchall() == []
assert db.execute(select, dict(companionId=10, ids=201)).fetchall() == []
assert db.execute(update, dict(params, enabled=False, grants="[]")).rowcount == 1
assert db.execute("SELECT sharePrivateMemories,privateMemoryGrants FROM group_members WHERE groupId=1 AND companionId=10").fetchone() == (0, "[]")
db.execute("DELETE FROM memories WHERE id=101")
assert db.execute(select, dict(companionId=10, ids=101)).fetchall() == []
assert db.execute("PRAGMA foreign_key_check").fetchall() == []
db.close()
print("PASS: generated Room 43->44 migration preserves data and defaults to closed; production DAO SQL scopes owner, group, membership and revocation")
