# Leaf - Multiplatform Git Client

## Built on Gitnuro

Leaf is built on [Gitnuro](https://github.com/JetpackDuba/Gitnuro), the open source Git client created by Abdelilah El
Aissaoui with the help of its contributors. Gitnuro offered a foundation very close to one I had in mind and was
planning to build myself, and I'm grateful for the work and care that went into it.

Leaf develops that foundation along a different roadmap, centred on first-class support for git worktrees in workflows
where AI coding agents work side by side. Those ideas needed to move quickly, so Leaf continues as its own project. It
was forked from Gitnuro's 2.0 development branch in October 2026 and keeps Gitnuro's license, the GNU General Public
License v3.0.

If both projects keep evolving and their directions line up, I'd be glad to see work and ideas shared between them, in
either direction.

## About

A FOSS Git client based on (Jetbrains) Compose and JGit.

Leaf shares Gitnuro's goal of a multiplatform open source Git client, without any kind of constraint to how you can use
it and without relying on web technologies.

## Download/Install

Leaf has no published releases yet. To build and run it from source, see [DEVELOPMENT.md](DEVELOPMENT.md).

## Features

Leaf has support for the following features:

- View diffs for text based files.
- View your history log and all its branches.
- Add (stage) & reset (unstage) files.
- Stage & unstage of hunks.
- Checkout files (revert changes of uncommitted files).
- Clone.
- Commit.
- Reset commits.
- Revert commits.
- Amend previous commit.
- Merge.
- Rebase.
- Create and delete branches locally.
- Create and delete tags locally.
- View remote branches.
- Pull and push.
- Stash and pop stash.
- Checkout a commit (detached HEAD).
- View changes/diff in images (side to side comparison).
- Force push.
- Remove branches from remote.
- Manage remotes.
- Start a new local repository.
- Search by commit message/author/commit id.
- Rebase interactive.
- Blame file.
- View file history.
- Theming.
- Side by side diff in text files.
- Stage/Unstage specific lines.
- Submodules support.
- Change the tracking of a specific branch.

<details>
  <summary><b>Features planned</b></summary>

- Create/Apply patches
- Remove tags from remote.
- View stashes in the log tree.
- Syntax highlighting for diff.
- Various log options like showing the author, filtering by current branch o hide remote branches.
- Customizations settings.

</details>

## Contributing

If you find a bug or you would like to suggest a new feature, feel free to open an issue.

Pull requests are also welcome but please create an issue first if it's a new feature. If you want to work on an
existing issue, please comment so I'm aware of it and discuss the changes if required.
See [this page](DEVELOPMENT.md) for how to set up your development environment.

## FAQ

> Is Leaf completely free?

Yes, free in both meanings of the word (in money and freedom).

> Does Leaf keep track of my data?

Leaf does not track data in any way, don't worry.

> I don't like the built-in themes, can I create a custom one?

Leaf includes the option to set custom themes in a JSON format. Keep in mind that themes may break with new releases,
making the default theme the fallback option.

For the latest stable version, you can use this JSON as an example:

```
{
    "primary": "FF456b00",
    "primaryVariant": "FF456b00",
    "onPrimary": "FFFFFFFFF",
    "secondary": "FF9c27b0",
    "onBackground": "FF141f00",
    "onBackgroundSecondary": "FF595858",
    "error": "FFc93838",
    "onError": "FFFFFFFF",
    "background": "FFe7f2d3",
    "backgroundSelected": "C0cee1f2",
    "surface": "FFc5f078",
    "secondarySurface": "FFedeef2",
    "tertiarySurface": "FFF4F6FA",
    "addFile": "FF32A852",
    "deletedFile": "FFc93838",
    "modifiedFile": "FF0070D8",
    "conflictingFile": "FFFFB638",
    "dialogOverlay": "AA000000",
    "normalScrollbar": "FFCCCCCC",
    "hoverScrollbar": "FF0070D8",
    "diffLineAdded": "FF0070D8",
    "diffLineRemoved": "FF0070D8",
    "onSecondary": "FF000000",
    "diffContentAdded": "FF000000",
    "diffContentRemoved": "FF000000",
    "diffKeyword": "FF000000", 
    "diffAnnotation": "FF000000",
    "diffComment": "FF000000",
    "isLight": true
}
```

Colors are in ARGB Hex format.

> Why isn't the Mac version signed?

Leaf isn't distributed as a signed and notarized app yet. A build you make yourself is signed ad hoc and runs on the
machine that built it.

> Authentication has failed. What's wrong?

Currently there are some limitations regarding this topic. Here are some known problematic setups, tracked in
Gitnuro's issues:

- Multicast DNS remote URL (https://github.com/JetpackDuba/Gitnuro/issues/19) with this
  workaround (https://github.com/JetpackDuba/Gitnuro/issues/19#issuecomment-1374431720).
- Self signed server certificate (https://github.com/JetpackDuba/Gitnuro/issues/48)

If the authentication fails and you think its due to a different reason, please open a new issue.


> Does it support Git credentials manager (aka manager-core)?

Yes, but it requires specifying the full path of the binary in your `.gitconfig`.

Example for linux:

```
[credential]
   helper = /usr/share/git-credential-manager-core/git-credential-manager-core
```

Example for windows (you may want to edit `C:\Program Files\Git\etc\gitconfig`):

```
[credential]
   helper = C:/Program Files/Git/mingw64/bin/git-credential-manager-core.exe
```

## License

Leaf is licensed under the [GNU General Public License v3.0](LICENSE), like Gitnuro.

