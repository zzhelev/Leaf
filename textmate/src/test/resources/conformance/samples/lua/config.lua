-- Settings for the plugin.
local M = {}

M.defaults = {
  width = 80,
  show_hidden = false,
  ignore = { "%.git/", "node_modules" },
}

--[[
  Merges user options over the defaults.
]]
function M.setup(opts)
  opts = opts or {}
  for key, value in pairs(M.defaults) do
    if opts[key] == nil then
      opts[key] = value
    end
  end
  return setmetatable(opts, { __index = M.defaults })
end

local count = #M.defaults.ignore + 0x10
print(string.format("%d patterns", count), [[long string]])
return M
