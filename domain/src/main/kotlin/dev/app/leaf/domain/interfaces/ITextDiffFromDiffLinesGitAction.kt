package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.models.Line

interface ITextDiffFromDiffLinesGitAction {
    operator fun invoke(lines: List<Line>): List<Line>
}