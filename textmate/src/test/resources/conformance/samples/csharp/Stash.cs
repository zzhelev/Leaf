using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace Example.Git
{
    /// <summary>A stash entry.</summary>
    public sealed record Stash(int Index, string Message);

    [Serializable]
    public class StashList
    {
        private readonly List<Stash> _entries = new();
        public int Count => _entries.Count;

        public async Task<Stash?> PopAsync()
        {
            await Task.Delay(10);
            if (_entries.Count == 0) return null;
            var first = _entries[0];
            _entries.RemoveAt(0);
            return first;
        }

        public override string ToString() => $"{Count} stashes, newest: {_entries.FirstOrDefault()?.Message ?? "none"}";
    }
}
