package com.pes2017.recomp

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * INSTALL PATCH: a full page, not a popup.
 *
 * PES 2018's import wizard is the model (PatchImportActivity there): pick a
 * source, scan it, read a summary of what was found and what it costs, then
 * watch the install and read its result. The steps live in one scroll and
 * only the current one is visible, so back always means the same thing.
 *
 * Everything PES 2017-specific is taken from this repository, not from the
 * 2018 one: the data pack is `dlc/SYSTEM00000000` holding `.cpk` archives and
 * `DpFileList.bin`, the root is the game folder's parent, and `SYSTEM00000000`
 * is never written. The writing is still [PatchStore]'s: this screen only
 * extracts a source into the app's own storage and hands that folder over, so
 * a failed or cancelled install leaves the installed pack untouched.
 */
class PatchImportActivity : AppCompatActivity() {

    private enum class Step { PICK, SCANNING, SUMMARY, INSTALL, RESULT }

    private enum class Outcome { INSTALLED, CANCELLED, FAILED, PASSWORD, CORRUPT, UNSUPPORTED, MISSING_PART, WRONG_PATCH, NO_GAME }

    private lateinit var prefs: Prefs

    private lateinit var stepPick: View
    private lateinit var stepScanning: View
    private lateinit var stepSummary: View
    private lateinit var stepInstall: View
    private lateinit var stepResult: View

    private lateinit var sourceText: TextView
    private lateinit var summarySource: TextView
    private lateinit var summaryPatch: TextView
    private lateinit var summarySkipped: TextView
    private lateinit var summaryWarning: TextView
    private lateinit var peakValue: TextView
    private lateinit var peakBreakdown: TextView
    private lateinit var lowSpace: TextView
    private lateinit var systemSaveCheck: CheckBox
    private lateinit var systemSaveHint: TextView
    private lateinit var installButton: Button
    private lateinit var installProgress: ProgressBar
    private lateinit var installText: TextView
    private lateinit var installStop: Button
    private lateinit var resultBadge: TextView
    private lateinit var resultTitle: TextView
    private lateinit var resultBody: TextView
    private lateinit var resultDetail: TextView
    private lateinit var resultRetry: Button

    private var step = Step.PICK
    private var plan: PatchScanner.Plan? = null
    private var source: PatchSource? = null
    private var password: String? = null
    private var scanToken = 0
    private var installing = false
    private var cancelled = false

    /** The folder an archive is unpacked into; app-owned, so it needs no permission. */
    private fun importDir(): File =
        File(getExternalFilesDir(null) ?: filesDir, "patch-import").apply { mkdirs() }

    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = displayName(uri).ifEmpty { "patch archive" }
        askPassword(name, allowNoPassword = true) { chosen ->
            if (chosen == null) return@askPassword
            password = chosen.ifEmpty { null }
            beginScan(uri = uri, tree = null, name = name)
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        beginScan(uri = null, tree = uri, name = treeName(uri))
    }

