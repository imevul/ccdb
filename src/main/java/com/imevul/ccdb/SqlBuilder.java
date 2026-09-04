package com.imevul.ccdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifier-safe helper SQL. Values are always bound parameters.
 */
public final class SqlBuilder {
	public static final int MAX_ROWS = 1000;

	private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final Set<String> TYPE_WORDS = Set.of(
		"INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC",
		"PRIMARY", "KEY", "NOT", "NULL", "UNIQUE", "DEFAULT", "AUTOINCREMENT"
	);
	private static final Pattern TYPE_TOKEN = Pattern.compile(
		"[A-Za-z_]+|[0-9]+|'(?:[A-Za-z0-9_ ]*)'"
	);

	private SqlBuilder() {
	}

	public record BuiltSql(String sql, List<Object> params) {
	}

	public record FindOptions(int limit, int offset, String orderColumn, boolean descending) {
		public static FindOptions defaults() {
			return new FindOptions(MAX_ROWS, 0, null, false);
		}
	}

	public static String ident(String name) {
		if (name == null || !IDENT.matcher(name).matches()) {
			throw new IllegalArgumentException("invalid identifier");
		}
		return '"' + name + '"';
	}

	public static String requireIdent(String name) {
		if (name == null || !IDENT.matcher(name).matches()) {
			throw new IllegalArgumentException("invalid identifier");
		}
		return name;
	}

