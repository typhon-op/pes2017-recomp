package com.pes2017.recomp

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile

/**
 * PATCHES: the data pack installed beside the game, the edit save that goes
 * with it, and the backups both of them keep.
 *
 * The layout is the one the runtime already uses (docs and SaveBackup): the
 * runtime runs from the game folder and reads its saves from `saves/` in the
 * folder above it, so everything here is addressed from the same root -- the
 * game folder's parent. The game folder holds `default.xex`; a patch is a data
 * pack (the `.cpk` archives plus `DpFileList.bin`) and lives in
 * `dlc/SYSTEM00000000`, which the runtime mounts as the title's data-pack
 * drive. The game folder itself is never written; EDITED DATA is
 * `saves/EDIT00000000`.
 *
 * Three rules decide the shape of this file:
 *
 *  - **Nothing is replaced before it is kept.** Every operation that changes a
 *    slot first moves what is there into `patch-backups/<slot>/<time>` with a
 *    manifest of its file count and size. A backup directory is never reused
 *    or overwritten: a second backup in the same second gets a suffix.
 *
 *  - **A backup is checked before it is put back.** The manifest is read back
 *    and the directory's files and bytes counted against it; a backup that is
 *    short a file, or a file that is empty, is refused rather than restored.
 *
 *  - **`SYSTEM00000000` is only written when the player ticks it.** It holds
 *    progress for every mode, so the import screen keeps it behind a separate
 *    switch and a confirmation, and the save in place is backed up first.
 */
object PatchStore {

    /** The file the game is; its presence is what makes a folder a game folder. */
    const val XEX = GameFolder.XEX

    /** The edit save; the only file EDITED DATA touches. */
    const val EDIT = "EDIT00000000"

    /** The shared system save; read for status, never modified. */
    const val SYSTEM = "SYSTEM00000000"

    /** The list that orders a data pack's archives. */
    const val DP_FILE_LIST = "DpFileList.bin"

    /**
     * The backup slot of INSTALL FOLDER WITHOUT DPFILIST.BIN: the game files
     * that source replaced. It is kept apart from `patch` and `edit` so that
     * restoring a data pack never puts a game file back over a newer one, and
     * so the PATCHES screen keeps listing only the packs it restores.
     */
    const val GAME_FILES = "game"

    private const val PATCH_SLOT = "dlc/SYSTEM00000000"
    private const val SAVES = "saves"
    private const val BACKUPS = "patch-backups"
    private const val STAGING = "patch-staging"
    private const val MANIFEST = "backup.properties"
    private const val STATE = "state.properties"

    /** The largest edit save this app will accept; a real one is a few MB. */
    private const val MAX_EDIT_BYTES = 64L * 1024 * 1024

    /** The message a cancelled [installPatch] fails with; the screen checks for it. */
    const val CANCELLED = "the install was cancelled"

    /** What is in one slot: a data pack is a directory, a save is one file. */
    data class Slot(val present: Boolean, val files: Int, val bytes: Long) {
        val empty: Boolean get() = !present || files == 0
    }

    /** One kept copy of a slot, with what the manifest said it holds. */
    data class Backup(
        val kind: String,
        val name: String,
        val at: Long,
        val files: Int,
        val bytes: Long,
    )

    /** Everything the PATCHES screen shows before the player does anything. */
    data class Status(
        val game: File?,
        val patch: Slot,
        val edit: Slot,
        val system: Slot,
        val patchAt: Long,
        val patchFrom: String,
        val editAt: Long,
        val editFrom: String,
        val backups: List<Backup>,
    ) {
        val installed: Boolean get() = patch.present && patchAt > 0
    }

    /** A refused operation: [message] is a reason, [detail] names the file. */
    class Failure(message: String, val detail: String = "") : Exception(
        if (detail.isEmpty()) message else "$message: $detail",
    )

    /**
     * Progress of a copy, in bytes; returning false cancels it before the swap.
     * [total] is 0 while the source cannot say how much there is.
     */
    fun interface Progress {
        fun onBytes(copied: Long, total: Long): Boolean
    }

