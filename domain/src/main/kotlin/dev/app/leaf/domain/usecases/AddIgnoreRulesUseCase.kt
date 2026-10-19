// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.interfaces.IAddIgnoreRulesGitAction
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Job
import javax.inject.Inject

/** Adds ignore rules for untracked files (fork-only). The status refreshes, as the files are gone from it. */
class AddIgnoreRulesUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val addIgnoreRulesGitAction: IAddIgnoreRulesGitAction,
) {
    operator fun invoke(place: IgnorePlace, rules: List<String>): Job {
        return useCaseExecutor.executeLaunch(
            taskType = TaskType.AddIgnoreRules,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
        ) { repositoryPath ->
            addIgnoreRulesGitAction(repositoryPath, place, rules)
        }
    }
}
