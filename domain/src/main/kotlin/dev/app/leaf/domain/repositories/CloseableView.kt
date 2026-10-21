package dev.app.leaf.domain.repositories


enum class CloseableView {
    DIFF,
    LOG_SEARCH,
    SIDE_PANE_SEARCH,
    COMMIT_CHANGES_SEARCH,
    STAGED_CHANGES_SEARCH,
    UNSTAGED_CHANGES_SEARCH,
    COMPARISON, // Fork-only: a comparison of two commits or branches, or a worktree's review
    COMPARISON_FULL_VIEW, // Fork-only: its full view, which Esc leaves before it ends the comparison
    COMMIT_FIELD_SUGGESTIONS, // Fork-only
}