    // ---- where things are ----------------------------------------------------

    /** The chosen game folder, when it still holds the game. */
    fun gameFolder(path: String): File? {
        if (path.isBlank()) return null
        val folder = File(path)
        return if (File(folder, XEX).isFile) folder.absoluteFile else null
    }

    /** The folder above the game folder: where `dlc/`, `saves/` and backups live. */
    fun rootOf(game: File): File = game.absoluteFile.parentFile ?: game.absoluteFile

    fun patchSlot(root: File): File = File(File(root, "dlc"), SYSTEM)

    fun savesDir(root: File): File = File(root, SAVES)

    fun editFile(root: File): File = File(savesDir(root), EDIT)

    fun systemFile(root: File): File = File(savesDir(root), SYSTEM)

    fun backupsRoot(root: File): File = File(root, BACKUPS)

    private fun slotDir(root: File, kind: String): File = File(backupsRoot(root), kind)

    // ---- status --------------------------------------------------------------

    fun status(game: File?): Status {
        if (game == null) {
            return Status(null, Slot(false, 0, 0), Slot(false, 0, 0), Slot(false, 0, 0), 0, "", 0, "", emptyList())
        }
        val root = rootOf(game)
        val state = readState(root)
        return Status(
            game = game,
            patch = slotOf(patchSlot(root)),
            edit = slotOf(editFile(root)),
            system = slotOf(systemFile(root)),
            patchAt = state.first,
            patchFrom = state.second,
            editAt = state.third,
            editFrom = state.fourth,
            backups = listBackups(root).map { it.second.summary },
        )
    }

    private fun slotOf(file: File): Slot {
        if (!file.exists()) return Slot(false, 0, 0)
        if (file.isDirectory) {
            val (files, bytes) = count(file)
            return Slot(true, files, bytes)
        }
        return Slot(true, if (file.isFile) 1 else 0, if (file.isFile) file.length() else 0)
    }

    // ---- installing a patch --------------------------------------------------

    /**
     * Installs an extracted loose payload (a `.cpk` set and `DpFileList.bin`).
     * [source] is the folder the pack was extracted to or a `.zip` of it; the
     * files are written flat into `dlc/SYSTEM00000000`, which is the package
     * the runtime mounts. The game folder itself is never written.
     *
     * The payload is staged first and swapped in only once it is whole, so a
     * cancelled or failed copy leaves the installed pack exactly as it was.
     * Returns the backup made of the pack that was there, if any.
     */
    fun installPatch(game: File, source: File, progress: Progress? = null): Backup? {
        val root = rootOf(game)
        requireGame(game)
        if (!source.exists()) throw Failure("the patch source is missing", source.name)

        val staging = File(File(root, STAGING), "install-" + stamp())
        staging.deleteRecursively()
        try {
            val imported = importPayload(source, staging, progress)
            if (imported.files == 0) {
                throw Failure("not a PES 2017 patch", "no .cpk files and no $DP_FILE_LIST")
            }
            val names = staging.listFiles()
                ?.filter { it.isFile && isPlainName(it.name) }
                ?.map { it.name }
                .orEmpty()
            val hasDp = names.any { it.equals(DP_FILE_LIST, ignoreCase = true) }
            val cpks = names.filter { it.lowercase(Locale.US).endsWith(".cpk") }
            return commitPatch(root, staging, cpks, hasDp, emptyList(), source.name)
                .firstOrNull { it.first == "patch" }
                ?.second
        } finally {
            staging.deleteRecursively()
            // An empty staging parent is noise in the status; drop it.
            File(root, STAGING).listFiles()?.takeIf { it.isEmpty() }?.let { File(root, STAGING).delete() }
        }
    }

