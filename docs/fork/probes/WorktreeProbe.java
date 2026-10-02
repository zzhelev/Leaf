import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.submodule.SubmoduleWalk;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fork-only probe backing docs/fork/architecture-notes.md. Mirrors the JGit calls Gitnuro makes (as of upstream
 * 62442f26) and runs them against throwaway repos. Driven by worktree-probe.sh; never point it at a real repository.
 *
 * Usage: java -cp <jgit + deps> WorktreeProbe.java <op> <path> [branch]
 *   gitnuro-open <dir>        OpenRepositoryGitAction.invoke + JGit.provide's Git.open(gitDir)
 *   git-open <dir>            plain Git.open(dir)
 *   checkout <gitDir> <name>  CheckoutBranchGitAction for a local branch
 *   delete <gitDir> <ref>     DeleteBranchGitAction (always forced)
 *   gc-now <gitDir>           GarbageCollectCommand with prune expiry "now"
 *   gc-default <gitDir>       GarbageCollectCommand with default settings (what autoGC runs once triggered)
 */
public class WorktreeProbe {
    public static void main(String[] args) throws Exception {
        String op = args[0];
        File path = new File(args[1]);

        switch (op) {
            case "gitnuro-open" -> gitnuroOpen(path);
            case "git-open" -> describe(Git.open(path));
            case "checkout" -> {
                try (Git git = Git.open(path)) {
                    git.checkout().setName(args[2]).call();
                    System.out.println("checkout OK, HEAD now: " + git.getRepository().getFullBranch());
                } catch (Exception e) {
                    System.out.println("checkout FAILED: " + e);
                }
            }
            case "delete" -> {
                try (Git git = Git.open(path)) {
                    List<String> deleted = git.branchDelete().setBranchNames(args[2]).setForce(true).call();
                    System.out.println("delete returned: " + deleted);
                } catch (Exception e) {
                    System.out.println("delete FAILED: " + e);
                }
            }
            case "gc-now" -> {
                try (Git git = Git.open(path)) {
                    @SuppressWarnings("deprecation")
                    var stats = git.gc().setExpire(new Date()).call();
                    System.out.println("gc stats: " + stats);
                }
            }
            case "gc-default" -> {
                try (Git git = Git.open(path)) {
                    System.out.println("gc stats: " + git.gc().call());
                }
            }
            default -> throw new IllegalArgumentException(op);
        }
    }

    /** Port of OpenRepositoryGitAction.invoke, followed by JGit.provide's Git.open(repositoryPath). */
    private static void gitnuroOpen(File directory) throws Exception {
        File[] children = directory.listFiles();
        boolean dotGitIsFile = children != null
                && Arrays.stream(children).anyMatch(f -> f.getName().equals(".git") && f.isFile());
        Repository repository;
        if (dotGitIsFile) {
            System.out.println("[gitnuro] .git is a file -> openSubmoduleRepository");
            File parent = getRepositoryParent(directory);
            if (parent == null) {
                System.out.println("[gitnuro] RESULT: throws InvalidDirectoryException(\"Submodule's parent repository not found\")");
                return;
            }
            System.out.println("[gitnuro] parent repository found at " + parent);
            Repository parentRepo = openRepository(parent);
            String prefix = Pattern.quote(parentRepo.getDirectory().getParent() + File.separator);
            String relative = directory.getAbsolutePath().replaceFirst("^" + prefix, "");
            System.out.println("[gitnuro] SubmoduleWalk.getSubmoduleRepository(parent, \"" + relative + "\")");
            repository = SubmoduleWalk.getSubmoduleRepository(parentRepo, relative);
            if (repository == null) {
                System.out.println("[gitnuro] RESULT: null -> OpenRepoError.RepositoryNotFoundInPath");
                return;
            }
        } else {
            repository = openRepository(directory);
        }
        repository.getWorkTree();
        String tabPath = repository.getDirectory().getAbsolutePath();
        System.out.println("[gitnuro] tab repositoryPath = " + tabPath);
        describe(Git.open(new File(tabPath)));
    }

    private static Repository openRepository(File directory) throws Exception {
        File gitDirectory;
        if (directory.getName().equals(".git")) {
            gitDirectory = directory;
        } else {
            File gitDir = new File(directory, ".git");
            gitDirectory = gitDir.exists() && gitDir.isDirectory() ? gitDir : directory;
        }
        return new FileRepositoryBuilder().setGitDir(gitDirectory).readEnvironment().findGitDir().build();
    }

    private static File getRepositoryParent(File directory) {
        if (directory == null) return null;
        File[] children = directory.listFiles();
        if (children != null && Arrays.stream(children).anyMatch(f -> f.getName().equals(".git") && f.isDirectory())) {
            return directory;
        }
        return getRepositoryParent(directory.getParentFile());
    }

    private static void describe(Git git) throws Exception {
        Repository repo = git.getRepository();
        System.out.println("  directory       = " + repo.getDirectory());
        System.out.println("  commonDirectory = " + repo.getCommonDirectory());
        System.out.println("  workTree        = " + repo.getWorkTree());
        System.out.println("  fullBranch      = " + repo.getFullBranch());
        for (Ref ref : git.branchList().call()) {
            System.out.println("  branch " + ref.getName() + " -> " + ref.getObjectId().abbreviate(7).name());
        }
        Status status = git.status().call();
        System.out.println("  status clean=" + status.isClean() + " added=" + status.getAdded()
                + " modified=" + status.getModified() + " untracked=" + status.getUntracked());
    }
}