    private val pickParts = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        beginScan(uri = null, tree = uri, name = treeName(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The player's Light or Dark Mode, before any view resolves a colour.
        WrapperTheme.apply(this)
        setContentView(R.layout.activity_patch_import)
        prefs = Prefs(this)
        title = getString(R.string.patch_import_title)
        findViewById<View>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        stepPick = findViewById(R.id.step_pick)
        stepScanning = findViewById(R.id.step_scanning)
        stepSummary = findViewById(R.id.step_summary)
        stepInstall = findViewById(R.id.step_install)
        stepResult = findViewById(R.id.step_result)

        sourceText = findViewById(R.id.import_source)
        summarySource = findViewById(R.id.summary_source)
        summaryPatch = findViewById(R.id.summary_patch)
        summarySkipped = findViewById(R.id.summary_skipped)
        summaryWarning = findViewById(R.id.summary_warning)
        peakValue = findViewById(R.id.peak_value)
        peakBreakdown = findViewById(R.id.peak_breakdown)
        lowSpace = findViewById(R.id.low_space)
        systemSaveCheck = findViewById(R.id.system_save_check)
        systemSaveHint = findViewById(R.id.system_save_hint)
        installButton = findViewById(R.id.install_button)
        installProgress = findViewById(R.id.install_progress)
        installText = findViewById(R.id.install_text)
        installStop = findViewById(R.id.install_stop)
        resultBadge = findViewById(R.id.result_badge)
        resultTitle = findViewById(R.id.result_title)
        resultBody = findViewById(R.id.result_body)
        resultDetail = findViewById(R.id.result_detail)
        resultRetry = findViewById(R.id.result_retry)

        findViewById<Button>(R.id.pick_archive).setOnClickListener { pickArchiveSource() }
        findViewById<Button>(R.id.pick_folder).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.pick_multipart).setOnClickListener { pickParts.launch(null) }
        // The fourth source is its own screen: it replaces loose game files and
        // has nothing to do with a data pack, so it must not share this wizard.
        findViewById<Button>(R.id.pick_game_files).setOnClickListener {
            startActivity(Intent(this, GameFilesInstallActivity::class.java))
        }
        findViewById<Button>(R.id.import_cancel_pick).setOnClickListener { finish() }
        findViewById<Button>(R.id.summary_cancel).setOnClickListener { backToPick() }
        installButton.setOnClickListener { beginInstall() }
        installStop.setOnClickListener { confirmCancelInstall() }
        findViewById<Button>(R.id.result_done).setOnClickListener { finish() }
        resultRetry.setOnClickListener { backToPick() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    installing -> confirmCancelInstall()
                    step == Step.SUMMARY -> backToPick()
                    else -> finish()
                }
            }
        })

        sourceText.text = getString(R.string.patch_import_source_none)
        showStep(Step.PICK)
    }

    override fun onResume() {
        super.onResume()
        relaxRtlText()
        if (step == Step.SUMMARY) paintSpace()
    }

    override fun onDestroy() {
        cancelled = true
        closeSource()
        super.onDestroy()
    }

    // ---- sources -------------------------------------------------------------

    /** Providers label archives differently, so everything is offered. */
    private fun pickArchiveSource() {
        pickArchive.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    /**
     * [onChoice] is null for cancel, "" for "no password" and the text for a
     * password. Empty is only offered before a scan; a retry after a locked
     * archive asks for a real value.
     */
    private fun askPassword(name: String, allowNoPassword: Boolean, onChoice: (String?) -> Unit) {
        val field = EditText(this).apply {
            hint = getString(R.string.patch_import_password_field)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val message = TextView(this).apply {
            text = getString(R.string.patch_import_password_hint) + "\n\n" + name
            textSize = 13f
            setTextColor(WrapperTheme.color(this@PatchImportActivity, R.attr.pesText, R.color.pes_text))
            setPadding(0, 0, 0, 8)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                message,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                field,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // The actions are the dialog's own, stacked in its content: the stock
        // button bar leaves this theme's labels white on a white surface.
        val actions = ArrayList<DialogAction>()
        actions += DialogAction(getString(R.string.patch_import_password_use), filled = true) {
            onChoice(field.text.toString())
        }
        actions += DialogAction(getString(R.string.cancel)) { onChoice(null) }
        if (allowNoPassword) {
            actions += DialogAction(getString(R.string.patch_import_password_skip)) { onChoice("") }
        }
        DialogActions.show(this, getString(R.string.patch_import_password_title), content, actions)
    }

    private fun treeName(tree: Uri): String =
        GameFolder.pathOf(tree)?.name
            ?: DocumentFile.fromTreeUri(this, tree)?.name
            ?: getString(R.string.patch_import_folder_default)

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
    }

    // ---- scanning ------------------------------------------------------------

    private fun beginScan(uri: Uri?, tree: Uri?, name: String) {
        val game = game()
        if (game == null) {
            showResult(
                Outcome.NO_GAME,
                getString(R.string.patch_import_result_no_game),
                getString(R.string.patch_import_result_no_game_body),
                "",
            )
            return
        }
        closeSource()
        plan = null
        sourceText.text = getString(R.string.patch_import_source, name)
        showStep(Step.SCANNING)
        val token = ++scanToken
        val passwordForScan = password
        work("patch-scan") {
            try {
                val opened = when {
                    uri != null -> PatchSources.fromDocument(this, uri, name, passwordForScan)
                    tree != null -> PatchSources.fromTree(this, tree, name, passwordForScan)
                    else -> throw PatchSourceException("no source was chosen", PatchSourceFailure.IO)
                }
                val existing = PatchStore.status(game).patch.bytes
                val scanned = try {
                    PatchScanner.scan(
                        opened,
                        existing,
                        freeBytes(),
                        File(cacheDir, "patch-scan"),
                    )
                } catch (error: Throwable) {
                    opened.close()
                    throw error
                }
                runOnUiThread {
                    if (token != scanToken || isFinishing) {
                        opened.close()
                        return@runOnUiThread
                    }
                    source = opened
                    showSummary(scanned)
                }
            } catch (error: PatchSourceException) {
                runOnUiThread {
                    if (token != scanToken || isFinishing) return@runOnUiThread
                    if (error.failure == PatchSourceFailure.PASSWORD && passwordForScan == null) {
                        // All three answers stay on the retry: a player who
                        // picked the wrong file should still be able to say
                        // "no password" and cancel, not only type one.
                        askPassword(name, allowNoPassword = true) { chosen ->
                            if (chosen == null) {
                                // Cancelling must not leave the spinner running with no scan.
                                showStep(Step.PICK)
                            } else {
                                password = chosen.ifEmpty { null }
                                beginScan(uri, tree, name)
                            }
                        }
                    } else {
                        showFailure(error)
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (token != scanToken || isFinishing) return@runOnUiThread
                    showResult(
                        Outcome.FAILED,
                        getString(R.string.patch_import_result_failed),
                        getString(R.string.patch_import_result_failed_body, error.message ?: error.toString()),
                        "",
                    )
                }
            }
        }
    }

    private fun showFailure(error: PatchSourceException) {
        val body = error.message ?: error.toString()
        when (error.failure) {
            PatchSourceFailure.WRONG_PATCH -> showResult(
                Outcome.WRONG_PATCH,
                getString(R.string.patch_import_result_wrong),
                getString(R.string.patch_import_result_wrong_body),
                body,
            )

            PatchSourceFailure.CORRUPT -> showResult(
                Outcome.CORRUPT,
                getString(R.string.patch_import_result_corrupt),
                getString(R.string.patch_import_result_corrupt_body),
                body,
            )

            PatchSourceFailure.MISSING_PART -> showResult(
                Outcome.MISSING_PART,
                getString(R.string.patch_import_result_missing_part),
                getString(R.string.patch_import_result_missing_part_body),
                body,
            )

            PatchSourceFailure.UNSUPPORTED_ARCHIVE -> showResult(
                Outcome.UNSUPPORTED,
                getString(R.string.patch_import_result_unsupported),
                getString(R.string.patch_import_result_unsupported_body),
                body,
            )

            PatchSourceFailure.PASSWORD -> showResult(
                Outcome.PASSWORD,
                getString(R.string.patch_import_result_password),
                getString(R.string.patch_import_result_password_body),
                body,
            )

            PatchSourceFailure.IO -> showResult(
                Outcome.FAILED,
                getString(R.string.patch_import_result_failed),
                getString(R.string.patch_import_result_failed_body, body),
                "",
            )
        }
    }

    // ---- summary -------------------------------------------------------------

    private fun showSummary(scanned: PatchScanner.Plan) {
        plan = scanned
        val kind = when (scanned.kind) {
            PatchSourceKind.ARCHIVE -> getString(R.string.patch_import_kind_archive)
            PatchSourceKind.FOLDER -> getString(R.string.patch_import_kind_folder)
            PatchSourceKind.MULTIPART -> getString(R.string.patch_import_kind_multipart)
        }
        summarySource.text = getString(R.string.patch_import_summary_source, scanned.label, kind)
        summaryPatch.text = getString(
            R.string.patch_import_summary_patch,
            PatchFormat.count(scanned.cpkCount),
            PatchFormat.bytes(scanned.cpkBytes),
            if (scanned.dpFileList) {
                getString(R.string.patch_import_dp_present)
            } else {
                getString(R.string.patch_import_dp_missing)
            },
        )
        summarySkipped.text = if (scanned.skipped.isEmpty()) {
            getString(R.string.patch_import_skipped_none)
        } else {
            getString(R.string.patch_import_skipped, scanned.skipped.size)
        }
        val notes = scanned.warnings.map { warning ->
            when (warning) {
                PatchScanner.Warning.NO_DPFILELIST -> getString(R.string.patch_import_warn_no_dpfilelist)
                PatchScanner.Warning.NO_CPK -> getString(R.string.patch_import_warn_no_cpk)
                PatchScanner.Warning.EDIT_SAVE_IN_SOURCE -> getString(R.string.patch_import_warn_edit)
                PatchScanner.Warning.SYSTEM_SAVE_IN_SOURCE -> getString(R.string.patch_import_warn_system)
                PatchScanner.Warning.EMPTY_CPK -> getString(R.string.patch_import_warn_empty_cpk)
                PatchScanner.Warning.DP_UNREADABLE -> getString(R.string.patch_import_warn_dp_unreadable)
                PatchScanner.Warning.DP_MISSING_CPK -> getString(R.string.patch_import_warn_dp_missing_cpk)
                PatchScanner.Warning.DP_ORDER -> getString(R.string.patch_import_warn_dp_order)
            }
        }.distinct()
        summaryWarning.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        summaryWarning.text = notes.joinToString("\n")

        // The shared save is the one slot a patch can carry that changes data
        // for every mode, so it is offered off the main path and off by default.
        val offerSystem = scanned.systemSave
        systemSaveCheck.visibility = if (offerSystem) View.VISIBLE else View.GONE
        systemSaveHint.visibility = if (offerSystem) View.VISIBLE else View.GONE
        systemSaveCheck.isChecked = false

        paintSpace()
        showStep(Step.SUMMARY)
    }

    /**
     * The space check is re-read every time the screen comes back, because the
     * player may have freed something while it was away. Install stays off
     * while the game is up: its files are open in it.
     */
    private fun paintSpace() {
        val scanned = plan ?: return
        peakValue.text = PatchFormat.bytes(scanned.peakBytes)
        peakBreakdown.text = getString(
            R.string.patch_import_peak_breakdown,
            PatchFormat.bytes(scanned.extractBytes),
            PatchFormat.bytes(scanned.existingBytes),
            PatchFormat.bytes(PatchScanner.MARGIN_BYTES),
        )
        val free = freeBytes()
        val enough = free >= scanned.peakBytes
        when {
            SaveBackup.gameRunning(this) -> {
                lowSpace.visibility = View.VISIBLE
                lowSpace.setText(R.string.patches_game_running)
                installButton.isEnabled = false
            }

            !enough -> {
                lowSpace.visibility = View.VISIBLE
                lowSpace.text = getString(
                    R.string.patch_import_low_space,
                    PatchFormat.bytes(free),
                    PatchFormat.bytes(scanned.peakBytes),
                )
                installButton.isEnabled = false
            }

            !scanned.hasPatch -> {
                lowSpace.visibility = View.VISIBLE
                lowSpace.setText(R.string.patch_import_warn_no_cpk)
                installButton.isEnabled = false
            }

            else -> {
                lowSpace.visibility = View.GONE
                installButton.isEnabled = true
            }
        }
    }

    // ---- installing ----------------------------------------------------------

    private fun beginInstall() {
        val game = game()
        val scanned = plan
        val opened = source
        if (game == null || scanned == null || opened == null) {
            showResult(
                Outcome.NO_GAME,
                getString(R.string.patch_import_result_no_game),
                getString(R.string.patch_import_result_no_game_body),
                "",
            )
            return
        }
        if (SaveBackup.gameRunning(this)) {
            paintSpace()
            return
        }
        if (freeBytes() < scanned.peakBytes) {
            paintSpace()
            return
        }
        if (systemSaveCheck.visibility == View.VISIBLE && systemSaveCheck.isChecked) {
            AlertDialog.Builder(this)
                .setTitle(R.string.patch_system_confirm_title)
                .setMessage(R.string.patch_system_confirm_body)
                .setPositiveButton(R.string.patch_system_confirm_positive) { _, _ -> startInstall() }
                .setNegativeButton(R.string.patch_system_confirm_negative) { _, _ ->
                    systemSaveCheck.isChecked = false
                    startInstall()
                }
                .show()
            return
        }
        startInstall()
    }

    private fun startInstall() {
        val game = game()
        val scanned = plan
        val opened = source
        if (game == null || scanned == null || opened == null) return
        cancelled = false
        installing = true
        installStop.isEnabled = true
        installProgress.isIndeterminate = true
        installText.text = getString(R.string.patch_import_phase_extracting)
        showStep(Step.INSTALL)

        val label = scanned.label
        val temp = File(importDir(), "extract-" + stamp())
        val installSystem = systemSaveCheck.visibility == View.VISIBLE && systemSaveCheck.isChecked
        work("patch-install") {
            var backups: List<Pair<String, PatchStore.Backup>> = emptyList()
            try {
                PatchScanner.extract(opened, scanned, temp) { copied, total ->
                    publishProgress(copied, total, getString(R.string.patch_import_phase_extracting))
                    !cancelled
                }
                if (cancelled) throw PatchScanner.Cancelled()
                backups = PatchStore.installExtracted(
                    game,
                    temp,
                    scanned,
                    installSystem,
                    label,
                )
                val backup = backups.firstOrNull { it.first == "patch" }?.second
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    showResult(
                        Outcome.INSTALLED,
                        getString(R.string.patch_import_result_installed),
                        if (backup != null) {
                            getString(R.string.patch_import_result_installed_body, label)
                        } else {
                            getString(R.string.patch_import_result_installed_body_nobackup, label)
                        },
                        getString(
                            R.string.patch_import_result_installed_detail,
                            PatchFormat.count(scanned.cpkCount),
                            PatchFormat.bytes(scanned.cpkBytes),
                        ),
                    )
                }
            } catch (_: PatchScanner.Cancelled) {
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    showResult(
                        Outcome.CANCELLED,
                        getString(R.string.patch_import_result_cancelled),
                        getString(R.string.patch_import_result_cancelled_body),
                        "",
                    )
                }
            } catch (error: PatchSourceException) {
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    showFailure(error)
                }
            } catch (error: PatchStore.Failure) {
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    if (error.message == PatchStore.CANCELLED) {
                        showResult(
                            Outcome.CANCELLED,
                            getString(R.string.patch_import_result_cancelled),
                            getString(R.string.patch_import_result_cancelled_body),
                            "",
                        )
                    } else {
                        showResult(
                            Outcome.FAILED,
                            getString(R.string.patch_import_result_failed),
                            getString(R.string.patch_import_result_failed_body, error.message ?: error.toString()),
                            "",
                        )
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    showResult(
                        Outcome.FAILED,
                        getString(R.string.patch_import_result_failed),
                        getString(R.string.patch_import_result_failed_body, error.message ?: error.toString()),
                        "",
                    )
                }
            } finally {
                temp.deleteRecursively()
            }
        }
    }

    private fun publishProgress(copied: Long, total: Long, phase: String) {
        runOnUiThread {
            if (!installing) return@runOnUiThread
            if (total > 0) {
                installProgress.isIndeterminate = false
                installProgress.progress = ((copied.coerceAtMost(total) * 1000) / total).toInt()
                installText.text = getString(
                    R.string.patch_import_progress,
                    phase,
                    PatchFormat.bytes(copied),
                    PatchFormat.bytes(total),
                )
            } else {
                installProgress.isIndeterminate = true
                installText.text = getString(R.string.patch_import_progress_unknown, phase, PatchFormat.bytes(copied))
            }
        }
    }

    private fun confirmCancelInstall() {
        AlertDialog.Builder(this)
            .setTitle(R.string.patch_import_cancel_title)
            .setMessage(R.string.patch_import_cancel_body)
            .setNegativeButton(R.string.patch_import_cancel_keep, null)
            .setPositiveButton(R.string.patch_import_cancel_stop) { _, _ ->
                cancelled = true
                installStop.isEnabled = false
                installText.setText(R.string.patch_import_stopping)
            }
            .show()
    }

    // ---- steps and results ---------------------------------------------------

    private fun showStep(next: Step) {
        step = next
        stepPick.visibility = if (next == Step.PICK) View.VISIBLE else View.GONE
        stepScanning.visibility = if (next == Step.SCANNING) View.VISIBLE else View.GONE
        stepSummary.visibility = if (next == Step.SUMMARY) View.VISIBLE else View.GONE
        stepInstall.visibility = if (next == Step.INSTALL) View.VISIBLE else View.GONE
        stepResult.visibility = if (next == Step.RESULT) View.VISIBLE else View.GONE
    }

    private fun backToPick() {
        if (installing) return
        closeSource()
        plan = null
        password = null
        sourceText.text = getString(R.string.patch_import_source_none)
        showStep(Step.PICK)
    }

    private fun showResult(outcome: Outcome, title: String, body: String, detail: String) {
        resultBadge.text = when (outcome) {
            Outcome.INSTALLED -> getString(R.string.patch_import_badge_installed)
            Outcome.CANCELLED -> getString(R.string.patch_import_badge_cancelled)
            else -> getString(R.string.patch_import_badge_failed)
        }
        resultBadge.setBackgroundResource(R.drawable.bg_panel_title)
        resultBadge.setTextColor(Color.WHITE)
        resultTitle.text = title
        resultBody.text = body
        resultDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        resultDetail.text = detail
        // Retrying a failed pick is useful; after an install the screen is done.
        resultRetry.visibility = if (outcome == Outcome.INSTALLED) View.GONE else View.VISIBLE
        showStep(Step.RESULT)
        if (outcome == Outcome.INSTALLED) setResult(RESULT_OK)
    }

    // ---- helpers -------------------------------------------------------------

    private fun game(): File? = PatchStore.gameFolder(prefs.gameDirectory)

    /** The smaller free space of the game's volume and the app's own, where the extraction goes. */
    private fun freeBytes(): Long {
        val game = game()
        val root = game?.let { PatchStore.rootOf(it) }
        val rootFree = runCatching { root?.usableSpace ?: 0L }.getOrDefault(0L)
        val importFree = runCatching { importDir().usableSpace }.getOrDefault(0L)
        return if (rootFree <= 0L) importFree else if (importFree <= 0L) rootFree else minOf(rootFree, importFree)
    }

    private fun work(name: String, block: () -> Unit) {
        thread(name = name, isDaemon = true) { block() }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