    /**
     * Installs what the import screen extracted: the plan's CPKs and
     * DpFileList into `dlc/SYSTEM00000000`, the edit save into
     * `saves/EDIT00000000` and, only when [installSystem] is true, the shared
     * save into `saves/SYSTEM00000000`. Each slot that already holds something
     * is moved to `patch-backups` first.
     *
     * Nothing under the game folder changes: the runtime mounts `dlc/` at
     * launch, which is how the pack reaches the game.
     */
    fun installExtracted(
        game: File,
        staging: File,
        plan: PatchScanner.Plan,
        installSystem: Boolean,
        sourceName: String,
    ): List<Pair<String, Backup>> {
        val root = rootOf(game)
        requireGame(game)
        val cpks = plan.cpks.map { it.name }
        if (cpks.isEmpty() && !plan.dpFileList) {
            throw Failure("not a PES 2017 patch", "the plan holds no data pack")
        }
        for (name in cpks) {
            val file = File(staging, name)
            if (!file.isFile || file.length() == 0L) {
                throw Failure("the extraction has no $name", staging.absolutePath)
            }
        }
        if (plan.dpFileList && !File(staging, DP_FILE_LIST).isFile) {
            throw Failure("the extraction has no $DP_FILE_LIST", staging.absolutePath)
        }
        val saves = plan.saves
            .filter { it.slot == EDIT || (installSystem && it.slot == SYSTEM) }
            .map { it.slot }
            .distinct()
        return commitPatch(root, staging, cpks, plan.dpFileList, saves, sourceName)
    }

    /**
     * Replaces game files from a scanned folder, for a patch that has no
     * DpFileList.bin and only changes files the game already has.
     *
     * Every file it touches is moved into one backup first (the same slot the
     * rest of this file uses), and the source is copied in only once the
     * original is safe. A failure or a cancel puts back every file already
     * replaced and drops the backup, so the game folder is left exactly as it
     * was. Nothing is added: a file the game does not already have is never a
     * match, and an `.xex` is refused here too, whatever the plan says.
     */
    fun replaceGameFiles(
        game: File,
        plan: GameFiles.Plan,
        sourceName: String,
        progress: Progress? = null,
    ): Backup? {
        val root = rootOf(game)
        requireGame(game)
        if (!plan.hasMatches) throw Failure("there is no game file to replace")

        val backupDir = freshBackupDir(root, GAME_FILES)
        backupDir.parentFile?.mkdirs()
        if (!backupDir.mkdirs()) throw Failure("could not make a backup folder", backupDir.absolutePath)
        val kept = ArrayList<Pair<File, File>>()
        var copied = 0L
        val total = plan.payloadBytes

        fun undo() {
            for ((stored, target) in kept.asReversed()) {
                runCatching {
                    target.delete()
                    stored.copyTo(target, overwrite = true)
                }
            }
            backupDir.deleteRecursively()
        }

        try {
            for (match in plan.matches) {
                val target = match.target
                if (!target.isFile) {
                    undo()
                    throw Failure("the file to replace is gone", target.absolutePath)
                }
                if (target.name.lowercase(Locale.US).endsWith(".xex")) {
                    undo()
                    throw Failure("the game executable is never replaced", target.absolutePath)
                }
                if (!match.source.isFile) {
                    undo()
                    throw Failure("the source file is missing", match.source.absolutePath)
                }
                val relative = runCatching { target.relativeTo(game).invariantSeparatorsPath }
                    .getOrNull()
                if (relative.isNullOrEmpty() || relative.startsWith("..") ||
                    relative.split('/').any { it.isEmpty() || it == "." || it == ".." }
                ) {
                    undo()
                    throw Failure("the file is not inside the game folder", target.absolutePath)
                }

                val stored = File(backupDir, relative)
                stored.parentFile?.mkdirs()
                // Kept before it is replaced: moved on the same volume, which
                // is what makes this instant and needs no extra space.
                if (!move(target, stored)) {
                    undo()
                    throw Failure("could not keep the file being replaced", target.absolutePath)
                }
                kept += stored to target

                try {
                    match.source.copyTo(target, overwrite = true)
                } catch (error: Throwable) {
                    undo()
                    throw Failure(
                        "could not write ${target.name}: ${error.message ?: error}",
                        target.absolutePath,
                    )
                }
                if (target.length() != match.size) {
                    undo()
                    throw Failure("${target.name} changed while it was copied", target.absolutePath)
                }
                copied += match.size
                if (progress != null && !progress.onBytes(copied, total)) {
                    undo()
                    throw Failure(CANCELLED)
                }
            }
            val (files, bytes) = count(backupDir)
            writeManifest(backupDir, GAME_FILES, files, bytes, sourceName)
            return Backup(GAME_FILES, backupDir.name, System.currentTimeMillis(), files, bytes)
        } catch (error: Throwable) {
            // Anything thrown before this point has already undone its work;
            // this keeps a throw from outside the loop from leaving a folder.
            if (backupDir.exists()) undo()
            throw error
        }
    }

