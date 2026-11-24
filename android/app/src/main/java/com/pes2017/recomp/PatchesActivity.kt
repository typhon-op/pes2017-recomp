package com.pes2017.recomp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * PATCHES: what data pack and edit save are installed beside the game, and the
 * operations that change them.
 *
 * Everything goes through [PatchStore], which keeps a copy of what it replaces
 * and checks a backup before putting it back; this screen is the two buttons
 * and the status line for each slot. The one rule it adds is that nothing may
 * be written while the game's own process is up -- the title has the files
 * open, and replacing them under it is how a save is lost.
 */
class PatchesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var patchText: TextView
    private lateinit var editText: TextView
    private lateinit var backupsText: TextView
    private lateinit var installButton: Button
    private lateinit var restoreButton: Button
    private lateinit var editReplaceButton: Button
    private lateinit var editRestoreButton: Button
    private lateinit var folderButton: Button

    private val pickEdit = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!ready()) return@registerForActivityResult
        confirm(getString(R.string.patches_edit_confirm)) {
            val name = displayName(uri) ?: PatchStore.EDIT
            work { replaceEdit(copyToImport(uri, name)) }
        }
    }

    private val pickGameFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val picked = GameFolder.pathOf(uri)
        if (picked == null) {
            message(getString(R.string.patches_unreadable_path))
            return@registerForActivityResult
        }
        val game = GameFolder.locate(picked)
        if (game == null) {
            message(getString(R.string.patches_wrong_game))
            return@registerForActivityResult
        }
        prefs.gameDirectory = game.absolutePath
        Toast.makeText(this, getString(R.string.patches_folder_set, game.absolutePath), Toast.LENGTH_LONG).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The player's Light or Dark Mode, before any view resolves a colour.
        WrapperTheme.apply(this)
        setContentView(R.layout.activity_patches)
        WrapperTheme.dimArtwork(this)
        prefs = Prefs(this)
        title = getString(R.string.patches)
        findViewById<android.view.View>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        status = findViewById(R.id.patches_status)
        patchText = findViewById(R.id.patches_patch)
        editText = findViewById(R.id.patches_edit)
        backupsText = findViewById(R.id.patches_backups)
        installButton = findViewById(R.id.patches_install)
        restoreButton = findViewById(R.id.patches_restore)
        editReplaceButton = findViewById(R.id.patches_edit_replace)
        editRestoreButton = findViewById(R.id.patches_edit_restore)
        folderButton = findViewById(R.id.patches_folder)

        folderButton.setOnClickListener { pickGameFolder.launch(null) }
        // INSTALL PATCH is its own page now (PatchImportActivity): the popup
        // that used to ask "zip or folder" could not show what was found, what
        // it costs, or ask a locked archive for its password.
        installButton.setOnClickListener {
            startActivity(Intent(this, PatchImportActivity::class.java))
        }
        restoreButton.setOnClickListener {
            if (!ready()) return@setOnClickListener
            confirm(getString(R.string.patches_restore_confirm)) {
                work { restorePatch() }
            }
        }
        editReplaceButton.setOnClickListener {
            pickEdit.launch(arrayOf("*/*"))
        }
        editRestoreButton.setOnClickListener {
            if (!ready()) return@setOnClickListener
            confirm(getString(R.string.patches_edit_restore_confirm)) {
                work { restoreEditedData() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        relaxRtlText()
        refresh()
    }

    // ---- the screen ----------------------------------------------------------

    private fun game(): File? = PatchStore.gameFolder(prefs.gameDirectory)

    private fun refresh() {
        val game = game()
        val state = PatchStore.status(game)
        status.text = if (game == null) {
            getString(R.string.patches_no_folder)
        } else {
            getString(R.string.patches_folder_set, game.absolutePath) + "\n" +
                if (state.system.present) getString(R.string.patches_system_present, bytes(state.system.bytes))
                else getString(R.string.patches_system_absent)
        }

        patchText.text = if (!state.patch.present) {
            getString(R.string.patches_patch_none)
        } else {
            val installed = if (state.patchAt > 0) {
                "\n" + getString(R.string.patches_installed_on, date(state.patchAt), state.patchFrom)
            } else ""
            getString(
                R.string.patches_patch_installed,
                getString(R.string.patches_files, state.patch.files),
                bytes(state.patch.bytes),
            ) + installed
        }

        editText.text = if (!state.edit.present) {
            getString(R.string.patches_edit_none)
        } else {
            val replaced = if (state.editAt > 0) {
                "\n" + getString(R.string.patches_edit_replaced_on, date(state.editAt), state.editFrom)
            } else ""
            getString(R.string.patches_edit_present, bytes(state.edit.bytes)) + replaced
        }

        backupsText.text = if (state.backups.isEmpty()) {
            getString(R.string.patches_backups_none)
        } else {
            state.backups.joinToString("\n\n") { backup ->
                getString(
                    R.string.patches_backup_line,
                    if (backup.kind == "patch") getString(R.string.patches_patch_section)
                    else getString(R.string.patches_edit_section),
                    date(backup.at),
                    getString(R.string.patches_files, backup.files),
                    bytes(backup.bytes),
                )
            }
        }

        val hasGame = game != null
        installButton.isEnabled = hasGame
        editReplaceButton.isEnabled = hasGame
        restoreButton.isEnabled = state.backups.any { it.kind == "patch" }
        editRestoreButton.isEnabled = state.backups.any { it.kind == "edit" }
    }

    // ---- operations ----------------------------------------------------------

    /** Refuses while the game's process is up: its files are open in it. */
    private fun ready(): Boolean {
        if (SaveBackup.gameRunning(this)) {
            message(getString(R.string.patches_game_running))
            return false
        }
        if (game() == null) {
            message(getString(R.string.patches_select_folder_first))
            return false
        }
        return true
    }

    private fun restorePatch(): String {
        val game = game() ?: return ""
        return try {
            PatchStore.restorePatch(game)
            refreshOnUi()
            getString(R.string.patches_restored_done)
        } catch (error: PatchStore.Failure) {
            failure(error)
        } catch (error: Throwable) {
            failure(error)
        }
    }

    private fun replaceEdit(source: File): String {
        val game = game() ?: return ""
        return try {
            val backup = PatchStore.replaceEditedData(game, source)
            refreshOnUi()
            if (backup != null) getString(R.string.patches_edit_done_kept) else getString(R.string.patches_edit_done)
        } catch (error: PatchStore.Failure) {
            failure(error)
        } catch (error: Throwable) {
            failure(error)
        }
    }

    private fun restoreEditedData(): String {
        val game = game() ?: return ""
        return try {
            PatchStore.restoreEditedData(game)
            refreshOnUi()
            getString(R.string.patches_edit_restored)
        } catch (error: PatchStore.Failure) {
            failure(error)
        } catch (error: Throwable) {
            failure(error)
        }
    }

    private fun failure(error: Throwable): String =
        getString(R.string.patches_failed, error.message ?: error.toString())

    // ---- work off the main thread -------------------------------------------

    private fun work(block: () -> String) {
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.patches_busy)
            .setCancelable(false)
            .show()
        // kotlin.concurrent.thread, not the Thread constructor: the constructor
        // takes (target, name) and cannot be called with the name first and a
        // trailing lambda, which does not compile.
        thread(name = "patch-work") {
            val result = block()
            runOnUiThread {
                progress.dismiss()
                refresh()
                if (result.isNotEmpty()) Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshOnUi() {
        runOnUiThread { refresh() }
    }

    // ---- picking files -------------------------------------------------------

    /** A provider's document copied into the app's own storage under its own name. */
    private fun copyToImport(uri: Uri, name: String): File {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "patch-import")
        dir.mkdirs()
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "import" }
        val target = File(dir, safe)
        val input = contentResolver.openInputStream(uri)
            ?: throw PatchStore.Failure(getString(R.string.patches_unreadable_path))
        input.use { from -> target.outputStream().use { from.copyTo(it) } }
        return target
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    // ---- small helpers -------------------------------------------------------

    private fun confirm(text: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.patches_continue) { _, _ -> onYes() }
            .show()
    }

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(R.string.close, null).show()
    }

    private fun date(millis: Long): String =
        if (millis <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    private fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble() / 1024.0
        var unit = 0
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024.0
            unit++
        }
        return String.format(java.util.Locale.US, "%.1f %s", size, units[unit])
    }
}
