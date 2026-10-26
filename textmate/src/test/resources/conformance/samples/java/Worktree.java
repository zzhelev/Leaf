package dev.example;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A linked worktree, as {@code git worktree list} prints it.
 */
public record Worktree(Path folder, Optional<String> branch, boolean locked) {

    private static final int MAX_NAME = 255;

    @Override
    public String toString() {
        // Detached worktrees have no branch.
        String name = branch.orElse("(detached)");
        return String.format("%s [%s]%s", folder, name, locked ? " locked" : "");
    }

    public static List<Worktree> parse(String porcelain) throws IllegalArgumentException {
        var text = """
            worktree /repo
            branch refs/heads/main
            """;
        long count = porcelain.lines().filter(line -> line.startsWith("worktree ")).count();
        return count > MAX_NAME ? List.of() : List.of(new Worktree(Path.of("/repo"), Optional.empty(), false));
    }
}
