package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every supported language carries every string the app asks for.
 *
 * A missing key is not a compile error: Android falls back to English, so a
 * settings page that was never translated looks translated in review and
 * English on the phone. This pins the count on the host, where a missing
 * translation is a failing test instead of a surprise in a menu.
 *
 * It also pins two things aapt2 will not: a format specifier that a translation
 * dropped (an `IllegalFormatConversionException` the moment the page opens) and
 * a key that was put in the wrong file, which is a duplicate-resource error at
 * build time and a silent overwrite at run time.
 *
 * The strings live in more than one file per folder -- `patch_import.xml` has
 * the patch and game-folder screens -- so a folder is read whole.
 */
class TranslationsTest {

    private val res: File = listOf(
        File("src/main/res"),
        File("android/app/src/main/res"),
    ).firstOrNull { it.isDirectory }
        ?: error("cannot find the app's res directory from ${File(".").absolutePath}")

    // Declared before anything that reads them: a field initialiser runs in
    // the order it is written, and the map below is built in one.
    private val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%(\d+)\$([sd])""")

    private val base: Map<String, String> = strings(File(res, "values"))

    /** Every `values-xx` folder that carries translations. */
    private val locales: List<File> = res.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values-") }
        .sortedBy { it.name }

    /**
     * Every string in a `values...` folder, wherever it lives, with the names
     * asserted unique across the folder: one key in two files is the build
     * error this pass produced, and it must not reach a build again.
     */
    private fun strings(folder: File): Map<String, String> {
        val files = folder.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }
        val found = LinkedHashMap<String, String>()
        val names = ArrayList<String>()
        for (file in files) {
            val text = file.readText()
            for (match in entry.findAll(text)) {
                val name = match.groupValues[1]
                names += "${file.name}/$name"
                found[name] = match.groupValues[2]
            }
        }
        val duplicated = names.groupBy { it.substringAfter('/') }.filterValues { it.size > 1 }.keys
        assertTrue("the same string is defined twice in ${folder.name}: $duplicated", duplicated.isEmpty())
        return found
    }

    /** `%1$s` / `%2$d`, in order, ignoring the literal `%%`. */
    private fun specifiers(text: String): List<String> =
        placeholder.findAll(text).map { it.groupValues[0] }.sorted().toList()

    @Test
    fun everyLanguageHasEveryString() {
        val missing = HashMap<String, List<String>>()
        for (locale in locales) {
            val translated = strings(locale)
            val absent = base.keys.filterNot(translated::containsKey).sorted()
            if (absent.isNotEmpty()) missing[locale.name] = absent
        }
        assertTrue("untranslated strings by language: $missing", missing.isEmpty())
    }

    @Test
    fun everyLanguageIsSupported() {
        // The languages the settings page offers, in the folders that carry them.
        val offered = setOf("ar", "es", "fr", "pt-rPT", "ru")
        assertEquals(offered, locales.map { it.name.removePrefix("values-") }.toSet())
    }

    @Test
    fun noLanguageInventsAStringTheAppNeverAsksFor() {
        for (locale in locales) {
            val extra = strings(locale).keys.filterNot(base::containsKey).sorted()
            assertTrue("unknown strings in ${locale.name}: $extra", extra.isEmpty())
        }
    }

    @Test
    fun aTranslationKeepsThePlaceholdersItFills() {
        val broken = ArrayList<String>()
        for (locale in locales) {
            val translated = strings(locale)
            for ((key, english) in base) {
                val other = translated[key] ?: continue
                if (specifiers(english) != specifiers(other)) {
                    broken += "${locale.name}/$key ${specifiers(english)} -> ${specifiers(other)}"
                }
            }
        }
        assertTrue("format specifiers changed: $broken", broken.isEmpty())
    }

    @Test
    fun theLanguagesTheSettingsPageOffersAreNamedInEveryLanguage() {
        for (key in listOf(
            "language_name_en", "language_name_ar", "language_name_fr",
            "language_name_es", "language_name_ru", "language_name_pt",
        )) {
            assertTrue("$key is missing from the base strings", base.containsKey(key))
        }
    }

    @Test
    fun theScreensThisPassTouchedAreTranslatedEverywhere() {
        // The preview, the sheet's switch, frame pacing and the labels that used
        // to be English in code: a review of them must not land on an English
        // page in a translated build.
        for (key in listOf(
            "left_stick_preview", "left_stick_preview_hint", "left_stick_preview_page_hint",
            "left_stick_preview_size", "left_stick_preview_back", "left_stick_menu",
            "left_stick_feel", "left_stick_curve", "left_stick_deadzone", "left_stick_sprint",
            "frame_pacing_mode", "frame_pacing_hint", "frame_pacing_refresh",
            "section_frame_pacing", "pacing_smooth", "pacing_compatibility",
            "hud_performance_title", "hud_close", "hud_cmd",
            "capture_button", "capture_stop_button",
            "macro_record_button", "macro_stop_button",
            "game_files_title", "game_files_warning", "patch_import_game_files",
        )) {
            assertTrue("$key is missing from the base strings", base.containsKey(key))
            for (locale in locales) {
                assertTrue(
                    "$key is missing from ${locale.name}",
                    strings(locale).containsKey(key),
                )
            }
        }
    }
}