    /**
     * The one place a payload becomes the installed slots. The old slot is
     * moved to `patch-backups` (never copied), the staged files are moved in,
     * and a failure rolls the slot it was working on back.
     */
    private fun commitPatch(
        root: File,
        staging: File,
        cpks: List<String>,
        hasDp: Boolean,
        saves: List<String>,
        sourceName: String,
    ): List<Pair<String, Backup>> {
        val done = ArrayList<Pair<String, Backup>>()
        val now = System.currentTimeMillis()
        val state = readState(root)
        var patchAt = state.first
        var patchFrom = state.second
        var editAt = state.third
        var editFrom = state.fourth

        if (cpks.isNotEmpty() || hasDp) {
            val slot = patchSlot(root)
            val backup = if (slot.exists()) backupDirectory(root, "patch", slot, sourceName) else null
            slot.mkdirs()
            val names = cpks + if (hasDp) listOf(DP_FILE_LIST) else emptyList()
            for (name in names) {
                val from = File(staging, name)
                if (!from.isFile) {
                    backup?.let { restoreDirectory(root, it, slot) }
                    throw Failure("the extraction has no $name", staging.absolutePath)
                }
                if (!move(from, File(slot, name))) {
                    backup?.let { restoreDirectory(root, it, slot) }
                    throw Failure("could not put $name in place", slot.absolutePath)
                }
            }
            patchAt = now
            patchFrom = sourceName
            backup?.let { done.add("patch" to it) }
        }

        for (name in saves) {
            val from = File(staging, name)
            if (!from.isDirectory) continue
            val target = if (name == EDIT) editFile(root) else systemFile(root)
            val kind = if (name == EDIT) "edit" else "system"
            val backup = if (target.exists()) {
                if (target.isDirectory) backupDirectory(root, kind, target, sourceName)
                else backupFile(root, kind, target, sourceName)
            } else {
                null
            }
            target.deleteRecursively()
            if (!move(from, target)) {
                backup?.let { restoreDirectory(root, it, target) }
                throw Failure("could not put $name in place", target.absolutePath)
            }
            backup?.let { done.add(kind to it) }
            if (name == EDIT) {
                editAt = now
                editFrom = sourceName
            }
        }

        writeState(root, patchAt, patchFrom, editAt, editFrom)
        return done
    }

    /**
     * Puts the newest patch backup back; the pack it replaces becomes the
     * backup. The game's own files that the patch overwrote or added are put
     * back first, so a restore undoes the in-game change too.
     */
    fun restorePatch(game: File): Backup? {
        val root = rootOf(game)
        requireGame(game)
        val backup = newestBackup(root, "patch")
            ?: throw Failure("there is no patch backup to restore")
        verify(backup)
        val slot = patchSlot(root)
        val aside = File(File(root, STAGING), "previous-" + stamp())
        if (slot.exists() && !move(slot, aside)) {
            throw Failure("could not move the installed patch aside", slot.absolutePath)
        }
        if (!move(backup.directory, slot)) {
            aside.takeIf { it.exists() }?.let { move(it, slot) }
            throw Failure("could not put the backup back", backup.directory.absolutePath)
        }
        // The pack that was installed is kept, not dropped: it is the thing a
        // second RESTORE would put back.
        val kept = aside.takeIf { it.exists() }?.let { backupDirectory(root, "patch", it, "restored-from") }
        aside.deleteRecursively()
        val state = readState(root)
        writeState(root, 0, "", state.third, state.fourth)
        return kept
    }

