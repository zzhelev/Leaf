// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;

/**
 * Fork-only probe backing docs/fork/worktree-lifecycle.md: what JGit does with merges and rebases that the git CLI
 * started, in a linked worktree's git dir, the way a Leaf tab opens it. Driven by lifecycle-probe.sh; never point it at
 * a real repository.
 *
 * Usage: java -cp <jgit + deps> LifecycleProbe.java <op> <gitDir> [args]
 *   state <gitDir>                 repository state, and whether SQUASH_MSG / MERGE_MSG exist
 *   rebase-continue <gitDir>       RebaseCommand CONTINUE
 *   rebase-skip <gitDir>           RebaseCommand SKIP
 *   rebase-abort <gitDir>          RebaseCommand ABORT
 *   commit <gitDir> <message>      CommitCommand
 *   squash <gitDir> <branch>       MergeCommand with squash, no commit
 */
public class LifecycleProbe {
    public static void main(String[] args) throws Exception {
        String op = args[0];
        File gitDir = new File(args[1]);
        try (Git git = Git.open(gitDir)) {
            Repository repo = git.getRepository();
            switch (op) {
                case "state" -> System.out.println("state=" + repo.getRepositoryState()
                        + " squashMsg=" + (repo.readSquashCommitMsg() != null)
                        + " mergeMsg=" + (repo.readMergeCommitMsg() != null));
                case "rebase-continue", "rebase-skip", "rebase-abort" -> {
                    RebaseCommand.Operation operation = switch (op) {
                        case "rebase-continue" -> RebaseCommand.Operation.CONTINUE;
                        case "rebase-skip" -> RebaseCommand.Operation.SKIP;
                        default -> RebaseCommand.Operation.ABORT;
                    };
                    try {
                        RebaseResult result = git.rebase().setOperation(operation).call();
                        System.out.println("rebase " + operation + ": " + result.getStatus());
                    } catch (Exception e) {
                        System.out.println("rebase " + operation + " threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
                case "commit" -> {
                    RevCommit commit = git.commit().setMessage(args[2]).call();
                    System.out.println("commit " + commit.abbreviate(7).name() + " parents=" + commit.getParentCount()
                            + " state after=" + repo.getRepositoryState()
                            + " squashMsg=" + (repo.readSquashCommitMsg() != null));
                }
                case "squash" -> {
                    ObjectId branch = repo.resolve(args[2]);
                    MergeResult result = git.merge().include(branch).setSquash(true).setCommit(false).call();
                    System.out.println("squash: " + result.getMergeStatus() + " state=" + repo.getRepositoryState());
                    String msg = repo.readSquashCommitMsg();
                    System.out.println("SQUASH_MSG:\n" + msg);
                }
                default -> throw new IllegalArgumentException(op);
            }
        }
    }
}
