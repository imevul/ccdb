package com.imevul.ccdb;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restricts raw SQL to a safe subset. Helpers build SQL that already passes this.
 */
public final class StatementGuard {
	private static final Set<String> ALLOWED_START = Set.of(
		"SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "WITH", "EXPLAIN"
	);
	private static final Pattern FORBIDDEN = Pattern.compile(
		"(?i)\\b(ATTACH|DETACH|LOAD_EXTENSION)\\b|(?i)\\bPRAGMA\\b"
	);
	private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
	private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern FIRST_WORD = Pattern.compile("([A-Za-z_]+)");

	private StatementGuard() {
	}

	public static void check(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("expected SQL");
		}
		String stripped = stripComments(sql).trim();
		if (stripped.isEmpty()) {
			throw new IllegalArgumentException("expected SQL");
		}
		if (hasMultipleStatements(stripped)) {
			throw new IllegalArgumentException("multiple SQL statements are not allowed");
		}
		if (FORBIDDEN.matcher(stripped).find()) {
			throw new IllegalArgumentException("statement is not allowed");
		}
		Matcher first = FIRST_WORD.matcher(stripped);
		if (!first.find()) {
			throw new IllegalArgumentException("expected SQL");
		}
		String keyword = first.group(1).toUpperCase(Locale.ROOT);
		if (!ALLOWED_START.contains(keyword)) {
			throw new IllegalArgumentException("statement is not allowed");
		}
	}

	static String stripComments(String sql) {
		String withoutBlocks = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
		return LINE_COMMENT.matcher(withoutBlocks).replaceAll(" ");
	}

	static boolean hasMultipleStatements(String sql) {
		boolean inString = false;
		char quote = 0;
		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (inString) {
				if (c == quote) {
					if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
						i++;
						continue;
					}
					inString = false;
				}
				continue;
			}
			if (c == '\'' || c == '"') {
				inString = true;
				quote = c;
				continue;
			}
			if (c == ';') {
				String rest = sql.substring(i + 1).trim();
				return !rest.isEmpty();
			}
		}
		return false;
	}
}
