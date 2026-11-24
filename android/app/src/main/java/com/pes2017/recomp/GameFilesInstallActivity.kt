package com.pes2017.recomp

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * INSTALL FOLDER WITHOUT DPFILIST.BIN: the one patch source that writes inside
 * the game folder.
 *
 * A data pack is mounted from `dlc/SYSTEM00000000` and leaves the game folder
 * alone; a folder patch (a kit pack, a stadium pack, an edit pack shipped as
 * loose files) replaces the game's own files instead, which is why it is its
 * own screen with its own warning. The order is the same as the wizard's:
 * choose, scan, read what will happen, confirm once, watch it, read the result.
 *
 * Nothing is written by the scan: [GameFiles] only matches names, and a file
 * the game does not already have is never a match, so this screen cannot add
 * a file. [PatchStore.replaceGameFiles] keeps every file it replaces in
 * `patch-backups/game`, and a failure or a stop puts them all back.
 */
class GameFilesInstallActivity : AppCompatActivity() {

    private enum class Step { PICK, SCANNING, SUMMARY, INSTALL, RESULT }

    private lateinit var prefs: Prefs

    private lateinit var stepPick: View
    private lateinit var stepScanning: View
    private lateinit var stepSummary: View
    private lateinit var stepInstall: View
    private lateinit var stepResult: View

