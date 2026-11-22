// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

// Fork-only. A template: auto-update-probe.sh fills in %VERSION% and %RESULTS%, then packages it twice.

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Step 0 of docs/fork/auto-update.md. Version 1 writes a plain file and downloads one (quarantine?), copies the
 * staged version 2 bundle next to its own bundle, starts a helper that swaps the two once it has exited, and exits.
 * Version 2 only notes that it started, and from where.
 */
public class AutoUpdateProbe {
    static final int VERSION = %VERSION%;
    static final Path RESULTS = Path.of("%RESULTS%");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(RESULTS);
        String javaHome = System.getProperty("java.home");
        // <bundle>/Contents/runtime/Contents/Home
        Path bundle = Path.of(javaHome).getParent().getParent().getParent().getParent();

        String info = "version=" + VERSION + "\n"
            + "java.home=" + javaHome + "\n"
            + "bundle=" + bundle + "\n"
            + "jpackage.app-path=" + System.getProperty("jpackage.app-path") + "\n"
            + "jpackage.app-version=" + System.getProperty("jpackage.app-version") + "\n"
            + "pid=" + ProcessHandle.current().pid() + "\n"
            + "command=" + ProcessHandle.current().info().command().orElse("?") + "\n";
        Files.writeString(RESULTS.resolve("started-v" + VERSION + ".txt"), info);

        if (VERSION != 1) {
            return;
        }

        Files.writeString(RESULTS.resolve("plain-file.txt"), "written by the test app\n");

        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://raw.githubusercontent.com/zzhelev/Leaf/main/latest.json")
            ).build();
            HttpResponse<Path> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofFile(RESULTS.resolve("downloaded.json"))
            );
            log("download status " + response.statusCode());
        } catch (Exception e) {
            log("download failed " + e);
        }

        Path stagedFile = RESULTS.resolve("staged-v2-path.txt");

        if (!Files.exists(stagedFile)) {
            log("no staged version 2, stopping");
            return;
        }

        Path staged = Path.of(Files.readString(stagedFile).trim());
        Path prepared = bundle.resolveSibling(".LeafUpdateProbe-2.app");
        Path old = bundle.resolveSibling(".LeafUpdateProbe-1-old.app");

        int ditto = run("/usr/bin/ditto", staged.toString(), prepared.toString());
        log("ditto into " + prepared + " exit " + ditto);

        if (ditto != 0) {
            return;
        }

        Path script = RESULTS.resolve("swap.sh");
        Files.writeString(script, """
            #!/bin/sh
            pid="$1"; bundle="$2"; prepared="$3"; old="$4"; log="$5"
            while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
            echo "app exited" >> "$log"
            if ! mv "$bundle" "$old" >> "$log" 2>&1; then
              echo "mv bundle->old failed" >> "$log"; echo done >> "$log"; exit 1
            fi
            echo "mv bundle->old ok" >> "$log"
            if ! mv "$prepared" "$bundle" >> "$log" 2>&1; then
              echo "mv prepared->bundle failed, putting the old one back" >> "$log"
              mv "$old" "$bundle" >> "$log" 2>&1; echo "mv old->bundle exit $?" >> "$log"
              echo done >> "$log"; exit 1
            fi
            echo "mv prepared->bundle ok" >> "$log"
            /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$bundle" >> "$log" 2>&1
            echo "lsregister exit $?" >> "$log"
            open "$bundle" >> "$log" 2>&1; echo "open exit $?" >> "$log"
            rm -rf "$old" >> "$log" 2>&1; echo "rm old exit $?" >> "$log"
            echo done >> "$log"
            """);

        new ProcessBuilder(
            "/bin/sh", script.toString(),
            String.valueOf(ProcessHandle.current().pid()),
            bundle.toString(), prepared.toString(), old.toString(),
            RESULTS.resolve("swap.log").toString()
        )
            .redirectErrorStream(true)
            .redirectOutput(RESULTS.resolve("swap.out").toFile())
            .start();

        log("helper started");
        System.exit(0);
    }

    static int run(String... command) throws IOException, InterruptedException {
        return new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(RESULTS.resolve("app.log").toFile()))
            .start()
            .waitFor();
    }

    static void log(String line) throws IOException {
        Files.writeString(
            RESULTS.resolve("app.log"), line + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        );
    }
}
