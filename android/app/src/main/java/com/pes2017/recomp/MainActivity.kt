package com.pes2017.recomp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * The launcher: is the game installed, which driver will be used, and the
 * button that starts it. It runs in its own process, separate from the game
 * (see the manifest), so that ending a run -- which means killing the
 * game's process -- leaves this one standing.
 *
 * It is written so that it cannot fail to appear. The native library is not
 * needed to draw this screen and is loaded in a guarded step afterwards: a
 * library that will not load leaves a launcher that says so, with the log
 * and the last crash still reachable, rather than an app that vanishes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var footer: TextView
    private lateinit var play: Button
    private lateinit var installButton: Button
    private lateinit var selectFolderButton: Button
    private lateinit var updatesButton: Button
    private lateinit var languageButton: Button
    private lateinit var quitButton: Button
    private lateinit var detailTitle: TextView
    private lateinit var detailText: TextView
    private lateinit var versionTag: TextView
    private lateinit var directLaunchSwitch: androidx.appcompat.widget.SwitchCompat
    private var detectedSource: GameSource? = null
    private var detectedSources: List<GameSource> = emptyList()
    private var nativeReady = false
    private var hasAutoLaunched = false

    /**
     * True only when this instance is the first screen of a fresh launch.
     * A recreated launcher (a rotation, a low-memory restart) restores its
     * saved state, and "kick off straight away" is about opening the app, not
     * about a screen being rebuilt -- so only this starts a match on its own.
     */
    private var coldStart = false

    /**
     * When the launcher came back from a match the player quit. A confirm that
     * dismissed the match's own menu must not land on KICK OFF as the launcher
     * appears, so the first moments after the return ignore that button.
     */
    private var quitReturnedAt = 0L

    /** The match asked to be started again with new settings (QuickGraphics). */
    private var relaunchRequested = false
    private var installRequired = false
    private var pendingInstallId: Long = -1
    private var pendingUpdateUrl: String? = null

    /** The tree the player picked while the app still lacked storage access. */
    private var pendingTree: Uri? = null

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderPicked(uri)
    }

    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resumePendingFolder() }

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != pendingInstallId) return
            pendingInstallId = -1
            installDownloadedApk(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        coldStart = savedInstanceState == null
        prefs = Prefs(this)
        relaunchRequested = QuickGraphics.takeRelaunch(intent, prefs)
        // The performance preset this phone starts from, once (DeviceProfile).
        try { DeviceProfile.ensurePreset(this, prefs) } catch (_: Throwable) {}

        status = findViewById(R.id.status)
        footer = findViewById(R.id.footer)
        play = findViewById(R.id.play)
        installButton = findViewById(R.id.install)
        selectFolderButton = findViewById(R.id.select_folder)
        updatesButton = findViewById(R.id.entry_updates)
        languageButton = findViewById(R.id.entry_language)
        quitButton = findViewById(R.id.entry_quit)
        detailTitle = findViewById(R.id.detail_title)
        detailText = findViewById(R.id.detail_text)
        versionTag = findViewById(R.id.version_tag)
        directLaunchSwitch = findViewById(R.id.direct_launch_switch)
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val name = info.versionName ?: ""
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else info.versionCode
            versionTag.text = getString(R.string.version_tag, name, code)
        } catch (_: Throwable) {
        }

        // The switch is the player's, always: it says what opening the app
        // does, which is as true before the game is installed as after. It used
        // to be hidden whenever no installation was found, so on a phone that
        // still had to pick its game folder -- the usual first run, and the
        // state the Arabic screenshot was taken in -- the feature looked gone.
        // Registered after the state is written, and guarded against the
        // no-op changes a rebuild can replay: a listener that fires on its own
        // initial state used to overwrite the saved choice with whatever the
        // freshly inflated switch happened to hold.
        directLaunchSwitch.setOnCheckedChangeListener(null)
        directLaunchSwitch.isChecked = prefs.autoLaunch
        directLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.autoLaunch != isChecked) prefs.autoLaunch = isChecked
            // The choice applies the next time the app opens, as the label says.
            // Taking the auto-start latch here keeps a resume (a dialog, Home)
            // from starting a match this session the instant it is switched on.
            hasAutoLaunched = true
            showDetail(
                getString(R.string.direct_launch),
                getString(if (isChecked) R.string.direct_launch_on else R.string.direct_launch_off),
            )
        }
        // The whole row is the target, not just the switch's thumb: the label
        // and the switch sit inline in one band under the list.
        findViewById<View>(R.id.direct_launch_row).setOnClickListener {
            directLaunchSwitch.toggle()
        }

        play.setOnClickListener {
            // A fresh quit-return may still carry the finger or key that
            // confirmed Exit in the match's menu. KICK OFF is deliberately
            // inert for that instant; the next tap is the player's.
            if (android.os.SystemClock.uptimeMillis() - quitReturnedAt < QUIT_INPUT_GUARD_MS) return@setOnClickListener
            select(play)
            showDetail(getString(R.string.play), status.text.toString())
            val gameDirectory = prefs.gameDirectory
            val installed = gameDirectory.isNotEmpty() && safely(false) {
                NativeBridge.nativeGameInstalled(gameDirectory)
            }
            if (installed) {
                startGame()
            } else if (detectedSource?.isFolder == true) {
                // Already extracted: played where it is, nothing copied.
                useFolder(detectedSource!!.file, null)
            } else if (detectedSource != null) {
                val intent = Intent(this, InstallActivity::class.java).apply {
                    putExtra(InstallActivity.EXTRA_INITIAL_PATH, detectedSource!!.file.absolutePath)
                    putExtra(InstallActivity.EXTRA_AUTO_START, true)
                }
                startActivity(intent)
            } else {
                // Nothing to play yet: the natural next step is choosing the folder.
                selectGameFolder()
            }
        }
        selectFolderButton.setOnClickListener {
            select(selectFolderButton)
            selectGameFolder()
        }
        installButton.setOnClickListener {
            select(installButton)
            showDetail(getString(R.string.install_game), getString(R.string.install_hint))
            if (detectedSources.size > 1) chooseSource()
            else startActivity(Intent(this, InstallActivity::class.java))
        }
        findViewById<Button>(R.id.driver).setOnClickListener {
            select(it as Button)
            showDetail(getString(R.string.graphics_driver), getString(R.string.driver_hint))
            startActivity(Intent(this, DriverActivity::class.java))
        }
        findViewById<Button>(R.id.controls_guide).setOnClickListener {
            select(it as Button)
            startActivity(Intent(this, ControlGuideActivity::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            select(it as Button)
            showDetail(getString(R.string.entry_settings), getString(R.string.settings_hint))
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.log).setOnClickListener {
            select(it as Button)
            showDetail(getString(R.string.view_log), "")
            showLog()
        }
        updatesButton.setOnClickListener {
            select(updatesButton)
            showDetail(getString(R.string.check_updates), "")
            checkForUpdates(manual = true)
        }
        languageButton.setOnClickListener {
            select(languageButton)
            showDetail(getString(R.string.language), getString(R.string.language_hint))
            LanguagePicker.show(this, prefs)
        }
        findViewById<Button>(R.id.patches).setOnClickListener {
            select(it as Button)
            showDetail(getString(R.string.patches_menu), getString(R.string.patches_hint))
            startActivity(Intent(this, PatchesActivity::class.java))
        }
        quitButton.setOnClickListener {
            select(quitButton)
            finishAndRemoveTask()
        }
        // The cursor starts on KICK OFF, and the strip at the foot scrolls
        // (a marquee runs only on a selected view).
        select(play)
        footer.isSelected = true

        // Whatever killed the app last time, shown before anything else is
        // attempted -- it may well be about to happen again.
        val crash = Trail.lastCrash(this)
        if (crash.isNotEmpty()) showLastCrash(crash)

        // The library is loaded after the screen exists, so a failure here
        // is reported rather than fatal. The breadcrumb is what identifies a
        // native crash inside the load itself, which no handler can catch.
        Trail.note(this, "launcher: loading the runtime library")
        nativeReady = NativeBridge.load(this)
        if (nativeReady) {
            Trail.note(this, "launcher: library loaded, initialising")
            try {
                NativeBridge.nativeInit(
                    filesDir.absolutePath,
                    (getExternalFilesDir(null) ?: filesDir).absolutePath,
                    cacheDir.absolutePath,
                    applicationInfo.nativeLibraryDir,
                    null,
                )
            } catch (error: Throwable) {
                nativeReady = false
                NativeBridge.noteInitFailure(error.message ?: error.toString())
            }
        }
        Trail.clear(this)
        ContextCompat.registerReceiver(
            this, downloadDone,
            android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        checkForUpdates()
        predownloadDriver()
    }

    /**
     * First run on an Adreno phone: fetch the matching Turnip build in the
     * background so it is in the driver list. It is only stored; choosing it
     * is the player's call (Graphics driver).
     */
    private fun predownloadDriver() {
        if (prefs.driverAutoTried) return
        thread(name = "driver-predownload", isDaemon = true) {
            val renderer = try { DeviceProfile.detect(this, prefs).specs.gpu } catch (_: Throwable) { "" }
            if (!DriverDownloader.suitable(renderer)) {
                prefs.driverAutoTried = true
                return@thread
            }
            val store = DriverStore(this)
            if (store.list().isNotEmpty()) {
                prefs.driverAutoTried = true
                return@thread
            }
            // Offline now is not a verdict: try again next launch.
            val result = DriverDownloader.download(this, renderer, store)
            if (result is DriverDownloader.Result.Done) prefs.driverAutoTried = true
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadDone)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (QuickGraphics.takeRelaunch(intent, prefs)) relaunchRequested = true
    }

    override fun onResume() {
        super.onResume()
        // Back from the all-files permission screen with a folder waiting.
        // This comes first so a freshly chosen folder is what refresh() shows
        // (and what direct launch would start).
        resumePendingFolder()
        refresh()
        pendingUpdateUrl?.let { url ->
            pendingUpdateUrl = null
            if (packageManager.canRequestPackageInstalls()) startUpdateDownload(url)
        }
    }

    // ---- choosing the game folder ------------------------------------------------

    /**
     * Select Game Folder: the system folder picker, then -- only if the folder
     * is outside the app's own storage and Android has not yet allowed the app
     * to read it -- the permission prompt. The game runs from the chosen folder
     * as it lies; nothing is copied unless the player prefers that.
     */
    private fun selectGameFolder() {
        showDetail(getString(R.string.entry_select_folder), getString(R.string.select_folder_hint))
        try {
            pickTree.launch(null)
        } catch (_: Throwable) {
            message(getString(R.string.folder_unreadable_path))
        }
    }

    private fun onFolderPicked(tree: Uri) {
        val picked = GameFolder.pathOf(tree)
        if (picked == null) {
            // A provider with no path behind it: the only way in is a copy.
            offerCopy(tree)
            return
        }
        if (!GameFolder.hasAccess(this)) {
            pendingTree = tree
            prefs.pendingFolder = picked.absolutePath
            askForAccess(tree)
            return
        }
        useFolder(picked, tree)
    }

    private fun askForAccess(tree: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.access_title)
            .setMessage(R.string.access_explained)
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingTree = null
                prefs.pendingFolder = ""
            }
            .setNeutralButton(R.string.copy_instead) { _, _ ->
                pendingTree = null
                prefs.pendingFolder = ""
                offerCopy(tree)
            }
            .setPositiveButton(R.string.allow_storage) { _, _ -> requestAccess() }
            .show()
    }

    private fun requestAccess() {
        val intent = GameFolder.accessIntent(this)
        if (intent == null) {
            legacyPermission.launch(GameFolder.legacyPermissions)
            return
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Throwable) {
            }
        }
    }

    /** Back from the permission screen: carry on with the folder that was waiting. */
    private fun resumePendingFolder() {
        val path = prefs.pendingFolder
        if (path.isEmpty() || !GameFolder.hasAccess(this)) return
        val tree = pendingTree
        prefs.pendingFolder = ""
        pendingTree = null
        useFolder(File(path), tree)
    }

    /** Looks for default.xex in the folder (or one level down) and offers to use it. */
    private fun useFolder(picked: File, tree: Uri?) {
        thread(name = "folder-check") {
            val game = try {
                GameFolder.locate(picked)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                when {
                    game == null && picked.listFiles() == null && tree != null -> offerCopy(tree)
                    game == null -> message(getString(R.string.folder_no_xex))
                    else -> verifyAndSet(game, picked)
                }
            }
        }
    }

    private fun verifyAndSet(game: File, picked: File) {
        thread(name = "folder-hash") {
            val matches = try {
                GameFolder.sha256(File(game, GameFolder.XEX)).equals(BuildConfig.PES_XEX_SHA256, ignoreCase = true)
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (matches) {
                    setGameFolder(game, picked)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.folder_title)
                        .setMessage(getString(R.string.folder_wrong_game) + "\n\n" + game.absolutePath)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.folder_use_anyway) { _, _ -> setGameFolder(game, picked) }
                        .show()
                }
            }
        }
    }

    private fun setGameFolder(game: File, picked: File) {
        prefs.gameDirectory = game.absolutePath
        val inside = if (game != picked) getString(R.string.folder_found_inside, game.absolutePath) + "\n\n" else ""
        showDetail(getString(R.string.folder_title), inside + getString(R.string.folder_set, game.absolutePath))
        refresh()
    }

    private fun offerCopy(tree: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_title)
            .setMessage(R.string.folder_unreadable_path)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.copy_instead) { _, _ ->
                startActivity(
                    Intent(this, InstallActivity::class.java).apply {
                        data = tree
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
            .show()
    }

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(R.string.close, null).show()
    }

    // ---- the menu's state ------------------------------------------------------------

    private fun refresh() {
        if (!nativeReady) {
            status.text = getString(
                R.string.status_library_missing,
                NativeBridge.loadError() ?: getString(R.string.unknown_reason),
            )
            play.isEnabled = false
            footer.text = getString(
                R.string.footer, device(), getString(R.string.driver_system),
                BuildConfig.BUILD_SHA, BuildConfig.BUILD_STAMP,
            )
            return
        }

        // The chosen folder first; when there is none, or it has gone, look for
        // the game ourselves. Only when that finds nothing does the player
        // have to point at it.
        var gameDirectory = prefs.gameDirectory
        var autoFound = false
        if (gameDirectory.isEmpty() || !safely(false) { NativeBridge.nativeGameInstalled(gameDirectory) }) {
            val found = GameFolder.autoDetect(this)?.absolutePath
            if (found != null && safely(false) { NativeBridge.nativeGameInstalled(found) }) {
                gameDirectory = found
                prefs.gameDirectory = found
                autoFound = true
            }
        }
        val installed = gameDirectory.isNotEmpty() && safely(false) {
            NativeBridge.nativeGameInstalled(gameDirectory)
        }

        if (installed) {
            play.isEnabled = true
            play.text = getString(R.string.play)
            status.text = getString(if (autoFound) R.string.status_ready_auto else R.string.status_ready, gameDirectory)
            installButton.visibility = android.view.View.GONE

            if (relaunchRequested) {
                // An explicit "apply & restart" is a new match, so it wins
                // over the quit latch: QuickGraphics starts this activity and
                // ends the run in the same breath, and the latch exists to
                // stop an *unasked-for* start, not that one.
                relaunchRequested = false
                hasAutoLaunched = true
                prefs.clearMatchQuit()
                startGameWhenPreviousRunIsGone()
                return
            }
            // Back from a match the player quit: the Main Menu is where Quit
            // Game asked to be. "Kick off straight away" is about opening the
            // app, not about answering that question. The latch is checked on
            // every resume and is not consumed, so a launcher rebuilt on the
            // way back (rotation, low memory) or resumed again still stays on
            // the menu instead of starting another match.
            //
            // A fresh launch is not that return, though: the player may have
            // gone In-App Menu > Exit > Main Menu > Quit and opened the app
            // again, which is "opening the app" in the switch's own words.
            // `coldStart` tells the two apart, so the latch only holds a
            // rebuilt or resumed launcher -- never a new one.
            if (!coldStart && prefs.matchQuitRecently()) {
                hasAutoLaunched = true
                if (quitReturnedAt == 0L) quitReturnedAt = android.os.SystemClock.uptimeMillis()
                return
            }
            // A match starts by itself on a fresh launch only. `coldStart`
            // keeps a rebuilt screen (a rotation, a restart underneath the
            // player) from counting as "the app was opened", and the latch
            // keeps every later resume of the same instance on the menu.
            if (prefs.autoLaunch && !hasAutoLaunched && coldStart) {
                hasAutoLaunched = true
                // The latch has done its job for the return that set it; a
                // match started by opening the app must not leave it behind
                // for the next resume of this launcher.
                prefs.clearMatchQuit()
                startGame()
                return
            }
        } else {
            // Whatever disc images and extracted folders can be seen. The one the
            // player last picked wins; otherwise the likeliest (GameSources.find).
            detectedSources = GameSources.find(this, File(gameDirectory.ifEmpty { "/nonexistent" }))
            val chosen = detectedSources.firstOrNull { it.file.absolutePath == prefs.lastSource }
                ?: detectedSources.firstOrNull()
            detectedSource = chosen
            installButton.visibility = android.view.View.VISIBLE

            if (gameDirectory.isNotEmpty()) {
                // A folder was chosen but is no longer usable: say why.
                play.isEnabled = false
                play.text = getString(R.string.play)
                status.text = getString(
                    if (!GameFolder.hasAccess(this) && !gameDirectory.startsWith(filesDirParent()))
                        R.string.status_needs_access else R.string.status_folder_missing,
                    gameDirectory,
                )
            } else if (chosen != null) {
                play.isEnabled = true
                play.text = getString(if (chosen.isFolder) R.string.play else R.string.install_and_play)
                val more = if (detectedSources.size > 1) "\n" + getString(R.string.source_more, detectedSources.size - 1) else ""
                val ready = getString(
                    if (chosen.isFolder) R.string.source_plays_here else R.string.source_ready_setup,
                )
                status.text = getString(R.string.source_found, kindName(chosen), chosen.file.name, ready) + more
                installButton.text = if (detectedSources.size > 1) {
                    getString(R.string.choose_game_source, detectedSources.size)
                } else {
                    getString(R.string.choose_another_source)
                }
            } else {
                play.isEnabled = true
                play.text = getString(R.string.play)
                status.text = getString(R.string.status_not_installed)
                installButton.text = getString(R.string.entry_install)
                // Nothing installed and nothing to install from in sight: installing is the only way on.
                if (!installRequired) {
                    installRequired = true
                    startActivity(Intent(this, InstallActivity::class.java).putExtra(InstallActivity.EXTRA_REQUIRED, true))
                }
            }
        }

        applyDriverChoice()
        footer.text = getString(
            R.string.footer, device(), currentDriverName(),
            BuildConfig.BUILD_SHA, BuildConfig.BUILD_STAMP,
        )
    }

    private fun filesDirParent(): String = (getExternalFilesDir(null) ?: filesDir).absolutePath

    /** Pick which disc image or folder to install from, or go and look for another. */
    private fun chooseSource() {
        val names = detectedSources.map {
            "${it.file.name}  (${kindName(it)}, ${android.text.format.Formatter.formatFileSize(this, it.bytes)})"
        } + getString(R.string.choose_source)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.install_game)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < detectedSources.size) {
                    prefs.lastSource = detectedSources[which].file.absolutePath
                    refresh()
                } else {
                    startActivity(Intent(this, InstallActivity::class.java))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** "folder" or "ISO" in the player's language, for the status line. */
    private fun kindName(source: GameSource): String =
        getString(if (source.isFolder) R.string.source_kind_folder else R.string.source_kind_iso)

    private fun device(): String {
        val vkVersion = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == "android.hardware.vulkan.version" }?.version ?: 0
        val vkMajor = vkVersion shr 22
        val vkMinor = (vkVersion shr 12) and 0x3ff
        val vkStr = if (vkVersion > 0) "Vulkan $vkMajor.$vkMinor" else "No Vulkan"
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ($vkStr), " +
            "Android ${android.os.Build.VERSION.RELEASE}"
    }

    /** A native call that must not take the launcher with it if it fails. */
    private fun <T> safely(fallback: T, call: () -> T): T = try {
        call()
    } catch (_: Throwable) {
        fallback
    }

    private fun applyDriverChoice() {
        val directory = prefs.driverDirectory
        val library = prefs.driverLibrary
        if (directory.isNotEmpty() && library.isNotEmpty() && File(directory, library).exists()) {
            safely(Unit) { NativeBridge.nativeSelectDriver(directory, library) }
        } else {
            // A driver that was deleted from underneath the setting.
            if (directory.isNotEmpty()) {
                prefs.driverDirectory = ""
                prefs.driverLibrary = ""
            }
            safely(Unit) { NativeBridge.nativeSelectDriver("", "") }
        }
    }

    private fun currentDriverName(): String {
        val directory = prefs.driverDirectory
        if (directory.isEmpty()) return getString(R.string.driver_system)
        return File(directory).name
    }

    /**
     * The run that asked to be restarted is being killed as this is called: a
     * start now would find its activity still there (it is single task), bring
     * it forward and lose it a moment later. Wait for its process to be gone.
     */
    private fun startGameWhenPreviousRunIsGone(attempt: Int = 0) {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val alive = manager.runningAppProcesses?.any { it.processName == "$packageName:game" } == true
        if (alive && attempt < 40) {
            window.decorView.postDelayed({ startGameWhenPreviousRunIsGone(attempt + 1) }, 100)
        } else {
            startGame()
        }
    }

    private fun startGame() {
        if (!nativeReady) return
        // A match is being asked for on purpose: the quit latch has done its job.
        prefs.clearMatchQuit()
        // A copy of the saves before every run (SaveBackup), taken before the
        // game's process exists, so nothing is writing them while they are read.
        val context = applicationContext
        thread(name = "save-backup", isDaemon = true) {
            try { SaveBackup.automatic(context, prefs) } catch (_: Throwable) {}
        }.join(3000)
        GameActivity.start(this)
    }

    private fun showDetail(title: String, text: String) {
        detailTitle.text = title
        detailText.text = text
    }

    private var selectedEntry: Button? = null
    private var selectedColors: android.content.res.ColorStateList? = null

    /**
     * PES 6's cursor on the chosen entry: the ring beside it, the band under it,
     * its text in gold. The one before gets its own colour back.
     */
    private fun select(entry: Button) {
        selectedEntry?.let { previous ->
            previous.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            previous.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_menu_cursor_space, 0, 0, 0)
            selectedColors?.let { previous.setTextColor(it) }
        }
        selectedEntry = entry
        selectedColors = entry.textColors
        entry.setBackgroundResource(R.drawable.bg_entry_selected)
        entry.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_menu_cursor, 0, 0, 0)
        entry.setTextColor(getColor(R.color.pes_gold))
    }

    /**
     * Asks the release feed what the newest published release is.
     *
     * The check on start-up only speaks when there is something newer than this
     * install and it is not a version the player has skipped. The one the player
     * asks for always answers: the newer release, the newest version, or what
     * stopped the check -- never "up to date" because a request failed.
     */
    private fun checkForUpdates(manual: Boolean = false) {
        thread(name = "update-check", isDaemon = true) {
            val info = try {
                packageManager.getPackageInfo(packageName, 0)
            } catch (_: Throwable) {
                null
            }
            val installedCode = when {
                info == null -> 0
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode.toInt()
                else -> info.versionCode
            }
            val installedName = info?.versionName.orEmpty()
            val result = UpdateCheck.check(installedCode, installedName)
            runOnUiThread {
                when (result) {
                    is UpdateResult.Newer ->
                        // A version the player skipped stays skipped for the
                        // start-up check; a check they asked for offers it again.
                        if (manual || result.tag != prefs.seenUpdateTag) {
                            showUpdateDialog(result, installedName, installedCode)
                        }
                    is UpdateResult.Current ->
                        if (manual) message(getString(R.string.up_to_date, result.installed))
                    UpdateResult.NoReleases ->
                        if (manual) message(getString(R.string.update_check_none))
                    UpdateResult.RateLimited ->
                        if (manual) message(getString(R.string.update_check_rate_limited))
                    is UpdateResult.Unreachable ->
                        if (manual) message(getString(R.string.update_check_offline))
                    is UpdateResult.Unreadable ->
                        if (manual) message(getString(R.string.update_check_failed, result.detail))
                }
            }
        }
    }

    private fun showUpdateDialog(update: UpdateResult.Newer, installedName: String, installedCode: Int) {
        val body = TextView(this)
        body.setPadding(32, 8, 32, 8)
        body.textSize = 12f
        val installedText = getString(R.string.build_label, installedName, installedCode)
        val releaseText = if (update.releaseCode > 0) {
            getString(R.string.build_label, update.versionName, update.releaseCode)
        } else {
            update.versionName
        }
        body.text = getString(R.string.update_message, installedText, releaseText) + "\n\n" + update.notes
        val scroll = ScrollView(this)
        scroll.addView(body)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, update.versionName))
            .setView(scroll)
            .setPositiveButton(R.string.update_download) { _, _ ->
                if (update.apkUrl.isNotEmpty()) downloadAndInstall(update.apkUrl)
                else openPage(update.page)
            }
            .setNeutralButton(R.string.skip_version) { _, _ -> prefs.seenUpdateTag = update.tag }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPage(page: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        } catch (_: Throwable) {
        }
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            // System sends the user to allow installs from this app; the
            // download starts when they come back (onResume).
            pendingUpdateUrl = apkUrl
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Throwable) {
                pendingUpdateUrl = null
            }
            return
        }
        startUpdateDownload(apkUrl)
    }

    private fun startUpdateDownload(apkUrl: String) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle(getString(R.string.update_download_name))
                .setDescription(getString(R.string.update_download_description))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "pes17-update.apk")
                .setAllowedOverMetered(true)
            pendingInstallId = manager.enqueue(request)
            Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            openPage(UpdateCheck.PAGE)
        }
    }

    private fun installDownloadedApk(id: Long) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(id) ?: return
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            AlertDialog.Builder(this)
                .setMessage(R.string.update_failed)
                .setPositiveButton(R.string.open_page) { _, _ -> openPage(UpdateCheck.PAGE) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showLastCrash(crash: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.last_crash)
            .setMessage(crash.take(4000))
            .setPositiveButton(R.string.close) { _, _ -> Trail.forgetCrash(this) }
            .setNeutralButton(R.string.share) { _, _ -> share(crash) }
            .show()
    }

    /**
     * The log screen: a summary of it, with the whole thing one button away
     * (LogSummary says what is kept and what is only counted). Share sends the
     * whole log, because that is what a bug report needs.
     */
    private fun showLog() {
        val session = if (nativeReady) safely("") { NativeBridge.nativeLogTail() } else ""
        val renderer = if (nativeReady) safely("") { NativeBridge.nativeRendererInfo() } else ""
        showLogText(LogSummary.build(this, renderer, session), 11f)
    }

    /** Everything, as it was, for when the summary is not enough. */
    private fun showWholeLog() {
        val session = if (nativeReady) safely("") { NativeBridge.nativeLogTail() } else ""
        showLogText(LogSummary.whole(this, session), 10f)
    }

    /**
     * The log dialog: a bounded, scrolling viewer over a note box and the three
     * actions. Only this chrome is translated -- the lines in the viewer are the
     * runtime's own, kept exactly as written.
     *
     * The viewer has a height of its own rather than growing with its text: an
     * AlertDialog measures a custom view freely, and a few hundred log lines
     * used to push the note box and the button row off the screen. The buttons
     * live in the dialog's own footer, so bounding the scroller is what keeps
     * CLOSE, THE WHOLE LOG and EXPORT REPORT always reachable.
     */
    private fun showLogText(text: String, textSize: Float) {
        val density = resources.displayMetrics.density
        val body = TextView(this)
        body.setPadding(32, 24, 32, 24)
        body.textSize = textSize
        body.setTextIsSelectable(true)
        body.text = text
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val note = EditText(this).apply {
            hint = getString(R.string.log_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 3
            setSingleLine(false)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            // The viewer takes a fixed share of the window; the note box sits
            // under it, and both stay above the dialog's own button row.
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.45f).toInt()
                        .coerceAtLeast((120 * density).toInt()),
                ),
            )
            addView(
                note,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    val margin = (12 * density).toInt()
                    setMargins(margin, (8 * density).toInt(), margin, 0)
                },
            )
        }
        // CLOSE, THE WHOLE LOG and EXPORT REPORT are the dialog's own actions,
        // stacked in its content (DialogActions): the stock button bar has no
        // style here and drew white labels on the white surface.
        DialogActions.show(
            this,
            getString(R.string.view_log),
            column,
            listOf(
                DialogAction(getString(R.string.close), filled = true),
                DialogAction(getString(R.string.log_everything)) { showWholeLog() },
                DialogAction(getString(R.string.log_export_report)) {
                    share(report(note.text.toString(), text))
                },
            ),
        )
    }

    /**
     * What EXPORT REPORT sends: the player's note, when there is one, above the
     * whole log. The log lines themselves are untouched -- a report with a
     * translated error message would be a report nobody can search for.
     */
    private fun report(note: String, summary: String): String {
        val whole = wholeLog()
        if (note.isBlank()) return whole
        return buildString {
            append(getString(R.string.log_note_label)).append('\n')
            append(note.trim()).append("\n\n")
            append(whole.ifBlank { summary })
        }
    }

    /** What Share sends: every log and crash file, not the summary. */
    private fun wholeLog(): String {
        val session = if (nativeReady) safely("") { NativeBridge.nativeLogTail() } else ""
        return LogSummary.whole(this, session)
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, "PES 2017 log")
        intent.putExtra(Intent.EXTRA_TEXT, text.takeLast(200_000))
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}

/**
 * How long KICK OFF ignores its first press after a match was quit. The
 * confirmation that left the match can arrive as a click or a key on the
 * launcher's window as it appears; this is shorter than a deliberate tap, and
 * long enough to swallow that one event.
 */
private const val QUIT_INPUT_GUARD_MS = 700L

/** The end of a file, which is the part of a log anyone wants. */
fun File.readTextTail(bytes: Int): String = try {
    val length = length()
    if (length <= bytes) readText()
    else inputStream().use { stream ->
        stream.skip(length - bytes)
        stream.readBytes().toString(Charsets.UTF_8)
    }
} catch (_: Throwable) {
    ""
}