    /** A name that stays inside the folder it is joined to. */
    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    // ---- edited data ---------------------------------------------------------

    /**
     * Replaces `saves/EDIT00000000` with [source]. [source] is the save itself
     * or a `.zip` holding exactly one `EDIT00000000`. The current save is kept
     * first, and `SYSTEM00000000` is not touched.
     */
    fun replaceEditedData(game: File, source: File): Backup? {
        val root = rootOf(game)
        requireGame(game)
        val staging = File(File(root, STAGING), "edit-" + stamp())
        staging.mkdirs()
        try {
            val packed = extractEdit(source, staging)
            if (packed.length() <= 0) throw Failure("the edit save is empty", source.name)
            if (packed.length() > MAX_EDIT_BYTES) {
                throw Failure("the edit save is too large to be one", packed.length().toString() + " bytes")
            }
            val wanted = sha256(packed)
            val target = editFile(root)
            val backup = if (target.exists()) {
                if (target.isDirectory) backupDirectory(root, "edit", target, source.name)
                else backupFile(root, "edit", target, source.name)
            } else {
                null
            }
            savesDir(root).mkdirs()
            val incoming = File(savesDir(root), "$EDIT.installing")
            incoming.delete()
            packed.copyTo(incoming, overwrite = true)
            if (sha256(incoming) != wanted) {
                incoming.delete()
                restoreEditFile(root, backup)
                throw Failure("the edit save changed while it was copied", source.name)
            }
            if (target.exists() && !target.delete()) {
                incoming.delete()
                restoreEditFile(root, backup)
                throw Failure("could not replace the edit save", target.absolutePath)
            }
            if (!incoming.renameTo(target)) {
                incoming.delete()
                restoreEditFile(root, backup)
                throw Failure("could not put the edit save in place", target.absolutePath)
            }
            val state = readState(root)
            writeState(root, state.first, state.second, System.currentTimeMillis(), source.name)
            return backup
        } finally {
            staging.deleteRecursively()
            File(root, STAGING).listFiles()?.takeIf { it.isEmpty() }?.let { File(root, STAGING).delete() }
        }
    }

    /** Puts the newest edit-save backup back; the save it replaces is kept. */
    fun restoreEditedData(game: File): Backup {
        val root = rootOf(game)
        requireGame(game)
        val backup = newestBackup(root, "edit")
            ?: throw Failure("there is no edited-data backup to restore")
        verify(backup)
        val target = editFile(root)
        savesDir(root).mkdirs()
        // An edited-data slot is one file for a hand-imported save and a
        // folder (data.bin, data.inf, data.png) for one that came inside a
        // patch's save container. Restore whichever the backup holds.
        val files = backup.directory.listFiles()
            ?.filter { it.isFile && it.name != MANIFEST }
            .orEmpty()
        if (files.isEmpty()) throw Failure("the edit backup holds no save", backup.directory.absolutePath)
        val current = if (target.exists()) {
            if (target.isDirectory) backupDirectory(root, "edit", target, "restored-from")
            else backupFile(root, "edit", target, "restored-from")
        } else {
            null
        }
        if (files.size == 1 && files[0].name == EDIT) {
            val incoming = File(savesDir(root), "$EDIT.installing")
            incoming.delete()
            files[0].copyTo(incoming, overwrite = true)
            val wanted = sha256(incoming)
            if (target.exists() && !target.deleteRecursively()) {
                throw Failure("could not clear the edit save", target.absolutePath)
            }
            if (!incoming.renameTo(target)) {
                throw Failure("could not put the backup back", target.absolutePath)
            }
            if (sha256(target) != wanted) {
                throw Failure("the restored edit save does not verify", target.absolutePath)
            }
        } else {
            val aside = File(File(root, STAGING), "edit-restored-" + stamp())
            if (target.exists() && !move(target, aside)) {
                throw Failure("could not move the installed edit save aside", target.absolutePath)
            }
            if (!move(backup.directory, target)) {
                aside.takeIf { it.exists() }?.let { move(it, target) }
                throw Failure("could not put the backup back", target.absolutePath)
            }
            aside.deleteRecursively()
        }
        val state = readState(root)
        writeState(root, state.first, state.second, 0, "")
        return current ?: backup.summary
    }

