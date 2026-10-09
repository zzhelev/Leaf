// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.util.Date;
import java.util.List;

/**
 * Fork-only probe backing the HEAD reflog section of docs/fork/architecture-notes.md. Runs the JGit calls that Leaf's
 * git actions make, on a repository opened the way a Leaf tab opens one: Git.open(<git dir>), which for a linked
 * worktree is <common>/.git/worktrees/<name>. Driven by worktree-reflog-probe.sh; never point it at a real repository.
 *
 * Usage: java -cp <jgit + deps> WorktreeReflogProbe.java <op> <gitDir> [args]
 *   commit <gitDir> <message>          DoCommitGitAction
 *   amend <gitDir> <message>           DoCommitGitAction with amend
 *   create-branch <gitDir> <name>      CreateBranchGitAction (create and check out)
 *   checkout <gitDir> <name>           CheckoutBranchGitAction
 *   checkout-commit <gitDir> <rev>     CheckoutCommitGitAction (detaches HEAD)
 *   reset <gitDir> <soft|mixed|hard> <rev>   ResetToCommitGitAction, and ResetRepositoryStateGitAction with HEAD
 *   merge <gitDir> <branch> <ff|no-ff> MergeBranchGitAction
 *   cherry-pick <gitDir> <rev>         CherryPickCommitGitAction
 *   revert <gitDir> <rev>              RevertCommitGitAction
 *   rebase <gitDir> <upstream>         RebaseBranchGitAction
 *   squash <gitDir> <upstream>         interactive rebase that squashes every commit into the first
 *   stash <gitDir>                     StashChangesGitAction
 *   rename <gitDir> <old> <new>        RenameBranchGitAction
 *   link <gitDir> <ref>                RefUpdate.link on HEAD, with a reflog message
 *   reflog <gitDir>                    the HEAD reflog as JGit reads it (GetRefDatesGitAction's checkout dates)
 *   gc-now <gitDir>                    GarbageCollectCommand with prune expiry "now"
 */
public class WorktreeReflogProbe {
    public static void main(String[] args) throws Exception {
        String op = args[0];

        try (Git git = Git.open(new File(args[1]))) {
            Repository repo = git.getRepository();
            switch (op) {
                case "commit" -> git.commit().setMessage(args[2]).setAllowEmpty(true).call();
                case "amend" -> git.commit().setMessage(args[2]).setAllowEmpty(true).setAmend(true).call();
                case "create-branch" -> git.checkout().setCreateBranch(true).setName(args[2]).call();
                case "checkout" -> git.checkout().setName(args[2]).call();
                case "checkout-commit" -> git.checkout().setName(repo.resolve(args[2]).name()).call();
                case "reset" -> git.reset()
                        .setMode(ResetCommand.ResetType.valueOf(args[2].toUpperCase()))
                        .setRef(repo.resolve(args[3]).name())
                        .call();
                case "merge" -> git.merge()
                        .include(repo.resolve(args[2]))
                        .setFastForward(args[3].equals("ff")
                                ? MergeCommand.FastForwardMode.FF
                                : MergeCommand.FastForwardMode.NO_FF)
                        .setMessage("Merge branch '" + args[2] + "' into " + repo.getBranch())
                        .call();
                case "cherry-pick" -> git.cherryPick().include(repo.resolve(args[2])).call();
                case "revert" -> git.revert().include(repo.resolve(args[2])).call();
                case "rebase" -> git.rebase()
                        .setUpstream(args[2])
                        .setOperation(RebaseCommand.Operation.BEGIN)
                        .call();
                case "squash" -> git.rebase()
                        .setUpstream(args[2])
                        .runInteractively(new RebaseCommand.InteractiveHandler() {
                            @Override
                            public void prepareSteps(List<RebaseTodoLine> steps) {
                                for (int i = 1; i < steps.size(); i++) {
                                    try {
                                        steps.get(i).setAction(RebaseTodoLine.Action.SQUASH);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            }

                            @Override
                            public String modifyCommitMessage(String message) {
                                return message;
                            }
                        })
                        .call();
                case "stash" -> git.stashCreate().setIncludeUntracked(true).call();
                case "rename" -> git.branchRename().setOldName(args[2]).setNewName(args[3]).call();
                case "link" -> {
                    RefUpdate update = repo.updateRef(Constants.HEAD);
                    update.setRefLogMessage("probe: RefUpdate.link to " + args[2], false);
                    System.out.println("link: " + update.link(args[2]));
                }
                case "reflog" -> {
                    ReflogReader reader = repo.getRefDatabase().getReflogReader(Constants.HEAD);
                    List<ReflogEntry> entries = reader == null ? List.of() : reader.getReverseEntries();
                    System.out.println("  JGit reads " + entries.size() + " HEAD reflog entries");
                    for (ReflogEntry entry : entries) {
                        if (entry.getComment().startsWith("checkout:")) {
                            System.out.println("    " + entry.getComment());
                        }
                    }
                }
                case "gc-now" -> {
                    @SuppressWarnings("deprecation")
                    var stats = git.gc().setExpire(new Date()).call();
                    System.out.println("gc stats: " + stats);
                }
                default -> throw new IllegalArgumentException(op);
            }
        } catch (Exception e) {
            System.out.println(op + " FAILED: " + e);
        }
    }
}
