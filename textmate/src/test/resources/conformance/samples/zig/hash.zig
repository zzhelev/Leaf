const std = @import("std");

/// FNV-1a, 32 bits.
pub fn fnv1a(data: []const u8) u32 {
    var hash: u32 = 0x811c9dc5;
    for (data) |byte| {
        hash ^= byte;
        hash *%= 0x01000193;
    }
    return hash;
}

test "fnv1a of leaf" {
    try std.testing.expectEqual(@as(u32, 0x8b2f0e1a), fnv1a("leaf"));
}

pub fn main() !void {
    const stdout = std.io.getStdOut().writer();
    // Prints the hash.
    try stdout.print("{x}\n", .{fnv1a("leaf")});
}
