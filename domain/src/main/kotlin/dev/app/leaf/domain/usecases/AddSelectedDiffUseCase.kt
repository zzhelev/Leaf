package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.extensions.toMutableSetAndAddAll
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

class AddSelectedDiffUseCase @Inject constructor() {
    operator fun invoke(
        diffSelected: DiffSelected?,
        diffType: List<DiffType.CommitDiff>,
        addToExisting: Boolean
    ): DiffSelected.CommitedChanges {
        val newDiffSelected =
            if (addToExisting && diffSelected is DiffSelected.CommitedChanges) {
                diffSelected.copy(items = diffSelected.items.toMutableSetAndAddAll(diffType))
            } else {
                DiffSelected.CommitedChanges(diffType.toSet())
            }

        return newDiffSelected
    }

    operator fun invoke(
        diffSelected: DiffSelected?,
        diffEntries: List<DiffType.UncommittedDiff>,
        addToExisting: Boolean,
        entryType: EntryType,
    ): DiffSelected.UncommittedChanges {
        val newDiffSelected =
            if (addToExisting && diffSelected is DiffSelected.UncommittedChanges && diffSelected.entryType == entryType) {
                diffSelected.copy(items = diffSelected.items.toMutableSetAndAddAll(diffEntries))
            } else {
                DiffSelected.UncommittedChanges(entryType, diffEntries.toSet())
            }

        return newDiffSelected
    }
}