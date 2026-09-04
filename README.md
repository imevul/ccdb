# ccdb

A [CC: Tweaked](https://tweaked.cc/) API that gives every computer in a world one shared [SQLite](https://sqlite.org/) database.

Free to include in modpacks (MIT).

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- CC: Tweaked 1.116+ (tested with 1.120.0)

## Install

Put `ccdb-1.21.1-*.jar` in the `mods/` folder of the instance that loads the world (the server, or your client for singleplayer). Build with:

```bash
./gradlew build
```

The artifact is `build/libs/ccdb-1.21.1-<version>.jar`.

Players joining a server that already has ccdb do **not** need the jar. Install it on the client only if you want the API in singleplayer worlds.

The database file is created on first world load at:

```text
<world>/computercraft/ccdb/main.sqlite
```

It travels with world backups. Any computer in that world can read and write it.

## Lua API

Use `require("ccdb")` or the global `ccdb`.

### Helpers

```lua
local db = require("ccdb")

db.create("items", {
  name = "TEXT PRIMARY KEY",
  count = "INTEGER NOT NULL DEFAULT 0",
})

db.insert("items", { name = "cobble", count = 64 })
db.upsert("items", { name = "cobble", count = 80 })

local row = db.get("items", { name = "cobble" })
local rows = db.find("items", { count = 80 }, { limit = 10, order = "name" })
local n = db.count("items", { name = "cobble" })

db.update("items", { count = 79 }, { name = "cobble" })
db.increment("items", "count", -1, { name = "cobble" })
db.delete("items", { name = "dirt" })

db.transaction(function()
  db.increment("items", "count", -1, { name = "cobble" })
  db.insert("log", { msg = "took cobble" })
end)
```

`where` is AND of `column = value`. `update` / `delete` / `increment` require a non-empty where. `find` options: `limit`, `offset`, `order` (`"name"` or `"name DESC"`). Results are capped at 1000 rows.

### Lua tables as values

Nested tables are stored as JSON TEXT and decoded back on read. A table in `where` matches JSON fields (`json_extract`), not the whole blob.

```lua
db.create("turtles", {
  id = "TEXT PRIMARY KEY",
  pose = "TEXT",
  inventory = "TEXT",
})

db.insert("turtles", {
  id = "7",
  pose = { x = 10, y = 64, z = -3, facing = "north" },
  inventory = {
    { name = "minecraft:cobblestone", count = 64 },
    { name = "minecraft:dirt", count = 12 },
  },
})

local turtle = db.get("turtles", { id = "7" })
-- turtle.pose.facing == "north"

local north = db.find("turtles", { pose = { facing = "north" } })
```

Array-like tables become JSON arrays; map-like tables become objects. Mixed tables are rejected.

### Raw SQL

```lua
local custom = db.query("SELECT name FROM items WHERE count > ?", 10)
local result = db.exec("INSERT INTO items (name, count) VALUES (?, ?)", "stone", 3)
-- result = { changes = 1, last_insert_id = ... }
```

Use `?` placeholders. `ATTACH`, `PRAGMA`, `load_extension`, and multiple statements in one call are rejected.

## Concurrency

Computers share one connection. Writes are serialized with a lock. `transaction` is a Lua wrapper around `begin` / `commit` / `rollback` (`BEGIN IMMEDIATE`). Nested `transaction` calls join the same transaction. Do not yield while a transaction is open.

## License

[MIT](LICENSE.md). You may use, modify, redistribute, and include this mod in modpacks. The jar bundles sqlite-jdbc (Apache-2.0); see [NOTICE.md](NOTICE.md).
