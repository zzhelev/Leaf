package dev.app.leaf.domain.interfaces

interface IGetLinesFromTextGitAction {
    operator fun invoke(content: String): List<String>
}