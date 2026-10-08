"""Run after assembleRelease: check real Room migration and DAO SQL in an in-memory SQLite DB."""
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
schemas = root / "app/schemas/com.cleo.cleos.data.db.AppDatabase"
old = json.loads((schemas / "30.json").read_text(encoding="utf-8"))["database"]
new = json.loads((schemas / "31.json").read_text(encoding="utf-8"))["database"]
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for entity in old["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
db.execute("INSERT INTO companions (id,name,persona,apiBaseUrl,apiModel,createdAt) VALUES (7,'old TA','persona','address','model',1)")
db.execute("INSERT INTO feed_posts (id,authorId,content,createdAt,liked) VALUES (1,7,'keep this post',1,0)")
migration = (root / "app/build/generated/ksp/release/kotlin/com/cleo/cleos/data/db/AppDatabase_AutoMigration_30_31_Impl.kt").read_text(encoding="utf-8")
for sql in re.findall(r'connection\.execSQL\("([^"\n]+)"\)', migration):
    db.execute(sql)
assert db.execute("SELECT feedVisitEnabled,feedVisitPosts,feedVisitNews,feedVisitQuietOn,feedVisitQuietStart,feedVisitQuietEnd FROM companions").fetchone() == (0,0,0,1,1380,480)
assert db.execute("SELECT content FROM feed_posts").fetchone() == ("keep this post",)
# Compare actual migrated column definitions against a freshly created v31 database.
fresh = sqlite3.connect(":memory:")
for entity in new["entities"]:
    fresh.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
for table in ("companions", "feed_visits"):
    columns = lambda conn: {row[1]: row[2:] for row in conn.execute(f"PRAGMA table_info({table})")}
    assert columns(db) == columns(fresh), table
dao = (root / "app/src/main/java/com/cleo/cleos/data/db/Daos.kt").read_text(encoding="utf-8").split("interface FeedVisitDao {", 1)[1].split("@Dao", 1)[0]
queries = dict((name, sql) for sql, name in re.findall(r'@Query\("([^"\n]+)"\)\s+suspend fun (\w+)', dao))
db.execute("INSERT INTO feed_visits VALUES (7,100,NULL,0,0)")
def claim(expected, next_at, day=1, maximum=2):
    return db.execute(queries["claim"], dict(id=7, expected=expected, next=next_at, day=day, maximum=maximum)).rowcount
def posted(expected, day=1):
    return db.execute(queries["posted"], dict(id=7, expected=expected, day=day)).rowcount
assert claim(100,200) == 1
assert claim(100,300) == 0  # duplicate worker claim
assert posted(200) == 1
assert posted(200) == 0  # at most one automatic post/day
assert posted(999) == 0  # stale generation
assert claim(200,300) == 1
assert claim(300,400) == 0  # daily opportunity cap
# Settings/enable changes only replace nextAt; quota survives them.
assert db.execute(queries["move"], dict(id=7,expected=300,next=500)).rowcount == 1
assert claim(500,600) == 0
assert db.execute("SELECT attempts,posts FROM feed_visits").fetchone() == (2,1)
assert claim(500,600,day=2) == 1
assert db.execute("SELECT attempts,posts FROM feed_visits").fetchone() == (1,0)
assert posted(600,day=1) == 0
assert posted(600,day=2) == 1
db.execute("DELETE FROM companions WHERE id=7")
assert db.execute("SELECT count(*) FROM feed_visits").fetchone() == (0,)
print("PASS: v30->v31 defaults, preserved posts, schema, stale/double claims, daily quotas, settings changes, next day, TA cascade")
