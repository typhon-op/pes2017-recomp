package com.pes2017.recomp

import java.io.File
import java.util.Locale

/**
 * INSTALL FOLDER WITHOUT DPFILIST.BIN: replacing loose game files from an
 * extracted folder, for the patches that only change files the game already
 * has (a kit folder, a stadium folder, an edit patch's loose data).
 *
 * This is the one source that writes inside the game folder, which is why it
 * is not part of the data-pack wizard: a data pack is mounted from
 * `dlc/SYSTEM00000000` and the game folder is never touched, while this
 * replaces the game's own files in place. Nothing is written before the whole
 * folder has been matched against the game, and a file is only ever replaced
 * when it already exists: this screen never adds a file to the game.
 *
 * The match is exact first (the folder's own relative path, so `data/kit.bin`
 * goes to `data/kit.bin`), then by filename when the game holds exactly one
 * file of that name. A name the game holds twice is not guessed at, and
 * `default.xex` and every other `.xex` are always refused: the runtime checks
 * its own baked hash, and a replaced executable would simply not start.
 */
object GameFiles {

    /** The same depth the patch sources use: deeper than any PES 2017 folder. */
    private const val MAX_DEPTH = 8

    /** Folders the app itself writes beside the game, never part of a match. */
    private val OWN_FOLDERS = setOf("patch-backups", "patch-staging")

    /** One planned replacement: the source file and the game file it becomes. */
    data class Match(
        val source: File,
        val target: File,
        val relative: String,
        val size: Long,
    )

    /**
     * What the folder holds, checked against the game folder before anything
     * is written. [unmatched] and [ambiguous] are left alone; [refused] are
     * the files this screen will not replace whatever they match.
     */
    data class Plan(
        val label: String,
        val sourceRoot: File,
        val matches: List<Match>,
        val unmatched: List<String>,
        val ambiguous: List<String>,
        val refused: List<String>,
        val skipped: Int,
    ) {
        val payloadBytes: Long get() = matches.sumOf { it.size }
        val hasMatches: Boolean get() = matches.isNotEmpty()
    }

    /**
     * Matches every file in [source] against the files in [game]. Reads only;
     * a plan is what the player confirms, and it is re-checked as it is applied.
     */
    fun scan(source: File, game: File, label: String = source.name): Plan {
        val root = game.absoluteFile
        val byPath = HashMap<String, File>()
        val byName = HashMap<String, MutableList<File>>()
        root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .onEnter { dir -> dir == root || dir.name.lowercase(Locale.US) !in OWN_FOLDERS }
            .filter { it.isFile }
            .forEach { file ->
                val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }
                    .getOrNull() ?: file.name
                val key = relative.lowercase(Locale.US)
                byPath[key] = file
                byName.getOrPut(file.name.lowercase(Locale.US)) { ArrayList() }.add(file)
            }

        val matches = ArrayList<Match>()
        val unmatched = ArrayList<String>()
        val ambiguous = ArrayList<String>()
        val refused = ArrayList<String>()
        val taken = HashSet<String>()
        var skipped = 0

        source.absoluteFile.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .filter { it.isFile }
            .sortedBy { it.path.lowercase(Locale.US) }
            .forEach { file ->
                val relative = runCatching { file.relativeTo(source.absoluteFile).invariantSeparatorsPath }
                    .getOrNull() ?: file.name
                if (relative.isEmpty()) {
                    skipped++
                    return@forEach
                }
                if (file.name.lowercase(Locale.US).endsWith(".xex")) {
                    refused += relative
                    return@forEach
                }
                if (file.length() <= 0L) {
                    // An empty file would blank a game file; it is never a patch.
                    skipped++
                    return@forEach
                }
                val target = byPath[relative.lowercase(Locale.US)]
                    ?: byName[file.name.lowercase(Locale.US)]?.let { found ->
                        when (found.size) {
                            1 -> found[0]
                            else -> {
                                ambiguous += relative
                                return@forEach
                            }
                        }
                    }
                if (target == null) {
                    unmatched += relative
                    return@forEach
                }
                if (target.name.lowercase(Locale.US).endsWith(".xex")) {
                    refused += relative
                    return@forEach
                }
                val key = target.absolutePath.lowercase(Locale.US)
                if (!taken.add(key)) {
                    // Two source files claiming one game file: neither is used.
                    val earlier = matches.firstOrNull {
                        it.target.absolutePath.lowercase(Locale.US) == key
                    }
                    if (earlier != null) {
                        ambiguous += earlier.relative
                        matches.remove(earlier)
                    }
                    ambiguous += relative
                    return@forEach
                }
                matches += Match(file, target, relative, file.length())
            }

        return Plan(
            label = label,
            sourceRoot = source.absoluteFile,
            matches = matches.sortedBy { it.relative.lowercase(Locale.US) },
            unmatched = unmatched.sorted(),
            ambiguous = ambiguous.sorted(),
            refused = refused.sorted(),
            skipped = skipped,
        )
    }
}
