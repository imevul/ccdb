package com.imevul.ccdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	void open() throws Exception {
		Database.get().open(tempDir);
		Database.get().create("items", Map.of(
			"name", "TEXT PRIMARY KEY",
			"count", "INTEGER NOT NULL DEFAULT 0"
		));
		Database.get().create("turtles", Map.of(
			"id", "TEXT PRIMARY KEY",
			"pose", "TEXT",
			"inventory", "TEXT"
		));
	}

	@AfterEach
	void close() {
		Database.get().close();
	}

	@Test
	void insertGetUpdateHelpers() {
		Database.get().insert("items", Map.of("name", "cobble", "count", 64));
		Map<String, Object> row = Database.get().get("items", Map.of("name", "cobble"));
		assertEquals(64, ((Number) row.get("count")).intValue());

		Database.get().upsert("items", Map.of("name", "cobble", "count", 80));
		assertEquals(80, ((Number) Database.get().get("items", Map.of("name", "cobble")).get("count")).intValue());

		Database.get().increment("items", "count", -1, Map.of("name", "cobble"));
		assertEquals(79, ((Number) Database.get().get("items", Map.of("name", "cobble")).get("count")).intValue());
		assertEquals(1L, Database.get().count("items", Map.of("name", "cobble")));
	}

	@Test
	void storesAndQueriesLuaTables() {
		Map<String, Object> pose = new LinkedHashMap<>();
		pose.put("x", 10);
		pose.put("y", 64);
		pose.put("z", -3);
		pose.put("facing", "north");
		List<Map<String, Object>> inventory = List.of(
			Map.of("name", "minecraft:cobblestone", "count", 64),
			Map.of("name", "minecraft:dirt", "count", 12)
		);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", "7");
		row.put("pose", pose);
		row.put("inventory", inventory);
		Database.get().insert("turtles", row);

		Map<String, Object> turtle = Database.get().get("turtles", Map.of("id", "7"));
		assertInstanceOf(Map.class, turtle.get("pose"));
		assertEquals("north", ((Map<?, ?>) turtle.get("pose")).get("facing"));
		assertInstanceOf(List.class, turtle.get("inventory"));
		assertEquals(64, ((Map<?, ?>) ((List<?>) turtle.get("inventory")).getFirst()).get("count"));

		List<Map<String, Object>> north = Database.get().find(
			"turtles",
			Map.of("pose", Map.of("facing", "north")),
			SqlBuilder.FindOptions.defaults()
		);
		assertEquals(1, north.size());

		Map<String, Object> at = new LinkedHashMap<>();
		at.put("x", 10);
		at.put("y", 64);
		at.put("z", -3);
		assertEquals("7", Database.get().get("turtles", Map.of("pose", at)).get("id"));
	}

	@Test
	void transactionRollback() {
		assertThrows(IllegalStateException.class, () -> Database.get().transaction(() -> {
			Database.get().insert("items", Map.of("name", "dirt", "count", 1));
			throw new IllegalStateException("boom");
		}));
		assertNull(Database.get().get("items", Map.of("name", "dirt")));
	}

	@Test
	void beginCommitAndRollback() {
		Database db = Database.get();
		db.bind(7);
		try {
			db.begin();
			db.insert("items", Map.of("name", "dirt", "count", 1));
			db.rollback();
			assertNull(db.get("items", Map.of("name", "dirt")));

			db.begin();
			db.insert("items", Map.of("name", "dirt", "count", 1));
			db.commit();
			assertEquals(1, ((Number) db.get("items", Map.of("name", "dirt")).get("count")).intValue());
		} finally {
			db.unbind();
		}
	}

	@Test
	void abortIfOwnerRollsBack() {
		Database db = Database.get();
		db.bind(3);
		try {
			db.begin();
			db.insert("items", Map.of("name", "sand", "count", 2));
		} finally {
			db.unbind();
		}
		db.abortIfOwner(3);
		assertNull(db.get("items", Map.of("name", "sand")));
	}

	@Test
	void concurrentIncrements() throws Exception {
		Database.get().insert("items", Map.of("name", "shared", "count", 0));
		int threads = 8;
		int perThread = 25;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger errors = new AtomicInteger();
		List<Future<?>> futures = new java.util.ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				try {
					start.await();
					for (int n = 0; n < perThread; n++) {
						Database.get().increment("items", "count", 1, Map.of("name", "shared"));
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				}
			}));
		}
		start.countDown();
		for (Future<?> future : futures) {
			future.get(15, TimeUnit.SECONDS);
		}
		pool.shutdownNow();
		assertEquals(0, errors.get());
		assertEquals(threads * perThread, ((Number) Database.get().get("items", Map.of("name", "shared")).get("count")).intValue());
	}

	@Test
	void rejectsUnsafeExec() {
		assertThrows(IllegalArgumentException.class, () -> Database.get().exec("PRAGMA journal_mode=DELETE", List.of()));
	}

	@Test
	void parameterizedQuery() {
		Database.get().insert("items", Map.of("name", "stone", "count", 3));
		List<Map<String, Object>> rows = Database.get().query("SELECT name FROM items WHERE count > ?", List.of(2));
		assertEquals(1, rows.size());
		assertEquals("stone", rows.getFirst().get("name"));
	}
}