    private fun extractEdit(source: File, staging: File): File {
        val name = source.name.lowercase(Locale.US)
        if (name.endsWith(".zip")) {
            ZipFile(source).use { zip ->
                val entry = zipEntries(zip).firstOrNull {
                    !it.isDirectory && it.name.substringAfterLast('/') == EDIT
                } ?: throw Failure("the archive holds no $EDIT", source.name)
                val out = File(staging, EDIT)
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                return out
            }
        }
        if (source.isDirectory) {
            val found = source.walkTopDown().firstOrNull { it.isFile && it.name == EDIT }
                ?: throw Failure("the folder holds no $EDIT", source.name)
            return found
        }
        return source
    }

    // ---- backups -------------------------------------------------------------

    /** The newest backup of [kind], newest first by its own time. */
    private fun newestBackup(root: File, kind: String): BackupGroup? =
        listBackups(root, listOf(kind)).firstOrNull()?.second

    private class BackupGroup(val kind: String, val directory: File, val summary: Backup, val manifest: Properties)

    /** Kept game files and the names a patch added; [directory] holds both. */
    /**
     * Every backup, newest first. The game-files backups are looked up by
     * [newestBackup] alone: they are bookkeeping for a restore, not a slot the
     * PATCHES screen lists as a kept pack.
     */
    private fun listBackups(
        root: File,
        kinds: List<String> = listOf("patch", "edit"),
    ): List<Pair<String, BackupGroup>> {
        val out = ArrayList<Pair<String, BackupGroup>>()
        for (kind in kinds) {
            val dir = slotDir(root, kind)
            val children = dir.listFiles()?.filter { it.isDirectory } ?: continue
            for (child in children) {
                val manifest = readManifest(child) ?: continue
                val at = manifest.getProperty("at")?.toLongOrNull() ?: 0L
                val summary = Backup(
                    kind = kind,
                    name = child.name,
                    at = at,
                    files = manifest.getProperty("files")?.toIntOrNull() ?: 0,
                    bytes = manifest.getProperty("bytes")?.toLongOrNull() ?: 0,
                )
                out += kind to BackupGroup(kind, child, summary, manifest)
            }
        }
        return out.sortedByDescending { it.second.summary.at }
    }

    /** Keeps a copy of a data-pack directory; returns it with its manifest. */
    private fun backupDirectory(root: File, kind: String, from: File, source: String): Backup {
        val target = freshBackupDir(root, kind)
        target.parentFile?.mkdirs()
        if (!target.mkdirs()) throw Failure("could not make a backup folder", target.absolutePath)
        if (!move(from, target)) throw Failure("could not move the old data aside", from.absolutePath)
        val (files, bytes) = count(target)
        if (files == 0) {
            target.deleteRecursively()
            throw Failure("the old data was empty, so it was not backed up", from.absolutePath)
        }
        writeManifest(target, kind, files, bytes, source)
        return Backup(kind, target.name, System.currentTimeMillis(), files, bytes)
    }

    /** Keeps a copy of one save file; returns it with its manifest. */
    private fun backupFile(root: File, kind: String, from: File, source: String): Backup {
        val target = freshBackupDir(root, kind)
        target.parentFile?.mkdirs()
        if (!target.mkdirs()) throw Failure("could not make a backup folder", target.absolutePath)
        val copy = File(target, from.name)
        try {
            from.copyTo(copy, overwrite = false)
        } catch (error: IOException) {
            target.deleteRecursively()
            throw Failure("could not copy the save into a backup", error.message ?: from.name)
        }
        val hash = sha256(copy)
        writeManifest(target, kind, 1, copy.length(), source, hash)
        return Backup(kind, target.name, System.currentTimeMillis(), 1, copy.length())
    }

