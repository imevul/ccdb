package com.imevul.ccdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-wide SQLite handle. One connection, one lock, WAL + busy timeout.
 */
public final class Database {
	public static final int MAX_ROWS = SqlBuilder.MAX_ROWS;
	public static final int BUSY_TIMEOUT_MS = 5000;
	public static final int STATEMENT_TIMEOUT_SEC = 5;

	private static final Database INSTANCE = new Database();

	private final ReentrantLock lock = new ReentrantLock();
	private final ThreadLocal<Integer> txDepth = ThreadLocal.withInitial(() -> 0);
	private Connection connection;

	public static Database get() {
		return INSTANCE;
	}

	public void open(Path dir) throws SQLException, IOException {
		lock.lock();
		try {
			closeUnlocked();
			Files.createDirectories(dir);
			Path file = dir.resolve("main.sqlite");
			connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA journal_mode=WAL");
				statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
				statement.execute("PRAGMA foreign_keys=ON");
			}
			connection.setAutoCommit(true);
		} finally {
			lock.unlock();
		}
	}

	public void close() {
		lock.lock();
		try {
			closeUnlocked();
		} finally {
			lock.unlock();
		}
	}

	public boolean isOpen() {
		lock.lock();
		try {
			return connection != null;
		} finally {
			lock.unlock();
		}
	}

	public Map<String, Object> exec(String sql, List<Object> params) {
		StatementGuard.check(sql);
		return withLock(() -> execUnlocked(sql, params));
	}

	public List<Map<String, Object>> query(String sql, List<Object> params) {
		StatementGuard.check(sql);
		return withLock(() -> queryUnlocked(sql, params));
	}

	public <T> T transaction(SqlWork<T> work) {
		ensureOpen();
		lock.lock();
		try {
			int depth = txDepth.get();
			if (depth > 0) {
				txDepth.set(depth + 1);
				try {
					return work.run();
				} finally {
					txDepth.set(depth);
				}
			}
			txDepth.set(1);
			try (Statement begin = connection.createStatement()) {
				begin.execute("BEGIN IMMEDIATE");
			}
			try {
				T result = work.run();
				try (Statement commit = connection.createStatement()) {
					commit.execute("COMMIT");
				}
				return result;
			} catch (RuntimeException e) {
				rollbackQuietly();
				throw e;
			} catch (SQLException e) {
				rollbackQuietly();
				throw wrap(e);
			} finally {
				txDepth.set(0);
			}
		} catch (SQLException e) {
			throw wrap(e);
		} finally {
			lock.unlock();
		}
	}

	public Map<String, Object> create(String table, Map<String, String> columns) {
		return run(SqlBuilder.create(table, columns));
	}

	public Map<String, Object> insert(String table, Map<String, Object> row) {
		return run(SqlBuilder.insert(table, row));
	}

	public Map<String, Object> upsert(String table, Map<String, Object> row) {
		return withLock(() -> {
			List<String> conflict = conflictColumns(table, row);
			return execUnlocked(SqlBuilder.upsert(table, row, conflict));
		});
	}

	public Map<String, Object> get(String table, Map<String, Object> where) {
		SqlBuilder.BuiltSql sql = SqlBuilder.select(table, where, new SqlBuilder.FindOptions(1, 0, null, false), false);
		List<Map<String, Object>> rows = withLock(() -> queryUnlocked(sql));
		return rows.isEmpty() ? null : rows.getFirst();
	}

	public List<Map<String, Object>> find(String table, Map<String, Object> where, SqlBuilder.FindOptions options) {
		return withLock(() -> queryUnlocked(SqlBuilder.select(table, where, options, false)));
	}

	public long count(String table, Map<String, Object> where) {
		SqlBuilder.BuiltSql sql = SqlBuilder.select(table, where, null, true);
		return withLock(() -> {
			try (PreparedStatement statement = prepare(sql)) {
				try (ResultSet rs = statement.executeQuery()) {
					if (!rs.next()) {
						return 0L;
					}
					return rs.getLong(1);
				}
			}
		});
	}

	public Map<String, Object> update(String table, Map<String, Object> values, Map<String, Object> where) {
		return run(SqlBuilder.update(table, values, where));
	}

	public Map<String, Object> delete(String table, Map<String, Object> where) {
		return run(SqlBuilder.delete(table, where));
	}

	public Map<String, Object> increment(String table, String column, Object delta, Map<String, Object> where) {
		return run(SqlBuilder.increment(table, column, delta, where));
	}

	@FunctionalInterface
	public interface SqlWork<T> {
		T run() throws SQLException;
	}

	private Map<String, Object> run(SqlBuilder.BuiltSql sql) {
		return withLock(() -> execUnlocked(sql));
	}

	private Map<String, Object> execUnlocked(SqlBuilder.BuiltSql sql) throws SQLException {
		return execUnlocked(sql.sql(), sql.params());
	}

	private List<Map<String, Object>> queryUnlocked(SqlBuilder.BuiltSql sql) throws SQLException {
		return queryUnlocked(sql.sql(), sql.params());
	}

	private Map<String, Object> execUnlocked(String sql, List<Object> params) throws SQLException {
		ensureOpenUnlocked();
		try (PreparedStatement statement = prepare(sql, params)) {
			statement.execute();
			long changes;
			long lastId;
			try (Statement extra = connection.createStatement();
				 ResultSet rs = extra.executeQuery("SELECT changes(), last_insert_rowid()")) {
				rs.next();
				changes = rs.getLong(1);
				lastId = rs.getLong(2);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("changes", (int) changes);
			result.put("last_insert_id", lastId > Integer.MAX_VALUE ? lastId : (int) lastId);
			return result;
		}
	}

	private List<Map<String, Object>> queryUnlocked(String sql, List<Object> params) throws SQLException {
		ensureOpenUnlocked();
		try (PreparedStatement statement = prepare(sql, params);
			 ResultSet rs = statement.executeQuery()) {
			return LuaRows.read(rs, MAX_ROWS);
		}
	}

	private PreparedStatement prepare(SqlBuilder.BuiltSql sql) throws SQLException {
		return prepare(sql.sql(), sql.params());
	}

	private PreparedStatement prepare(String sql, List<Object> params) throws SQLException {
		PreparedStatement statement = connection.prepareStatement(sql);
		statement.setQueryTimeout(STATEMENT_TIMEOUT_SEC);
		if (params != null) {
			for (int i = 0; i < params.size(); i++) {
				statement.setObject(i + 1, params.get(i));
			}
		}
		return statement;
	}

	private List<String> conflictColumns(String table, Map<String, Object> row) throws SQLException {
		ensureOpenUnlocked();
		String tableName = SqlBuilder.requireIdent(table);
		List<String> pk = new ArrayList<>();
		try (Statement statement = connection.createStatement();
			 ResultSet rs = statement.executeQuery("PRAGMA table_info(" + SqlBuilder.ident(tableName) + ")")) {
			while (rs.next()) {
				if (rs.getInt("pk") > 0) {
					String name = rs.getString("name");
					if (row.containsKey(name)) {
						pk.add(name);
					}
				}
			}
		}
		if (!pk.isEmpty()) {
			return pk;
		}
		List<String> unique = new ArrayList<>();
		List<String> indexes = new ArrayList<>();
		try (Statement statement = connection.createStatement();
			 ResultSet rs = statement.executeQuery("PRAGMA index_list(" + SqlBuilder.ident(tableName) + ")")) {
			while (rs.next()) {
				if (rs.getInt("unique") == 1) {
					indexes.add(rs.getString("name"));
				}
			}
		}
		for (String index : indexes) {
			List<String> cols = new ArrayList<>();
			try (Statement statement = connection.createStatement();
				 ResultSet rs = statement.executeQuery("PRAGMA index_info(" + SqlBuilder.ident(index) + ")")) {
				while (rs.next()) {
					cols.add(rs.getString("name"));
				}
			}
			if (!cols.isEmpty() && row.keySet().containsAll(cols)) {
				unique.addAll(cols);
				break;
			}
		}
		if (unique.isEmpty()) {
			throw new IllegalArgumentException("upsert requires primary or unique columns in the row");
		}
		return unique;
	}

	private <T> T withLock(SqlWork<T> work) {
		ensureOpen();
		lock.lock();
		try {
			return work.run();
		} catch (SQLException e) {
			throw wrap(e);
		} finally {
			lock.unlock();
		}
	}

	private void ensureOpen() {
		if (!isOpen()) {
			throw new IllegalStateException("database is not available");
		}
	}

	private void ensureOpenUnlocked() {
		if (connection == null) {
			throw new IllegalStateException("database is not available");
		}
	}

	private void closeUnlocked() {
		if (connection == null) {
			return;
		}
		try {
			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			} catch (SQLException ignored) {
			}
			connection.close();
		} catch (SQLException ignored) {
		} finally {
			connection = null;
			txDepth.set(0);
		}
	}

	private void rollbackQuietly() {
		try (Statement rollback = connection.createStatement()) {
			rollback.execute("ROLLBACK");
		} catch (SQLException ignored) {
		}
	}

	private static IllegalStateException wrap(SQLException e) {
		String message = e.getMessage();
		if (message == null || message.isBlank()) {
			message = "SQL error";
		}
		return new IllegalStateException(message, e);
	}
}
