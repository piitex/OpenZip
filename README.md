# OpenZip

A free archive utility for macOS, Windows and Linux, built on [RenGL](../RenGL). It exists because macOS has no good
free zipping app that isn't asking for money.

- **Create** `.zip`, `.7z`, `.tar.gz`, `.tar.xz`, `.tar.bz2` and `.tar` with a compression level you choose.
- **Open and extract** all of those, and **`.rar`** (RAR 3, 4 and 5, solid or not, with or without a password; `.cbr` comic
  archives too), and a single compressed file such as `access.log.gz`, `.xz` or `.bz2` (shown as the one file inside; a
  `.gz` that holds a tarball opens as the tar). RAR can be read but not created: only RARLAB's own software may write it. Browse the contents one folder at a time with a path bar, sortable Name, Size,
  Packed and Modified columns, and extract everything, or just the files and folders you selected (click, Shift/Ctrl-click,
  or drag a selection box from the empty space below the rows; Ctrl toggles and Shift adds, and it scrolls by itself at the
  top and bottom edge). Double-click a file
  to open it with the program the system uses for it (it is extracted to a scratch folder that goes when OpenZip quits;
  programs are never run). Text files (`.txt`, `.md`, `.sh`, `.json`, source code and the like) open in the system's
  text editor, scripts included, since an editor never runs them. **Space** or *Preview* in the right-click menu shows
  one inside OpenZip instead: Markdown set out as a document (switchable to its source), everything else as source
  with line numbers and colored comments, strings and keywords. It is a viewer, not an editor: nothing can be saved
  back into an archive. Drop an archive on the window to open it.
- **Add to an archive by dropping:** with a `.zip`, `.tar`, `.tar.gz`, `.tar.xz` or `.tar.bz2` open, drop files or folders
  on the window and they are added to the folder being shown. A zip keeps what is already in it as it is, not unpacked and
  compressed again. A tar has to be written again from its old entries (same compression as before), so a big `.tar.gz` takes
  about as long as making it; progress and Cancel are shown. The archive is replaced only once the new one is complete, so a
  failure or Cancel leaves it untouched. A name that is already there is replaced after you confirm (a folder is merged into).
  With no archive open, or a 7z or rar open, dropped files go to the compress dialog instead.
- **Delete from an archive:** select files or folders in an open `.zip`, `.tar`, `.tar.gz`, `.tar.xz` or `.tar.bz2` and press
  **Delete** (**Cmd+Backspace** on a Mac), or use *Delete...* in the right-click menu or the Archive menu. It asks first; a
  folder takes everything in it along. It is written the same way as adding: a zip keeps what stays as it is, a tar is
  written again, and the archive is replaced only once the new one is complete. Not for password protected zips, 7z, RAR
  or an archive opened from inside another.
- **Drag out of an archive** (Windows): select files or folders and drag them past the edge of the window onto Explorer
  or the desktop. They are extracted in the background as soon as the pointer leaves the window, into a scratch folder
  that goes when OpenZip quits, and the target copies them from there. The window stays responsive and shows progress
  with Cancel; the drop only waits if the extraction is still running when you let go. Cancelling removes what was
  extracted so far. A drag you abandon still extracts until you press Cancel. Works for every format OpenZip can read.
  Dragging a row that is one of several selected takes them all. A password protected archive needs its password entered
  once first (Extract Selected asks for it). The drag shows the system's plain cursor. macOS and Linux can't do this
  yet: RenGL starts the system drag on Windows only.
- **Right-click in Finder** (macOS): the installed app asks once, on its first launch, and *File ▸ Add to Finder's Right-Click Menu* adds or removes
  "Compress with OpenZip" under Quick Actions for any file or folder; it opens OpenZip with the compress dialog. It is a
  small workflow in `~/Library/Services` that opens the app by its bundle id (`me.piitex.openzip`), so it needs the
  packaged app. Linux file managers are not done.
