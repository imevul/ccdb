package com.imevul.ccdb;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CcdbAPI implements ILuaAPI {
	private final IComputerSystem computer;

	public CcdbAPI(IComputerSystem computer) {
		this.computer = computer;
	}

	@Override
	public String[] getNames() {
		return new String[] { "ccdb" };
	}

	@Override
	public String getModuleName() {
		return "ccdb.native";
	}

	@Override
	public void shutdown() {
		try {
			Database.get().abortIfOwner(computer.getID());
		} catch (RuntimeException ignored) {
		}
	}

	@LuaFunction
	public final Map<String, Object> exec(IArguments args) throws LuaException {
		return bound(() -> Database.get().exec(args.getString(0), rest(args, 1)));
	}

	@LuaFunction
	public final List<Map<String, Object>> query(IArguments args) throws LuaException {
		return bound(() -> Database.get().query(args.getString(0), rest(args, 1)));
	}

	@LuaFunction
	public final void begin() throws LuaException {
		bound(() -> {
			Database.get().begin();
			return null;
		});
	}

	@LuaFunction
	public final void commit() throws LuaException {
		bound(() -> {
			Database.get().commit();
			return null;
		});
	}

	@LuaFunction
	public final void rollback() throws LuaException {
		bound(() -> {
			Database.get().rollback();
			return null;
		});
	}

	@LuaFunction
	public final Map<String, Object> create(IArguments args) throws LuaException {
		return bound(() -> Database.get().create(
			args.getString(0),
			SqlBuilder.stringMap(args.getTable(1), "columns")
		));
	}

	@LuaFunction
	public final Map<String, Object> insert(IArguments args) throws LuaException {
		return bound(() -> Database.get().insert(args.getString(0), SqlBuilder.objectMap(args.getTable(1), "row")));
	}

	@LuaFunction
	public final Map<String, Object> upsert(IArguments args) throws LuaException {
		return bound(() -> Database.get().upsert(args.getString(0), SqlBuilder.objectMap(args.getTable(1), "row")));
	}

	@LuaFunction
	public final @Nullable Map<String, Object> get(IArguments args) throws LuaException {
		return bound(() -> Database.get().get(args.getString(0), SqlBuilder.objectMap(args.getTable(1), "where")));
	}

	@LuaFunction
	public final List<Map<String, Object>> find(IArguments args) throws LuaException {
		return bound(() -> {
			Map<String, Object> where = optionalMap(args, 1, "where");
			Map<String, Object> options = optionalMap(args, 2, "options");
			return Database.get().find(args.getString(0), where, SqlBuilder.parseOptions(options));
		});
	}

	@LuaFunction
	public final int count(IArguments args) throws LuaException {
		return bound(() -> {
			Map<String, Object> where = optionalMap(args, 1, "where");
			return (int) Database.get().count(args.getString(0), where);
		});
	}

	@LuaFunction
	public final Map<String, Object> update(IArguments args) throws LuaException {
		return bound(() -> Database.get().update(
			args.getString(0),
			SqlBuilder.objectMap(args.getTable(1), "values"),
			SqlBuilder.objectMap(args.getTable(2), "where")
		));
	}

	@LuaFunction
	public final Map<String, Object> delete(IArguments args) throws LuaException {
		return bound(() -> Database.get().delete(args.getString(0), SqlBuilder.objectMap(args.getTable(1), "where")));
	}

	@LuaFunction
	public final Map<String, Object> increment(IArguments args) throws LuaException {
		return bound(() -> Database.get().increment(
			args.getString(0),
			args.getString(1),
			args.get(2),
			SqlBuilder.objectMap(args.getTable(3), "where")
		));
	}

	private <T> T bound(BoundWork<T> work) throws LuaException {
		Database db = Database.get();
		try {
			db.bind(computer.getID());
			return work.run();
		} catch (LuaException e) {
			throw e;
		} catch (RuntimeException e) {
			throw toLua(e);
		} finally {
			db.unbind();
		}
	}

	private static Map<String, Object> optionalMap(IArguments args, int index, String what) throws LuaException {
		if (index >= args.count() || "nil".equals(args.getType(index))) {
			return Map.of();
		}
		return SqlBuilder.objectMap(args.getTable(index), what);
	}

	private static List<Object> rest(IArguments args, int start) throws LuaException {
		List<Object> params = new ArrayList<>();
		for (int i = start; i < args.count(); i++) {
			if ("nil".equals(args.getType(i))) {
				params.add(null);
			} else {
				params.add(bindArg(args.get(i)));
			}
		}
		return params;
	}

	private static Object bindArg(Object value) {
		if (LuaJson.isTable(value)) {
			return LuaJson.encode(value);
		}
		return value;
	}

	private static LuaException toLua(RuntimeException e) {
		String message = e.getMessage();
		if (message == null || message.isBlank()) {
			message = e.getClass().getSimpleName();
		}
		return new LuaException(message);
	}

	@FunctionalInterface
	private interface BoundWork<T> {
		T run() throws LuaException;
	}
}
