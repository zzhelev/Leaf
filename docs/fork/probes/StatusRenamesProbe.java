// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.blame.BlameGenerator;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.ContentSource;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.util.io.NullOutputStream;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Fork-only probe backing docs/fork/status-renames.md: what JGit 7.7 reports for a moved file in the status, what its
 * rename detector pairs on the staged side (HEAD against the index) and the unstaged side (the index against the
 * working tree, untracked files included), and what its blame, reset and checkout do with one. Driven by
 * status-renames-probe.sh; never point it at a real repository.
 *
 * Usage: java -cp <jgit + deps> StatusRenamesProbe.java <op> <workTree> [paths]
 *   status <dir>             JGit's status sets, and the index entries marked intent-to-add
 *   staged <dir>             HEAD against the index, with renames at git's score (50)
 *   unstaged <dir>           the index against the working tree (untracked files included), with renames at 50
 *   blame <dir> <path>       BlameCommand with setFollowFileRenames(true), as Leaf runs it: each line's commit and path
 *   blame-moved <dir> <old> <new>  BlameGenerator from the old path at HEAD, with the new path's file as uncommitted
 *   log <dir> <path>         LogCommand.addPath, as Leaf's file history runs it
 *   reset <dir> <paths...>   ResetCommand.addPath for each path, as Leaf's Unstage does
 *   checkout <dir> <paths..> CheckoutCommand.addPath for each path, as Leaf's Discard does after its reset
 *   commit <dir> <message>   CommitCommand, as Leaf commits what is staged
 */
public class StatusRenamesProbe {
    public static void main(String[] args) throws Exception {
        String op = args[0];
        try (Git git = Git.open(new File(args[1]))) {
            Repository repo = git.getRepository();
            switch (op) {
                case "status" -> status(git);
                case "staged" -> print(renames(repo, headTree(repo), new DirCacheIterator(repo.readDirCache())));
                case "unstaged" -> print(renames(repo, new DirCacheIterator(repo.readDirCache()), new FileTreeIterator(repo)));
                case "blame" -> blame(git, args[2]);
                case "blame-moved" -> blameMoved(git, args[2], args[3]);
                case "log" -> {
                    List<String> subjects = new ArrayList<>();
                    for (RevCommit c : git.log().addPath(args[2]).call()) subjects.add(c.getShortMessage());
                    System.out.println("  log " + args[2] + ": " + subjects);
                }
                case "reset" -> {
                    var reset = git.reset();
                    for (int i = 2; i < args.length; i++) reset.addPath(args[i]);
                    reset.call();
                    System.out.println("  jgit reset done");
                }
                case "commit" -> {
                    try {
                        RevCommit c = git.commit().setMessage(args[2]).setSign(false).call();
                        System.out.println("  jgit commit made " + c.abbreviate(7).name());
                    } catch (Exception e) {
                        System.out.println("  jgit commit threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
                case "checkout" -> {
                    var checkout = git.checkout();
                    for (int i = 2; i < args.length; i++) checkout.addPath(args[i]);
                    try {
                        checkout.call();
                        System.out.println("  jgit checkout done");
                    } catch (Exception e) {
                        System.out.println("  jgit checkout threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
                default -> throw new IllegalArgumentException(op);
            }
        }
    }

    private static void status(Git git) throws Exception {
        Status s = git.status().call();
        System.out.println("  jgit added=" + new TreeSet<>(s.getAdded())
                + " changed=" + new TreeSet<>(s.getChanged())
                + " removed=" + new TreeSet<>(s.getRemoved())
                + " missing=" + new TreeSet<>(s.getMissing())
                + " modified=" + new TreeSet<>(s.getModified())
                + " untracked=" + new TreeSet<>(s.getUntracked())
                + " conflicting=" + new TreeSet<>(s.getConflicting()));
        DirCache index = git.getRepository().readDirCache();
        for (int i = 0; i < index.getEntryCount(); i++) {
            DirCacheEntry e = index.getEntry(i);
            if (e.isIntentToAdd()) System.out.println("  jgit index: " + e.getPathString() + " is intent-to-add");
        }
    }

    private static AbstractTreeIterator headTree(Repository repo) throws Exception {
        try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            RevCommit head = walk.parseCommit(repo.resolve("HEAD"));
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, head.getTree());
            return parser;
        }
    }

    private static List<DiffEntry> renames(Repository repo, AbstractTreeIterator a, AbstractTreeIterator b) throws Exception {
        List<DiffEntry> scanned;
        try (DiffFormatter f = new DiffFormatter(NullOutputStream.INSTANCE)) {
            f.setRepository(repo);
            f.setDetectRenames(false);
            scanned = f.scan(a, b);
        }
        try (ObjectReader reader = repo.newObjectReader()) {
            RenameDetector detector = new RenameDetector(repo);
            detector.setRenameScore(50);
            detector.addAll(scanned);
            ContentSource blobs = ContentSource.create(reader);
            ContentSource newSide = b instanceof WorkingTreeIterator
                    ? ContentSource.create(new FileTreeIterator(repo))
                    : blobs;
            return detector.compute(new ContentSource.Pair(blobs, newSide), NullProgressMonitor.INSTANCE);
        }
    }

    private static void print(List<DiffEntry> entries) {
        if (entries.isEmpty()) System.out.println("  (no changes)");
        for (DiffEntry e : entries) {
            String path = switch (e.getChangeType()) {
                case ADD -> e.getNewPath();
                case DELETE, MODIFY -> e.getOldPath();
                default -> e.getOldPath() + " -> " + e.getNewPath() + " (" + e.getScore() + "%)";
            };
            System.out.println("  " + e.getChangeType() + " " + path);
        }
    }

    /**
     * A blame that starts from the old path at HEAD, with the new path's file on disk as the uncommitted version, which
     * is what BlameCommand does for a path that HEAD has.
     */
    private static void blameMoved(Git git, String oldPath, String newPath) throws Exception {
        Repository repo = git.getRepository();
        byte[] onDisk = java.nio.file.Files.readAllBytes(new File(repo.getWorkTree(), newPath).toPath());
        try (BlameGenerator generator = new BlameGenerator(repo, oldPath)) {
            generator.setFollowFileRenames(true);
            // Oldest first, as BlameCommand pushes HEAD, then the index, then the working tree
            generator.push(null, repo.resolve("HEAD"));
            generator.push("uncommitted", new RawText(onDisk));
            BlameResult result = BlameResult.create(generator);
            result.computeAll();
            print(result, oldPath + " (moved to " + newPath + ")");
        }
    }

    private static void blame(Git git, String path) throws Exception {
        BlameResult result = git.blame().setFilePath(path).setFollowFileRenames(true).call();
        print(result, path);
    }

    private static void print(BlameResult result, String path) {
        if (result == null) {
            System.out.println("  blame " + path + ": null");
            return;
        }
        for (int i = 0; i < result.getResultContents().size(); i++) {
            RevCommit c = result.getSourceCommit(i);
            System.out.println("  blame line " + (i + 1) + ": "
                    + (c == null ? "not committed" : c.getShortMessage()) + " @ " + result.getSourcePath(i));
        }
    }
}
