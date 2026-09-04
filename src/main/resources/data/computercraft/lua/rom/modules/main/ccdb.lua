local native = require("ccdb.native")

local wrap = {}

local names = {
	"exec", "query", "create", "insert", "upsert", "get", "find", "count",
	"update", "delete", "increment", "begin", "commit", "rollback",
}
for i = 1, #names do
	local name = names[i]
	wrap[name] = function(...)
		return native[name](...)
	end
end

function wrap.transaction(fn)
	if type(fn) ~= "function" then
		error("bad argument #1 (expected function, got " .. type(fn) .. ")", 2)
	end
	native.begin()
	local ok, err = pcall(fn)
	if ok then
		native.commit()
		return
	end
	native.rollback()
	error(err, 0)
end

return wrap