- **Right-click in Explorer** (Windows): *Open with OpenZip* on archives (`.zip .7z .rar .tar .tgz .txz .tbz2 .tbz .gz .xz .bz2 .cbr`; Windows sees `foo.tar.gz` as a `.gz`)
  and, on folders, *Compress to ZIP* (makes `folder.zip` next to it at once, or `folder 2.zip` if that is taken) and
  *Compress with OpenZip...* (the compress dialog). The setup adds the entries and its uninstaller removes them
  (`shellVerb` lines in `rengl-package.properties`, the RenGL packager's `--shell-verb`).
  `OpenZip.exe --compress=zip "<folder>"` also works from the command line. *Compress to ZIP* doesn't open the main window: a small progress window (current file,
  percentage, Cancel) appears only if it takes more than half a second, says what it made with a *Show in Explorer*
  button, and goes by itself after a few seconds; a small folder is zipped with nothing on screen at all. Windows 11
  shows the entries under *Show more options*. Selecting several folders opens one window per folder.
- **Passwords:** zip and 7z archives can be protected with AES-256, and password-protected zips (AES or the older
  ZipCrypto), 7z and RAR archives open. The names can be browsed without the password; you are asked for it when you
  extract or test, and asked again if it was wrong. A failed extraction never leaves a half-written file behind.
- **Test** an archive: every file is read and its checksum checked.
- **Made for Macs:** Finder's `.DS_Store`, `._*` and `__MACOSX` clutter is left out of the archives you make and is not
  extracted from the ones you receive. Permissions, the executable bit and symlinks are kept (the JDK's own zip classes
  lose them). Archives made by Finder, `zip` and `tar` open here, and ours open in `unzip`, `tar` and `ditto`.
- **Safe by default:** an archive is untrusted input. Names that climb out of the destination (`../`, absolute paths,
  drive letters), links that point outside it, and writing through a link already in the destination are refused and
  reported. On Windows, names that system can't store or would store somewhere else (`file.txt:stream`, which writes
  into a hidden alternate data stream, device names like `NUL` and `CON`, a trailing dot or space) are skipped and reported too. Existing files are never merged into by "Extract All": it makes `name`, `name 2`, ... instead.
- A failed or cancelled compression never leaves a half-written archive and never touches an existing one.
- **Light and dark**, following the system (and switching when it does) or set from the View menu. The choice is saved.

## Run it

