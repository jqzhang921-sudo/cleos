"""Validate the real read-only feed query against synthetic records in SQLite, never phone data."""
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "app/schemas/com.cleo.cleos.data.db.AppDatabase/31.json").read_text(encoding="utf-8"))["database"]
db = sqlite3.connect(":memory:")
for entity in schema["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
dao = (root / "app/src/main/java/com/cleo/cleos/data/db/FeedDao.kt").read_text(encoding="utf-8")
query = re.search(r'@Query\("""(SELECT \* FROM feed_posts.*?)"""\)\s+suspend fun search', dao, re.S).group(1)
for row in [
    (1, 0, "午后散步", 10, "moments", None, None),
    (2, 7, "自己的帖子", 20, "moments", None, None),
    (3, 8, "另一个 TA", 30, "moments", None, None),
    (4, 0, "猫猫话题", 40, "topic", None, "Cats"),
    (5, 0, "老版本资讯", 40, "moments", "https://example.com", "Older Cats"),
    (6, 0, "100%_真实", 50, "moments", None, None),
]:
    db.execute("INSERT INTO feed_posts (id,authorId,content,createdAt,kind,sourceUrl,sourceTitle,liked) VALUES (?,?,?,?,?,?,?,0)", row)

def ids(authorId=0, kind="all", query_text="", limit=6, offset=0):
    return [row[0] for row in db.execute(query, dict(authorId=authorId, kind=kind, query=query_text, limit=limit, offset=offset))]

assert ids() == [6, 5, 4, 1]
assert ids(authorId=7) == [2]
assert ids(authorId=None) == [6, 5, 4, 3, 2, 1]
assert ids(kind="topic") == [5, 4]  # Legacy sourced posts are topics too.
assert ids(kind="moments") == [6, 1]
assert ids(query_text="cats") == [5, 4]
assert ids(query_text="%_") == [6]  # Search treats SQL wildcard characters literally.
assert ids(limit=2, offset=1) == [5, 4]
assert ids(query_text="不存在") == []
assert db.execute("SELECT count(*) FROM feed_posts").fetchone()[0] == 6
print("PASS: actual feed read SQL filters, literal search, stable ordering, pagination and read-only behavior")
