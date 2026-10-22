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
    COMPARISON_COMMIT, // Fork-only: one of its commits' own changes, which Esc leaves for all of them
    COMPARISON_SIDE_PICKER, // Fork-only: the list of refs under a side's name
    COMMIT_FIELD_SUGGESTIONS, // Fork-only
}