Needs JDK 25+ and the RenGL snapshot installed in your local Maven repository (`./mvnw install -DskipTests` in RenGL; do it
again when RenGL changes, since the snapshot's version doesn't). OpenZip depends on `rengl-core` only.
Encrypted zips use [zip4j](https://github.com/srikanth-lingala/zip4j) and RAR uses
[7-Zip-JBinding](https://sevenzipjbind.sourceforge.net/) (7-Zip's own code, so RAR 5 works); Maven fetches both.

```bash
./mvnw compile exec:exec
```

```bash
./mvnw test
```

On Windows (x64) use `mvnw.cmd` in PowerShell or `./mvnw` in Git Bash; the commands are the same.

On macOS the JVM needs `-XstartOnFirstThread`; the `exec:exec` setup in the pom passes it for you, and only there (a
Windows or Linux JVM refuses to start with it). The pom picks RenGL's natives jar for your OS and CPU by itself, and
brings 7-Zip-JBinding's for every platform (about 2 MB each) so that any image the packager builds can read `.rar`.
7-Zip-JBinding has no Windows ARM build, so `.rar` is meant to report that it can't be opened there while everything else
works (not tried on that hardware).

**Building the app** is RenGL's packager, which makes Windows, Linux and macOS builds from one computer. The settings
(name, icons, file types, the Explorer right-click entries) are in `rengl-package.properties`; run `.\package.cmd` in this
folder, or `.\package.cmd -Installer plain` for a Windows `setup.exe`, `-Platform linux-x64` for one platform. It needs
JDK 25 and RenGL installed once with `.\mvnw.cmd install -DskipTests` in the RenGL folder (repeat after changing RenGL: the
snapshot version stays the same). See RenGL's `docs/reference/packaging.md`.
This project depends on `rengl-core` only (the whole engine would add about 50 MB); `package-app.ps1` reads the engine
version and natives from either artifact. Install the natives for every platform you build for (RenGL's
`install-natives.ps1`) before the first all-platform build.

## Layout

| Package | What is in it |
|---|---|
| `me.piitex.openzip.archive` | The library: reading, writing, safety checks, progress and cancel. No UI, fully unit-tested. |
| `me.piitex.openzip.ui` | The RenGL window: header, the archive's folder table and path bar on a rounded panel, status line, the dialogs. `Palette` holds every color of the light and dark schemes and `OpenZipTheme` hands them to the engine's themed widgets; `Appearance` picks and remembers the scheme; `Components` the shared pills, icon buttons and rows. |
| `packaging/` | What `rengl-package.properties` points at: the icons and the macOS document types (`document-types.plist`). |

`src/test/.../ui/Screenshot.java` is a developer tool that renders the real window to a PNG, for checking the layout
without clicking through it.

## Not done yet

- **Searching inside an archive.** The table shows one folder at a time, so finding a file deep in a big archive means
  clicking down to it. A search box that lists matches from every folder would fix that.
- **Markdown in the preview** has headings, lists, numbered lists and code blocks, but not bold, italic, links or
  tables (RenGL's Markdown preset leaves those as plain text); "Show Source" has them all.
- **Adding to other archives.** Only zip and tar can take new entries. Dropping on a password protected zip, or on an
  archive opened from inside another archive (the change wouldn't reach the original), is refused with a message; 7z would
  need a full rewrite, RAR can't be written, and a single `.gz` file has nothing to add to. There is no menu item or button
  for adding yet, only the drop. Deleting is limited the same way (and password protected zips can't be deleted from: copied
  as they are, their AES entries came out unreadable), and renaming or moving entries isn't there at all.
- **Remembering the sort and column widths** between launches. They reset when an archive is opened.
- **Windows (x64)** builds, passes its tests, launches, and opens and extracts archives, from `exec:exec` and from the
  `OpenZip.exe` the RenGL packager builds (checked on the engine as of 2026-11-01: the main window, and *Compress to ZIP*
  on a small folder, silently). The packager's `setup.exe` was installed silently into a scratch folder and checked: the
  four right-click keys and the *Open with* ProgIDs are written with the right commands, Explorer (`Shell.Application`)
  lists *Compress to ZIP* and *Compress with OpenZip...* on a folder and *Compress to ZIP* zipped a folder whose name has a
  space, and the uninstaller left no registry entry behind. The progress window was checked on a 300 MB folder in all its
  states: progress, *Created*, Cancel (no partial file) and a failure (a locked file). Not tried: double-clicking an archive
  in Explorer (file association, `Engine.onOpenFiles`), `Browser.reveal` ("Show in Explorer") and opening a text file in
  Notepad. The installer is unsigned, so SmartScreen warns on it. The entries are in the classic menu, not the short
  Windows 11 one: that needs a native shell extension, which this project doesn't have.
- **Linux** has not been run: file association, `Browser.reveal`, opening a text file in the default editor, and the
  `.deb` build are written from the platform's documentation.
- **RAR** is read only. Multi-volume sets (`.part1.rar`, `.r00`) aren't joined: the other parts are never looked for, so
  anything that continues past the first part can't be extracted. A RAR that also encrypts its file names (`rar -hp`) is
  handled in the code (it asks for the password to list them) but has not been tried against a real one. The tests use
  RAR 4 and 5 archives built by `RarFixtures` (stored, not compressed, and checked against 7-Zip); compressed archives
  were checked by hand against 7-Zip's own extraction, not in the test suite, because no RAR can be made without RARLAB's software.
- **Signing and notarizing the macOS app.** The RenGL packager doesn't do it, and the old jpackage script that had the
  steps (`codesign` with an entitlements file, then `notarytool`) is gone from this folder. Sign `OpenZip.app` yourself
  with an Apple Developer ID. Unsigned, Gatekeeper warns.
- **Password limits.** Only AES-256 can be created (Finder and the system `unzip` can't open AES zips; OpenZip, 7-Zip
  and `bsdtar` can). Tar has no encryption. 7z file *names* can't be hidden when creating (opening such archives works).
- **Dragging rows out to Finder** (macOS) and Linux file managers. RenGL's `Window.startFileDrag` is Windows only
  (`FileDrag.isSupported()`), so on those systems a drag simply ends at the window edge; use Extract Selected.
- Dock/taskbar progress, a "done" notification and a title for the file dialogs need RenGL; see its `Update.md`.
