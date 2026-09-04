package com.imevul.ccdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lua table ↔ JSON TEXT. Array-like maps (keys 1..n, no holes) become arrays;
 * string-key maps become objects. Mixed tables are rejected.
 */
public final class LuaJson {
	private LuaJson() {
	}

	public static String encode(Object value) {
		StringBuilder out = new StringBuilder();
		write(out, value);
		return out.toString();
	}

	public static Object decode(String json) {
		Parser parser = new Parser(json);
		Object value = parser.parseValue();
		parser.skipWs();
		if (!parser.done()) {
			throw new IllegalArgumentException("trailing JSON");
		}
		return value;
	}

	/**
	 * If {@code text} is a JSON object or array, decode it; otherwise return the original string.
	 */
	public static Object maybeDecode(String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return text;
		}
		char first = trimmed.charAt(0);
		if (first != '{' && first != '[') {
			return text;
		}
		try {
			Object decoded = decode(trimmed);
			if (decoded instanceof Map || decoded instanceof List) {
				return decoded;
			}
		} catch (RuntimeException ignored) {
		}
		return text;
	}

	public static boolean isTable(Object value) {
		return value instanceof Map || value instanceof List;
	}

	static void write(StringBuilder out, Object value) {
		if (value == null) {
			out.append("null");
			return;
		}
		if (value instanceof Boolean bool) {
			out.append(bool);
			return;
		}
		if (value instanceof Number number) {
			writeNumber(out, number);
			return;
		}
		if (value instanceof String string) {
			writeString(out, string);
			return;
		}
		if (value instanceof List<?> list) {
			writeArray(out, list);
			return;
		}
		if (value instanceof Map<?, ?> map) {
			writeMap(out, map);
			return;
		}
		throw new IllegalArgumentException("cannot encode " + value.getClass().getSimpleName());
	}

	private static void writeNumber(StringBuilder out, Number number) {
		if (number instanceof Double || number instanceof Float) {
			double d = number.doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				throw new IllegalArgumentException("cannot encode non-finite number");
			}
			if (d == Math.rint(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
				out.append((long) d);
				return;
			}
			out.append(d);
			return;
		}
		out.append(number.longValue());
	}

	private static void writeString(StringBuilder out, String string) {
		out.append('"');
		for (int i = 0; i < string.length(); i++) {
			char c = string.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
	}

	private static void writeArray(StringBuilder out, List<?> list) {
		out.append('[');
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			write(out, list.get(i));
		}
		out.append(']');
	}

	private static void writeMap(StringBuilder out, Map<?, ?> map) {
		Shape shape = shape(map);
		if (shape == Shape.MIXED) {
			throw new IllegalArgumentException("mixed array and map tables cannot be encoded");
		}
		if (shape == Shape.ARRAY) {
			out.append('[');
			int n = map.size();
			for (int i = 1; i <= n; i++) {
				if (i > 1) {
					out.append(',');
				}
				write(out, valueAt(map, i));
			}
			out.append(']');
			return;
		}
		out.append('{');
		boolean first = true;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException("object keys must be strings");
			}
			if (!first) {
				out.append(',');
			}
			first = false;
			writeString(out, key);
			out.append(':');
			write(out, entry.getValue());
		}
		out.append('}');
	}

	enum Shape {
		ARRAY,
		OBJECT,
		MIXED
	}

	static Shape shape(Map<?, ?> map) {
		if (map.isEmpty()) {
			return Shape.OBJECT;
		}
		boolean anyIndex = false;
		boolean anyName = false;
		int n = map.size();
		boolean[] seen = new boolean[n + 1];
		for (Object key : map.keySet()) {
			Integer idx = asIndex(key);
			if (idx != null) {
				anyIndex = true;
				if (idx < 1 || idx > n || seen[idx]) {
					return Shape.MIXED;
				}
				seen[idx] = true;
			} else if (key instanceof String) {
				anyName = true;
			} else {
				return Shape.MIXED;
			}
		}
		if (anyIndex && anyName) {
			return Shape.MIXED;
		}
		if (anyIndex) {
			for (int i = 1; i <= n; i++) {
				if (!seen[i]) {
					return Shape.MIXED;
				}
			}
			return Shape.ARRAY;
		}
		return Shape.OBJECT;
	}

	private static Object valueAt(Map<?, ?> map, int index) {
		Object value = map.get(index);
		if (value == null) {
			value = map.get((long) index);
		}
		if (value == null) {
			value = map.get((double) index);
		}
		return value;
	}

	static Integer asIndex(Object key) {
		if (key instanceof Integer integer) {
			return integer;
		}
		if (key instanceof Long longKey) {
			if (longKey < Integer.MIN_VALUE || longKey > Integer.MAX_VALUE) {
				return null;
			}
			return longKey.intValue();
		}
		if (key instanceof Double doubleKey) {
			if (doubleKey != Math.rint(doubleKey) || doubleKey < Integer.MIN_VALUE || doubleKey > Integer.MAX_VALUE) {
				return null;
			}
			return doubleKey.intValue();
		}
		if (key instanceof Float floatKey) {
			if (floatKey != Math.rint(floatKey) || floatKey < Integer.MIN_VALUE || floatKey > Integer.MAX_VALUE) {
				return null;
			}
			return floatKey.intValue();
		}
		return null;
	}

	private static final class Parser {
		private final String json;
		private int pos;

		Parser(String json) {
			this.json = json;
		}

		boolean done() {
			return pos >= json.length();
		}

		void skipWs() {
			while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
				pos++;
			}
		}

		Object parseValue() {
			skipWs();
			if (done()) {
				throw new IllegalArgumentException("unexpected end of JSON");
			}
			char c = json.charAt(pos);
			if (c == '{') {
				return parseObject();
			}
			if (c == '[') {
				return parseArray();
			}
			if (c == '"') {
				return parseString();
			}
			if (c == 't' || c == 'f' || c == 'n') {
				return parseLiteral();
			}
			if (c == '-' || (c >= '0' && c <= '9')) {
				return parseNumber();
			}
			throw new IllegalArgumentException("unexpected " + c);
		}

		private Map<String, Object> parseObject() {
			pos++;
			Map<String, Object> map = new LinkedHashMap<>();
			skipWs();
			if (peek('}')) {
				pos++;
				return map;
			}
			while (true) {
				skipWs();
				if (done() || json.charAt(pos) != '"') {
					throw new IllegalArgumentException("expected object key");
				}
				String key = parseString();
				skipWs();
				if (!peek(':')) {
					throw new IllegalArgumentException("expected ':'");
				}
				pos++;
				map.put(key, parseValue());
				skipWs();
				if (peek('}')) {
					pos++;
					return map;
				}
				if (!peek(',')) {
					throw new IllegalArgumentException("expected ',' or '}'");
				}
				pos++;
			}
		}

		private List<Object> parseArray() {
			pos++;
			List<Object> list = new ArrayList<>();
			skipWs();
			if (peek(']')) {
				pos++;
				return list;
			}
			while (true) {
				list.add(parseValue());
				skipWs();
				if (peek(']')) {
					pos++;
					return list;
				}
				if (!peek(',')) {
					throw new IllegalArgumentException("expected ',' or ']'");
				}
				pos++;
			}
		}

		private String parseString() {
			pos++;
			StringBuilder out = new StringBuilder();
			while (!done()) {
				char c = json.charAt(pos++);
				if (c == '"') {
					return out.toString();
				}
				if (c == '\\') {
					if (done()) {
						throw new IllegalArgumentException("unterminated escape");
					}
					char e = json.charAt(pos++);
					out.append(switch (e) {
						case '"', '\\', '/' -> e;
						case 'b' -> '\b';
						case 'f' -> '\f';
						case 'n' -> '\n';
						case 'r' -> '\r';
						case 't' -> '\t';
						case 'u' -> parseUnicode();
						default -> throw new IllegalArgumentException("bad escape");
					});
					continue;
				}
				out.append(c);
			}
			throw new IllegalArgumentException("unterminated string");
		}

		private char parseUnicode() {
			if (pos + 4 > json.length()) {
				throw new IllegalArgumentException("bad unicode escape");
			}
			int value = Integer.parseInt(json.substring(pos, pos + 4), 16);
			pos += 4;
			return (char) value;
		}

		private Object parseLiteral() {
			if (json.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (json.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			if (json.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new IllegalArgumentException("unexpected literal");
		}

		private Number parseNumber() {
			int start = pos;
			if (peek('-')) {
				pos++;
			}
			while (pos < json.length() && isDigit(json.charAt(pos))) {
				pos++;
			}
			boolean frac = false;
			if (peek('.')) {
				frac = true;
				pos++;
				while (pos < json.length() && isDigit(json.charAt(pos))) {
					pos++;
				}
			}
			if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
				frac = true;
				pos++;
				if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) {
					pos++;
				}
				while (pos < json.length() && isDigit(json.charAt(pos))) {
					pos++;
				}
			}
			String raw = json.substring(start, pos);
			if (frac) {
				return Double.parseDouble(raw);
			}
			try {
				long value = Long.parseLong(raw);
				if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
					return (int) value;
				}
				return value;
			} catch (NumberFormatException e) {
				return Double.parseDouble(raw);
			}
		}

		private boolean peek(char c) {
			return pos < json.length() && json.charAt(pos) == c;
		}

		private static boolean isDigit(char c) {
			return c >= '0' && c <= '9';
		}
	}
}