    /** A directory no other backup has used: the time, then a suffix. */
    private fun freshBackupDir(root: File, kind: String): File {
        val parent = slotDir(root, kind)
        val base = stamp()
        var candidate = File(parent, base)
        var index = 2
        while (candidate.exists()) {
            candidate = File(parent, "$base-$index")
            index++
        }
        return candidate
    }

    /** Refuses a backup whose manifest does not match what is on disk. */
    private fun verify(backup: BackupGroup) {
        val manifest = backup.manifest
        val expectedFiles = manifest.getProperty("files")?.toIntOrNull() ?: 0
        val expectedBytes = manifest.getProperty("bytes")?.toLongOrNull() ?: 0
        if (expectedFiles <= 0) throw Failure("the backup is empty", backup.directory.absolutePath)
        val (files, bytes) = count(backup.directory)
        if (files != expectedFiles || bytes != expectedBytes) {
            throw Failure(
                "the backup does not match its record",
                "$expectedFiles file(s), $expectedBytes bytes recorded; $files file(s), $bytes bytes found",
            )
        }
        manifest.getProperty("sha256")?.let { wanted ->
            val file = backup.directory.listFiles()?.firstOrNull { it.isFile && it.name != MANIFEST }
                ?: return
            if (!sha256(file).equals(wanted, ignoreCase = true)) {
                throw Failure("the backup's save does not match its record", backup.directory.absolutePath)
            }
        }
    }

    private fun restoreDirectory(root: File, backup: Backup, to: File) {
        if (to.exists()) to.deleteRecursively()
        move(backupDirectoryOf(root, backup), to)
    }

    private fun backupDirectoryOf(root: File, backup: Backup): File = File(slotDir(root, backup.kind), backup.name)

    private fun restoreEditFile(root: File, backup: Backup?) {
        if (backup == null) return
        val kept = File(backupDirectoryOf(root, backup), EDIT)
        if (!kept.isFile) return
        val target = editFile(root)
        target.parentFile?.mkdirs()
        kept.copyTo(target, overwrite = true)
    }

    // ---- manifests and state -------------------------------------------------

