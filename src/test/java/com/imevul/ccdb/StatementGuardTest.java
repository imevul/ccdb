package com.imevul.ccdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatementGuardTest {
	@Test
	void allowsHelpersAndSelect() {
		assertDoesNotThrow(() -> StatementGuard.check("SELECT * FROM items WHERE name = ?"));
		assertDoesNotThrow(() -> StatementGuard.check("INSERT INTO items (name) VALUES (?)"));
		assertDoesNotThrow(() -> StatementGuard.check("CREATE TABLE IF NOT EXISTS items (name TEXT)"));
	}

	@Test
	void rejectsDangerousSql() {
		assertThrows(IllegalArgumentException.class, () -> StatementGuard.check("ATTACH DATABASE 'x' AS other"));
		assertThrows(IllegalArgumentException.class, () -> StatementGuard.check("PRAGMA journal_mode=DELETE"));
		assertThrows(IllegalArgumentException.class, () -> StatementGuard.check("SELECT 1; DROP TABLE items"));
		assertThrows(IllegalArgumentException.class, () -> StatementGuard.check("BEGIN IMMEDIATE"));
		assertThrows(IllegalArgumentException.class, () -> StatementGuard.check("SELECT load_extension('x')"));
	}
}
