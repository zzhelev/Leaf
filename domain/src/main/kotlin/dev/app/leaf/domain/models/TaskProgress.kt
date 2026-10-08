// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * The progress of the foreground task, as git reports it: [stage] such as "Writing objects", and its [percent]. Both
 * are null until git reports its first stage.
 */
data class TaskProgress(val stage: String?, val percent: Int?)
