"""After assembleRelease, verify the published v25 schema can upgrade to v31.

Uses Room-generated SQL and synthetic records in memory, never a user's database.
"""
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
schemas = root / "app/schemas/com.cleo.cleos.data.db.AppDatabase"
generated = root / "app/build/generated/ksp/release/kotlin/com/cleo/cleos/data/db"


def create(version):
    schema = json.loads((schemas / f"{version}.json").read_text(encoding="utf-8"))["database"]
    connection = sqlite3.connect(":memory:")
    connection.execute("PRAGMA foreign_keys=ON")
    for entity in schema["entities"]:
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    return connection, schema


def columns(connection, table):
    result = {}
    for _, name, kind, required, default, primary in connection.execute(f"PRAGMA table_info(`{table}`)"):
        # For nullable columns, DEFAULT NULL and no default have identical SQL behaviour.
        if not required and default is not None and default.strip("() ").upper() == "NULL":
            default = None
        result[name] = (kind, required, default, primary)
    return result


db, old = create(25)
db.execute("INSERT INTO companions (id,name,persona,apiBaseUrl,apiModel,createdAt) VALUES (7,'old TA','persona','address','model',1)")
db.execute("INSERT INTO conversations (id,title,createdAt,updatedAt,companionId) VALUES (9,'old chat',1,2,7)")
db.execute("INSERT INTO messages (id,conversationId,role,content,createdAt) VALUES (11,9,'user','keep this message',1)")
db.execute("INSERT INTO diary_entries (id,day,title,blocks,createdAt,updatedAt,secret) VALUES (13,1,'private entry','[]',1,2,1)")
snapshots = {
    entity["tableName"]: db.execute(f'SELECT * FROM `{entity["tableName"]}`').fetchall()
    for entity in old["entities"]
}

for version in range(25, 31):
    source = (generated / f"AppDatabase_AutoMigration_{version}_{version + 1}_Impl.kt").read_text(encoding="utf-8")
    statements = re.findall(r'connection\.execSQL\("([^"\n]+)"\)', source)
    assert statements, f"No generated SQL for {version}->{version + 1}"
    for sql in statements:
        db.execute(sql)

fresh, new = create(31)
tables = lambda connection: {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
assert tables(db) == tables(fresh)
for entity in new["entities"]:
    table = entity["tableName"]
    indices = lambda connection: {row[1]: row[2:] for row in connection.execute(f"PRAGMA index_list(`{table}`)")}
    actual, expected = columns(db, table), columns(fresh, table)
    differences = {name: (actual.get(name), expected.get(name)) for name in actual.keys() | expected.keys() if actual.get(name) != expected.get(name)}
    assert not differences, f"Columns differ: {table}: {differences}"
    assert indices(db) == indices(fresh), f"Indices differ: {table}"
    assert db.execute(f"PRAGMA foreign_key_list(`{table}`)").fetchall() == fresh.execute(f"PRAGMA foreign_key_list(`{table}`)").fetchall(), table

for entity in old["entities"]:
    table = entity["tableName"]
    names = ",".join(f'`{field["columnName"]}`' for field in entity["fields"])
    assert db.execute(f"SELECT {names} FROM `{table}`").fetchall() == snapshots[table], f"Old records changed: {table}"

assert db.execute("PRAGMA foreign_key_check").fetchall() == []
assert db.execute("SELECT feedVisitEnabled,feedVisitPosts,feedVisitNews FROM companions WHERE id=7").fetchone() == (0, 0, 0)
assert db.execute("SELECT feedShare FROM messages WHERE id=11").fetchone() == (None,)
print("PASS: v25->v31 generated migration chain; old chat, TA and private diary preserved; schema/indices/foreign keys match; automatic feed defaults off")