    private lateinit var sourceText: TextView
    private lateinit var summarySource: TextView
    private lateinit var summaryFiles: TextView
    private lateinit var summaryUnmatched: TextView
    private lateinit var summaryAmbiguous: TextView
    private lateinit var summaryRefused: TextView
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
    private var plan: GameFiles.Plan? = null
    private var source: File? = null
    private var scanToken = 0
    private var installing = false
    private var cancelled = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        beginScan(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The player's Light or Dark Mode, before any view resolves a colour.
        WrapperTheme.apply(this)
        setContentView(R.layout.activity_game_files_install)
        prefs = Prefs(this)
        title = getString(R.string.game_files_title)
        findViewById<View>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        stepPick = findViewById(R.id.step_pick)
        stepScanning = findViewById(R.id.step_scanning)
        stepSummary = findViewById(R.id.step_summary)
        stepInstall = findViewById(R.id.step_install)
        stepResult = findViewById(R.id.step_result)

        sourceText = findViewById(R.id.game_files_source)
        summarySource = findViewById(R.id.game_files_summary_source)
        summaryFiles = findViewById(R.id.game_files_summary_files)
        summaryUnmatched = findViewById(R.id.game_files_summary_unmatched)
        summaryAmbiguous = findViewById(R.id.game_files_summary_ambiguous)
        summaryRefused = findViewById(R.id.game_files_summary_refused)
        installButton = findViewById(R.id.game_files_install_button)
        installProgress = findViewById(R.id.game_files_progress)
        installText = findViewById(R.id.game_files_install_text)
        installStop = findViewById(R.id.game_files_stop)
        resultBadge = findViewById(R.id.game_files_result_badge)
        resultTitle = findViewById(R.id.game_files_result_title)
        resultBody = findViewById(R.id.game_files_result_body)
        resultDetail = findViewById(R.id.game_files_result_detail)
        resultRetry = findViewById(R.id.game_files_result_retry)

        findViewById<Button>(R.id.game_files_pick).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.game_files_cancel_pick).setOnClickListener { finish() }
        findViewById<Button>(R.id.game_files_summary_cancel).setOnClickListener { backToPick() }
        installButton.setOnClickListener { confirmReplace() }
        installStop.setOnClickListener { confirmCancel() }
        findViewById<Button>(R.id.game_files_result_done).setOnClickListener { finish() }
        resultRetry.setOnClickListener { backToPick() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    installing -> confirmCancel()
                    step == Step.SUMMARY -> backToPick()
                    else -> finish()
                }
            }
        })

        sourceText.text = getString(R.string.game_files_source_none)
        showStep(Step.PICK)
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    // ---- scanning ------------------------------------------------------------

    private fun beginScan(uri: Uri) {
        val game = game()
        if (game == null) {
            showResult(
                good = false,
                title = getString(R.string.game_files_no_game),
                body = getString(R.string.game_files_no_game_body),
                detail = "",
            )
            return
        }
        val folder = GameFolder.pathOf(uri)
        if (folder == null || !folder.isDirectory || !folder.canRead()) {
            showResult(
                good = false,
                title = getString(R.string.game_files_result_failed),
                body = getString(R.string.game_files_unreadable),
                detail = "",
            )
            return
        }
        if (folder.absolutePath == game.absolutePath) {
            showResult(
                good = false,
                title = getString(R.string.game_files_result_failed),
                body = getString(R.string.game_files_same_folder),
                detail = "",
            )
            return
        }
        plan = null
        source = null
        sourceText.text = getString(R.string.game_files_source, folder.name)
        showStep(Step.SCANNING)
        val token = ++scanToken
        thread(name = "game-files-scan", isDaemon = true) {
            try {
                val scanned = GameFiles.scan(folder, game)
                runOnUiThread {
                    if (token != scanToken || isFinishing) return@runOnUiThread
                    source = folder
                    showSummary(scanned)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (token != scanToken || isFinishing) return@runOnUiThread
                    showResult(
                        good = false,
                        title = getString(R.string.game_files_result_failed),
                        body = getString(
                            R.string.game_files_result_failed_body,
                            error.message ?: error.toString(),
                        ),
                        detail = "",
                    )
                }
            }
        }
    }

    private fun showSummary(scanned: GameFiles.Plan) {
        plan = scanned
        summarySource.text = getString(R.string.game_files_summary_source, scanned.label)
        if (!scanned.hasMatches) {
            summaryFiles.setText(R.string.game_files_nothing_matched)
        } else {
            summaryFiles.text = getString(
                R.string.game_files_summary_files,
                scanned.matches.size,
                PatchFormat.bytes(scanned.payloadBytes),
            )
        }
        summaryUnmatched.visibility = if (scanned.unmatched.isEmpty()) View.GONE else View.VISIBLE
        summaryUnmatched.text = if (scanned.unmatched.isEmpty()) {
            ""
        } else {
            getString(R.string.game_files_summary_unmatched, scanned.unmatched.size) +
                "\n" + scanned.unmatched.take(6).joinToString("\n")
        }
        summaryAmbiguous.visibility = if (scanned.ambiguous.isEmpty()) View.GONE else View.VISIBLE
        summaryAmbiguous.text = if (scanned.ambiguous.isEmpty()) {
            ""
        } else {
            getString(R.string.game_files_summary_ambiguous, scanned.ambiguous.size) +
                "\n" + scanned.ambiguous.take(6).joinToString("\n")
        }
        summaryRefused.visibility = if (scanned.refused.isEmpty()) View.GONE else View.VISIBLE
        summaryRefused.text = if (scanned.refused.isEmpty()) {
            ""
        } else {
            getString(R.string.game_files_summary_refused, scanned.refused.size)
        }
        installButton.text = getString(R.string.game_files_install, scanned.matches.size)
        installButton.isEnabled = scanned.hasMatches && !SaveBackup.gameRunning(this)
        showStep(Step.SUMMARY)
    }

    // ---- replacing -----------------------------------------------------------

    /** The one confirmation: the warning, the count, and nothing written until CONFIRM. */
    private fun confirmReplace() {
        val scanned = plan ?: return
        val opened = source ?: return
        val game = game() ?: return
        if (!scanned.hasMatches) return
        if (SaveBackup.gameRunning(this)) {
            showResult(
                good = false,
                title = getString(R.string.game_files_running),
                body = getString(R.string.patches_game_running),
                detail = "",
            )
            return
        }
        val body = TextView(this).apply {
            text = getString(
                R.string.game_files_confirm_body,
                scanned.matches.size,
                PatchFormat.bytes(scanned.payloadBytes),
            )
            textSize = 13f
            setTextColor(WrapperTheme.color(this@GameFilesInstallActivity, R.attr.pesText, R.color.pes_text))
        }
        DialogActions.show(
            this,
            getString(R.string.game_files_confirm_title, scanned.matches.size),
            body,
            listOf(
                DialogAction(getString(R.string.game_files_confirm_positive), filled = true) {
                    startReplace(game, opened, scanned)
                },
                // CANCEL writes nothing at all: the dialog closes and the
                // screen stays on the summary it was confirmed from.
                DialogAction(getString(R.string.game_files_confirm_negative)),
            ),
        )
    }

    private fun startReplace(game: File, folder: File, scanned: GameFiles.Plan) {
        if (installing) return
        cancelled = false
        installing = true
        installStop.isEnabled = true
        installProgress.progress = 0
        installText.text = getString(R.string.game_files_progress, 0, scanned.matches.size)
        showStep(Step.INSTALL)

        val label = scanned.label
        val progress = PatchStore.Progress { copied, total ->
            val done = if (total <= 0L) scanned.matches.size
            else (copied * scanned.matches.size / total).toInt().coerceAtMost(scanned.matches.size)
            publishProgress(done, scanned.matches.size)
            !cancelled
        }
        thread(name = "game-files-install", isDaemon = true) {
            try {
                PatchStore.replaceGameFiles(game, scanned, label, progress)
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    showResult(
                        good = true,
                        title = getString(R.string.game_files_ok),
                        body = getString(
                            R.string.game_files_result_installed_body,
                            scanned.matches.size,
                            PatchFormat.bytes(scanned.payloadBytes),
                        ),
                        detail = "",
                    )
                }
            } catch (error: PatchStore.Failure) {
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    if (error.message == PatchStore.CANCELLED) {
                        showResult(
                            good = false,
                            title = getString(R.string.game_files_cancelled),
                            body = getString(R.string.game_files_result_cancelled_body),
                            detail = "",
                        )
                    } else {
                        showResult(
                            good = false,
                            title = getString(R.string.game_files_result_failed),
                            body = getString(
                                R.string.game_files_result_failed_body,
                                error.message ?: error.toString(),
                            ),
                            detail = "",
                        )
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    installing = false
                    if (isFinishing) return@runOnUiThread
                    showResult(
                        good = false,
                        title = getString(R.string.game_files_result_failed),
                        body = getString(
                            R.string.game_files_result_failed_body,
                            error.message ?: error.toString(),
                        ),
                        detail = "",
                    )
                }
            }
        }
    }

    private fun publishProgress(done: Int, total: Int) {
        runOnUiThread {
            if (!installing) return@runOnUiThread
            installProgress.isIndeterminate = false
            installProgress.progress = if (total <= 0) 0 else (done * 1000 / total)
            installText.text = getString(R.string.game_files_progress, done, total)
        }
    }

    private fun confirmCancel() {
        DialogActions.show(
            this,
            getString(R.string.game_files_cancel_title),
            TextView(this).apply {
                text = getString(R.string.game_files_cancel_body)
                textSize = 13f
                setTextColor(WrapperTheme.color(this@GameFilesInstallActivity, R.attr.pesText, R.color.pes_text))
            },
            listOf(
                DialogAction(getString(R.string.game_files_cancel_keep), filled = true),
                DialogAction(getString(R.string.game_files_cancel_stop)) {
                    cancelled = true
                    installStop.isEnabled = false
                    installText.setText(R.string.game_files_stopping)
                },
            ),
        )
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
        plan = null
        source = null
        sourceText.text = getString(R.string.game_files_source_none)
        showStep(Step.PICK)
    }

    private fun showResult(good: Boolean, title: String, body: String, detail: String) {
        resultBadge.setText(if (good) R.string.game_files_badge_done else R.string.game_files_badge_failed)
        resultBadge.setBackgroundResource(R.drawable.bg_panel_title)
        resultBadge.setTextColor(Color.WHITE)
        resultTitle.text = title
        resultBody.visibility = if (body.isBlank()) View.GONE else View.VISIBLE
        resultBody.text = body
        resultDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        resultDetail.text = detail
        resultRetry.visibility = if (good) View.GONE else View.VISIBLE
        showStep(Step.RESULT)
        if (good) setResult(RESULT_OK)
    }

    private fun game(): File? = PatchStore.gameFolder(prefs.gameDirectory)
}
