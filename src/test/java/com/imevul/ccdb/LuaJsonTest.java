package com.imevul.ccdb;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaJsonTest {
	@Test
	void encodesObjectAndArray() {
		Map<String, Object> pose = new LinkedHashMap<>();
		pose.put("x", 10);
		pose.put("facing", "north");
		assertEquals("{\"x\":10,\"facing\":\"north\"}", LuaJson.encode(pose));

		Map<Object, Object> inventory = new LinkedHashMap<>();
		Map<String, Object> slot = new LinkedHashMap<>();
		slot.put("name", "dirt");
		slot.put("count", 12);
		inventory.put(1, slot);
		assertEquals("[{\"name\":\"dirt\",\"count\":12}]", LuaJson.encode(inventory));
	}

	@Test
	void decodesBackToTables() {
		Object pose = LuaJson.maybeDecode("{\"facing\":\"north\",\"x\":10}");
		assertInstanceOf(Map.class, pose);
		@SuppressWarnings("unchecked")
		Map<String, Object> map = (Map<String, Object>) pose;
		assertEquals("north", map.get("facing"));
		assertEquals(10, map.get("x"));

		Object list = LuaJson.maybeDecode("[{\"count\":64}]");
		assertInstanceOf(List.class, list);
		assertEquals(64, ((Map<?, ?>) ((List<?>) list).getFirst()).get("count"));
	}

	@Test
	void leavesPlainTextAlone() {
		assertEquals("cobble", LuaJson.maybeDecode("cobble"));
		assertEquals("{not json", LuaJson.maybeDecode("{not json"));
	}

	@Test
	void rejectsMixedTables() {
		Map<Object, Object> mixed = new LinkedHashMap<>();
		mixed.put(1, "a");
		mixed.put("name", "b");
		assertThrows(IllegalArgumentException.class, () -> LuaJson.encode(mixed));
	}

	@Test
	void emptyMapIsObject() {
		assertEquals("{}", LuaJson.encode(Map.of()));
		assertTrue(((Map<?, ?>) LuaJson.decode("{}")).isEmpty());
	}
}