	public static void validateType(String type) {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("invalid column type");
		}
		if (type.indexOf('(') >= 0 || type.indexOf(')') >= 0) {
			throw new IllegalArgumentException("invalid column type");
		}
		String trimmed = type.trim();
		Matcher matcher = TYPE_TOKEN.matcher(trimmed);
		int end = 0;
		boolean any = false;
		while (matcher.find()) {
			if (matcher.start() != end && !trimmed.substring(end, matcher.start()).isBlank()) {
				throw new IllegalArgumentException("invalid column type");
			}
			end = matcher.end();
			any = true;
			String token = matcher.group();
			if (token.startsWith("'")) {
				continue;
			}
			if (token.chars().allMatch(Character::isDigit)) {
				continue;
			}
			if (!TYPE_WORDS.contains(token.toUpperCase(Locale.ROOT))) {
				throw new IllegalArgumentException("invalid column type");
			}
		}
		if (!any || end != trimmed.length()) {
			throw new IllegalArgumentException("invalid column type");
		}
	}

	public static BuiltSql create(String table, Map<String, String> columns) {
		if (columns == null || columns.isEmpty()) {
			throw new IllegalArgumentException("create requires columns");
		}
		StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
		sql.append(ident(table)).append(" (");
		boolean first = true;
		for (Map.Entry<String, String> entry : columns.entrySet()) {
			if (!first) {
				sql.append(", ");
			}
			first = false;
			sql.append(ident(entry.getKey())).append(' ');
			validateType(entry.getValue());
			sql.append(entry.getValue().trim());
		}
		sql.append(')');
		return new BuiltSql(sql.toString(), List.of());
	}

	public static BuiltSql insert(String table, Map<String, Object> row) {
		if (row == null || row.isEmpty()) {
			throw new IllegalArgumentException("insert requires a row");
		}
		List<String> cols = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		StringBuilder placeholders = new StringBuilder();
		for (Map.Entry<String, Object> entry : row.entrySet()) {
			if (!cols.isEmpty()) {
				placeholders.append(", ");
			}
			cols.add(ident(entry.getKey()));
			placeholders.append('?');
			params.add(bindValue(entry.getValue()));
		}
		String sql = "INSERT INTO " + ident(table) + " (" + String.join(", ", cols) + ") VALUES (" + placeholders + ")";
		return new BuiltSql(sql, params);
	}

	public static BuiltSql upsert(String table, Map<String, Object> row, List<String> conflictCols) {
		BuiltSql insert = insert(table, row);
		if (conflictCols == null || conflictCols.isEmpty()) {
			throw new IllegalArgumentException("upsert requires primary or unique columns in the row");
		}
		StringBuilder sql = new StringBuilder(insert.sql);
		sql.append(" ON CONFLICT (");
		for (int i = 0; i < conflictCols.size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			sql.append(ident(conflictCols.get(i)));
		}
		sql.append(") DO UPDATE SET ");
		boolean first = true;
		for (String col : row.keySet()) {
			if (conflictCols.contains(col)) {
				continue;
			}
			if (!first) {
				sql.append(", ");
			}
			first = false;
			String quoted = ident(col);
			sql.append(quoted).append(" = excluded.").append(quoted);
		}
		if (first) {
			String quoted = ident(conflictCols.getFirst());
			sql.append(quoted).append(" = excluded.").append(quoted);
		}
		return new BuiltSql(sql.toString(), insert.params);
	}

	public static BuiltSql update(String table, Map<String, Object> values, Map<String, Object> where) {
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("update requires values");
		}
		requireWhere(where);
		List<Object> params = new ArrayList<>();
		StringBuilder sql = new StringBuilder("UPDATE ");
		sql.append(ident(table)).append(" SET ");
		boolean first = true;
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			if (!first) {
				sql.append(", ");
			}
			first = false;
			sql.append(ident(entry.getKey())).append(" = ?");
			params.add(bindValue(entry.getValue()));
		}
		appendWhere(sql, params, where);
		return new BuiltSql(sql.toString(), params);
	}

	public static BuiltSql delete(String table, Map<String, Object> where) {
		requireWhere(where);
		List<Object> params = new ArrayList<>();
		StringBuilder sql = new StringBuilder("DELETE FROM ").append(ident(table));
		appendWhere(sql, params, where);
		return new BuiltSql(sql.toString(), params);
	}

	public static BuiltSql increment(String table, String column, Object delta, Map<String, Object> where) {
		requireWhere(where);
		List<Object> params = new ArrayList<>();
		String quoted = ident(column);
		StringBuilder sql = new StringBuilder("UPDATE ");
		sql.append(ident(table)).append(" SET ").append(quoted).append(" = ").append(quoted).append(" + ?");
		params.add(bindValue(delta));
		appendWhere(sql, params, where);
		return new BuiltSql(sql.toString(), params);
	}

	public static BuiltSql select(String table, Map<String, Object> where, FindOptions options, boolean countOnly) {
		FindOptions opts = options == null ? FindOptions.defaults() : options;
		List<Object> params = new ArrayList<>();
		StringBuilder sql = new StringBuilder("SELECT ");
		sql.append(countOnly ? "COUNT(*)" : "*");
		sql.append(" FROM ").append(ident(table));
		if (where != null && !where.isEmpty()) {
			appendWhere(sql, params, where);
		}
		if (!countOnly) {
			if (opts.orderColumn != null) {
				sql.append(" ORDER BY ").append(ident(opts.orderColumn));
				sql.append(opts.descending ? " DESC" : " ASC");
			}
			int limit = Math.min(Math.max(opts.limit, 1), MAX_ROWS);
			sql.append(" LIMIT ").append(limit);
			if (opts.offset > 0) {
				sql.append(" OFFSET ").append(opts.offset);
			}
		}
		return new BuiltSql(sql.toString(), params);
	}

	public static FindOptions parseOptions(Map<String, Object> options) {
		if (options == null || options.isEmpty()) {
			return FindOptions.defaults();
		}
		int limit = MAX_ROWS;
		int offset = 0;
		String orderColumn = null;
		boolean descending = false;
		Object limitRaw = options.get("limit");
		if (limitRaw != null) {
			limit = toInt(limitRaw, "limit");
		}
		Object offsetRaw = options.get("offset");
		if (offsetRaw != null) {
			offset = toInt(offsetRaw, "offset");
			if (offset < 0) {
				throw new IllegalArgumentException("offset must be >= 0");
			}
		}
		Object orderRaw = options.get("order");
		if (orderRaw != null) {
			if (!(orderRaw instanceof String order)) {
				throw new IllegalArgumentException("order must be a string");
			}
			String[] parts = order.trim().split("\\s+");
			if (parts.length == 0 || parts.length > 2) {
				throw new IllegalArgumentException("invalid order");
			}
			orderColumn = requireIdent(parts[0]);
			if (parts.length == 2) {
				String dir = parts[1].toUpperCase(Locale.ROOT);
				if ("DESC".equals(dir)) {
					descending = true;
				} else if (!"ASC".equals(dir)) {
					throw new IllegalArgumentException("invalid order");
				}
			}
		}
		return new FindOptions(limit, offset, orderColumn, descending);
	}

	public static Map<String, String> stringMap(Map<?, ?> raw, String what) {
		if (raw == null) {
			throw new IllegalArgumentException("expected " + what);
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException(what + " keys must be strings");
			}
			if (!(entry.getValue() instanceof String value)) {
				throw new IllegalArgumentException(what + " values must be strings");
			}
			out.put(key, value);
		}
		return out;
	}

	public static Map<String, Object> objectMap(Map<?, ?> raw, String what) {
		if (raw == null) {
			return Map.of();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException(what + " keys must be strings");
			}
			out.put(key, entry.getValue());
		}
		return out;
	}

	static Object bindValue(Object value) {
		if (value == null) {
			return null;
		}
		if (LuaJson.isTable(value)) {
			return LuaJson.encode(value);
		}
		if (value instanceof Boolean || value instanceof Number || value instanceof String) {
			return value;
		}
		throw new IllegalArgumentException("cannot store " + value.getClass().getSimpleName());
	}

	private static void requireWhere(Map<String, Object> where) {
		if (where == null || where.isEmpty()) {
			throw new IllegalArgumentException("where is required");
		}
	}

	static void appendWhere(StringBuilder sql, List<Object> params, Map<String, Object> where) {
		sql.append(" WHERE ");
		boolean first = true;
		for (Map.Entry<String, Object> entry : where.entrySet()) {
			if (!first) {
				sql.append(" AND ");
			}
			first = false;
			appendPredicate(sql, params, entry.getKey(), entry.getValue());
		}
	}

	private static void appendPredicate(StringBuilder sql, List<Object> params, String column, Object value) {
		if (value instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				throw new IllegalArgumentException("empty table where is not allowed");
			}
			if (LuaJson.shape(map) == LuaJson.Shape.ARRAY) {
				sql.append(ident(column)).append(" = ?");
				params.add(LuaJson.encode(map));
				return;
			}
			if (LuaJson.shape(map) == LuaJson.Shape.MIXED) {
				throw new IllegalArgumentException("mixed array and map tables cannot be used in where");
			}
			appendJsonWhere(sql, params, column, "$", map);
			return;
		}
		sql.append(ident(column)).append(" = ?");
		params.add(bindValue(value));
	}

	private static void appendJsonWhere(
		StringBuilder sql,
		List<Object> params,
		String column,
		String path,
		Map<?, ?> map
	) {
		boolean first = true;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException("JSON where keys must be strings");
			}
			requireIdent(key);
			String next = path + "." + key;
			Object value = entry.getValue();
			if (value instanceof Map<?, ?> nested && LuaJson.shape(nested) == LuaJson.Shape.OBJECT) {
				if (nested.isEmpty()) {
					throw new IllegalArgumentException("empty table where is not allowed");
				}
				if (!first) {
					sql.append(" AND ");
				}
				first = false;
				appendJsonWhere(sql, params, column, next, nested);
				continue;
			}
			if (!first) {
				sql.append(" AND ");
			}
			first = false;
			sql.append("json_extract(").append(ident(column)).append(", '").append(next).append("') = ?");
			params.add(bindValue(value));
		}
	}

	private static int toInt(Object raw, String name) {
		if (raw instanceof Number number) {
			return number.intValue();
		}
		throw new IllegalArgumentException(name + " must be a number");
	}
}