    private fun readManifest(dir: File): Properties? {
        val file = File(dir, MANIFEST)
        if (!file.isFile) return null
        return try {
            Properties().apply { file.inputStream().use { load(it) } }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeManifest(dir: File, kind: String, files: Int, bytes: Long, source: String, sha: String? = null) {
        val properties = Properties()
        properties.setProperty("kind", kind)
        properties.setProperty("at", System.currentTimeMillis().toString())
        properties.setProperty("files", files.toString())
        properties.setProperty("bytes", bytes.toString())
        properties.setProperty("source", source)
        if (sha != null) properties.setProperty("sha256", sha)
        File(dir, MANIFEST).outputStream().use { properties.store(it, "PES 2017 backup") }
    }

    /** patchAt, patchFrom, editAt, editFrom. */
    private fun readState(root: File): Quad {
        val file = File(File(root, BACKUPS), STATE)
        if (!file.isFile) return Quad(0, "", 0, "")
        return try {
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            Quad(
                properties.getProperty("patchAt")?.toLongOrNull() ?: 0L,
                properties.getProperty("patchFrom") ?: "",
                properties.getProperty("editAt")?.toLongOrNull() ?: 0L,
                properties.getProperty("editFrom") ?: "",
            )
        } catch (_: Throwable) {
            Quad(0, "", 0, "")
        }
    }

    private fun writeState(root: File, patchAt: Long, patchFrom: String, editAt: Long, editFrom: String) {
        val dir = File(root, BACKUPS)
        dir.mkdirs()
        val properties = Properties()
        properties.setProperty("patchAt", patchAt.toString())
        properties.setProperty("patchFrom", patchFrom)
        properties.setProperty("editAt", editAt.toString())
        properties.setProperty("editFrom", editFrom)
        val target = File(dir, STATE)
        val temp = File(dir, "$STATE.tmp")
        temp.outputStream().use { properties.store(it, "PES 2017 patch state") }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private data class Quad(val first: Long, val second: String, val third: Long, val fourth: String)

    // ---- reading a source ----------------------------------------------------

    private class Imported(val files: Int, val bytes: Long)

    /**
     * Copies a patch source into [staging]. Xbox 360 content packages (CON,
     * LIVE, PIRS) are not unpacked here; an extracted pack is what this takes,
     * which is also what a phone-side patch is.
     */
    private fun importPayload(source: File, staging: File, progress: Progress?): Imported {
        staging.mkdirs()
        val taken = LinkedHashSet<String>()
        var files = 0
        var bytes = 0L
        var copied = 0L
        var total = 0L
        fun want(name: String): Boolean {
            val flat = name.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.US)
            return flat.endsWith(".cpk") || flat == DP_FILE_LIST.lowercase(Locale.US)
        }
        fun put(name: String, input: java.io.InputStream) {
            val flat = name.substringAfterLast('/').substringAfterLast('\\')
            if (!want(flat)) return
            if (!taken.add(flat.lowercase(Locale.US))) {
                throw Failure("the source holds two files called $flat", source.name)
            }
            val out = File(staging, flat)
            input.use { from ->
                out.outputStream().use { to ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val read = from.read(buffer)
                        if (read <= 0) break
                        to.write(buffer, 0, read)
                        copied += read
                        if (progress != null && !progress.onBytes(copied, total)) {
                            throw Failure(CANCELLED)
                        }
                    }
                }
            }
            files++
            bytes += out.length()
        }
        when {
            source.isDirectory -> {
                val found = source.walkTopDown().maxDepth(6)
                    .filter { it.isFile && want(it.name) }
                    .toList()
                if (found.isEmpty()) {
                    throw Failure(
                        "not a PES 2017 patch",
                        "no .cpk files and no $DP_FILE_LIST under ${source.name}",
                    )
                }
                total = found.sumOf { it.length() }
                for (file in found) file.inputStream().use { put(file.name, it) }
            }
            source.name.lowercase(Locale.US).endsWith(".zip") -> {
                ZipFile(source).use { zip ->
                    val entries = zipEntries(zip).filter { !it.isDirectory && want(it.name) }
                    if (entries.isEmpty()) {
                        throw Failure(
                            "not a PES 2017 patch",
                            "the archive holds no .cpk files and no $DP_FILE_LIST",
                        )
                    }
                    total = entries.sumOf { it.size.coerceAtLeast(0) }
                    for (entry in entries) zip.getInputStream(entry).use { put(entry.name, it) }
                }
            }
            else -> throw Failure("this is not a patch this app can read", source.name)
        }
        return Imported(files, bytes)
    }

    // ---- small helpers -------------------------------------------------------

    private fun requireGame(game: File) {
        if (!File(game, XEX).isFile) throw Failure("the game folder does not hold $XEX", game.absolutePath)
    }

    /** Files and bytes under [dir], the manifest itself not counted. */
    fun count(dir: File): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile && file.name != MANIFEST && file.name != STATE) {
                files++
                bytes += file.length()
            }
        }
        return files to bytes
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** renameTo, or a copy and delete across filesystems. */
    private fun move(from: File, to: File): Boolean {
        if (!from.exists()) return false
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        return try {
            if (from.isDirectory) {
                if (to.exists()) to.deleteRecursively()
                if (!from.copyRecursively(to, overwrite = true)) return false
                from.deleteRecursively()
            } else {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun zipEntries(zip: ZipFile): List<java.util.zip.ZipEntry> {
        val list = ArrayList<java.util.zip.ZipEntry>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) list += enumeration.nextElement()
        return list
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
