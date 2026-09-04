package com.imevul.ccdb;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LuaRows {
	private LuaRows() {
	}

	public static List<Map<String, Object>> read(ResultSet rs, int maxRows) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		int columns = meta.getColumnCount();
		int[] types = new int[columns + 1];
		String[] names = new String[columns + 1];
		for (int i = 1; i <= columns; i++) {
			types[i] = meta.getColumnType(i);
			names[i] = meta.getColumnLabel(i);
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		while (rs.next() && rows.size() < maxRows) {
			Map<String, Object> row = new LinkedHashMap<>();
			for (int i = 1; i <= columns; i++) {
				row.put(names[i], readValue(rs, i, types[i]));
			}
			rows.add(row);
		}
		return rows;
	}

	private static Object readValue(ResultSet rs, int index, int type) throws SQLException {
		Object raw = rs.getObject(index);
		if (raw == null || rs.wasNull()) {
			return null;
		}
		if (type == Types.INTEGER || type == Types.BIGINT || type == Types.SMALLINT || type == Types.TINYINT) {
			if (raw instanceof Number number) {
				long value = number.longValue();
				if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
					return (int) value;
				}
				return value;
			}
		}
		if (type == Types.FLOAT || type == Types.DOUBLE || type == Types.REAL || type == Types.NUMERIC || type == Types.DECIMAL) {
			if (raw instanceof Number number) {
				return number.doubleValue();
			}
		}
		if (raw instanceof Number number) {
			if (number instanceof Double || number instanceof Float) {
				return number.doubleValue();
			}
			long value = number.longValue();
			if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
				return (int) value;
			}
			return value;
		}
		if (raw instanceof Boolean || raw instanceof byte[]) {
			return raw;
		}
		String text = raw.toString();
		if (type == Types.VARCHAR || type == Types.CHAR || type == Types.LONGVARCHAR || type == Types.NVARCHAR || type == Types.CLOB || type == Types.OTHER) {
			return LuaJson.maybeDecode(text);
		}
		return LuaJson.maybeDecode(text);
	}
}
