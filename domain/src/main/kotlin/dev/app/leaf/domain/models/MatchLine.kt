package dev.app.leaf.domain.models

import dev.app.leaf.domain.DiffMatchPatch

data class MatchLine(val diffs: List<DiffMatchPatch.Diff>)