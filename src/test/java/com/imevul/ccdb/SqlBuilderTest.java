package com.imevul.ccdb;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlBuilderTest {
	@Test
	void createAndInsert() {
		Map<String, String> columns = new LinkedHashMap<>();
		columns.put("name", "TEXT PRIMARY KEY");
		columns.put("count", "INTEGER NOT NULL DEFAULT 0");
		SqlBuilder.BuiltSql create = SqlBuilder.create("items", columns);
		assertEquals(
			"CREATE TABLE IF NOT EXISTS \"items\" (\"name\" TEXT PRIMARY KEY, \"count\" INTEGER NOT NULL DEFAULT 0)",
			create.sql()
		);

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", "cobble");
		row.put("count", 64);
		SqlBuilder.BuiltSql insert = SqlBuilder.insert("items", row);
		assertEquals("INSERT INTO \"items\" (\"name\", \"count\") VALUES (?, ?)", insert.sql());
		assertEquals(List.of("cobble", 64), insert.params());
	}

	@Test
	void tableWhereUsesJsonExtract() {
		Map<String, Object> pose = new LinkedHashMap<>();
		pose.put("facing", "north");
		pose.put("x", 10);
		Map<String, Object> where = new LinkedHashMap<>();
		where.put("pose", pose);
		SqlBuilder.BuiltSql sql = SqlBuilder.select("turtles", where, SqlBuilder.FindOptions.defaults(), false);
		assertTrue(sql.sql().contains("json_extract(\"pose\", '$.facing') = ?"));
		assertTrue(sql.sql().contains("json_extract(\"pose\", '$.x') = ?"));
		assertEquals(List.of("north", 10), sql.params());
		assertTrue(sql.sql().contains("LIMIT 1000"));
	}

	@Test
	void nestedJsonWhere() {
		Map<String, Object> loc = new LinkedHashMap<>();
		loc.put("x", 1);
		Map<String, Object> pose = new LinkedHashMap<>();
		pose.put("loc", loc);
		SqlBuilder.BuiltSql sql = SqlBuilder.select("turtles", Map.of("pose", pose), SqlBuilder.FindOptions.defaults(), false);
		assertTrue(sql.sql().contains("json_extract(\"pose\", '$.loc.x') = ?"));
		assertEquals(List.of(1), sql.params());
	}

	@Test
	void rejectsBadIdentifiersAndTypes() {
		assertThrows(IllegalArgumentException.class, () -> SqlBuilder.ident("items; DROP"));
		assertThrows(IllegalArgumentException.class, () -> SqlBuilder.validateType("TEXT (PRIMARY KEY)"));
		assertThrows(IllegalArgumentException.class, () -> SqlBuilder.validateType("TEXT; ATTACH"));
		assertThrows(IllegalArgumentException.class, () -> SqlBuilder.update("items", Map.of("count", 1), Map.of()));
		assertThrows(IllegalArgumentException.class, () -> SqlBuilder.delete("items", Map.of()));
	}

	@Test
	void incrementAndFindOptions() {
		SqlBuilder.BuiltSql inc = SqlBuilder.increment("items", "count", -1, Map.of("name", "cobble"));
		assertEquals("UPDATE \"items\" SET \"count\" = \"count\" + ? WHERE \"name\" = ?", inc.sql());
		assertEquals(List.of(-1, "cobble"), inc.params());

		SqlBuilder.FindOptions options = SqlBuilder.parseOptions(Map.of("limit", 10, "order", "name DESC"));
		SqlBuilder.BuiltSql find = SqlBuilder.select("items", Map.of(), options, false);
		assertTrue(find.sql().contains("ORDER BY \"name\" DESC"));
		assertTrue(find.sql().contains("LIMIT 10"));
	}

	@Test
	void encodesTableColumnValues() {
		Map<String, Object> pose = new LinkedHashMap<>();
		pose.put("facing", "north");
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", "7");
		row.put("pose", pose);
		SqlBuilder.BuiltSql insert = SqlBuilder.insert("turtles", row);
		assertEquals(List.of("7", "{\"facing\":\"north\"}"), insert.params());
	}
}
