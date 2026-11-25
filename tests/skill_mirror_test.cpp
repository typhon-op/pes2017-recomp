// The app's copy of the move table (SkillMoves.kt) against the runtime's
// (runtime/skill_moves.h).
//
// The app cannot ask the runtime what the table says -- it is compiled against
// a prebuilt engine -- so it repeats the count, the exit flags, the left-stick
// hints and the ability gates. That repetition is where a silent drift lives:
// the wheel would play one move while the picker named another, or a hint would
// say "then LS left" for a move that changed direction. The Kotlin file is read
// as text, so this check needs neither the Android toolchain nor a build of the
// runtime, only the source tree.
//
//   g++ -std=c++20 -I runtime -DPES17_SKILL_KT="\"$PWD/android/app/src/main/java/com/pes2017/recomp/SkillMoves.kt\"" -o skill_mirror_test tests/skill_mirror_test.cpp
//   ./skill_mirror_test [path to SkillMoves.kt]
//
// ctest passes the path itself (tests/CMakeLists.txt).
#include "skill_moves.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace
{
    int g_checks = 0;
    int g_failures = 0;

    void Check(bool ok, const char* what)
    {
        g_checks++;
        if (ok) return;
        g_failures++;
        std::fprintf(stdout, "FAIL: %s\n", what);
    }

    void CheckEqual(const std::string& got, const char* want, const char* what)
    {
        const bool ok = got == want;
        g_checks++;
        if (ok) return;
        g_failures++;
        std::fprintf(stdout, "FAIL: %s: app \"%s\", runtime \"%s\"\n", what, got.c_str(), want);
    }

    std::string ReadFile(const char* path)
    {
        std::ifstream in(path, std::ios::binary);
        std::ostringstream out;
        out << in.rdbuf();
        return out.str();
    }

    // The text between `key` and the matching close parenthesis, with a depth
    // count so a nested call could not end it early.
    std::string Block(const std::string& src, const char* key)
    {
        const size_t at = src.find(key);
        if (at == std::string::npos) return {};
        const size_t open = src.find('(', at);
        if (open == std::string::npos) return {};
        int depth = 0;
        for (size_t i = open; i < src.size(); i++)
        {
            // A hint can contain brackets of its own ("then LS right (drag
            // back: left)"), so a string literal is skipped whole: only the
            // array's own brackets count towards the depth.
            if (src[i] == '"')
            {
                for (i++; i < src.size() && src[i] != '"'; i++)
                    if (src[i] == '\\') i++;
                continue;
            }
            if (src[i] == '(') depth++;
            else if (src[i] == ')' && --depth == 0) return src.substr(open + 1, i - open - 1);
        }
        return {};
    }

    // A Kotlin string literal, with escapes folded back to the characters the
    // runtime would compare. Returns "" at the end of the block.
    std::string NextString(const std::string& body, size_t& i)
    {
        while (i < body.size() && body[i] != '"') i++;
        if (i >= body.size()) return {};
        std::string out;
        for (i++; i < body.size() && body[i] != '"'; i++)
        {
            if (body[i] != '\\' || i + 1 >= body.size()) { out += body[i]; continue; }
            const char escaped = body[++i];
            switch (escaped)
            {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                default: out += escaped; break;
            }
        }
        if (i < body.size()) i++;
        return out;
    }

    std::vector<std::string> StringArray(const std::string& src, const char* key)
    {
        const std::string body = Block(src, key);
        std::vector<std::string> out;
        for (size_t i = 0; i < body.size(); )
        {
            const std::string text = NextString(body, i);
            if (text.empty() && (i >= body.size() || body[i - 1] != '"')) break;
            out.push_back(text);
        }
        return out;
    }

    std::vector<bool> BoolArray(const std::string& src, const char* key)
    {
        const std::string body = Block(src, key);
        std::vector<bool> out;
        for (size_t i = 0; i < body.size(); )
        {
            const size_t t = body.find("true", i);
            const size_t f = body.find("false", i);
            if (t == std::string::npos && f == std::string::npos) break;
            if (t != std::string::npos && (f == std::string::npos || t < f))
            {
                out.push_back(true);
                i = t + 4;
            }
            else
            {
                out.push_back(false);
                i = f + 5;
            }
        }
        return out;
    }

    // `const val COUNT = 15`, as the number the app stores layouts with.
    int Count(const std::string& src)
    {
        const size_t at = src.find("const val COUNT");
        if (at == std::string::npos) return -1;
        const size_t equals = src.find('=', at);
        if (equals == std::string::npos) return -1;
        return std::atoi(src.c_str() + equals + 1);
    }

    std::string Label(const char* field, size_t index)
    {
        char text[96];
        std::snprintf(text, sizeof text, "%s[%zu]", field, index);
        return text;
    }
}

int main(int argc, char** argv)
{
    const char* path =
#ifdef PES17_SKILL_KT
        PES17_SKILL_KT;
#else
        "android/app/src/main/java/com/pes2017/recomp/SkillMoves.kt";
#endif
    if (argc > 1) path = argv[1];

    const std::string src = ReadFile(path);
    if (src.empty())
    {
        std::fprintf(stderr, "cannot read %s\n", path);
        return 1;
    }

    const int count = Count(src);
    if (count != skill::kMoveCount)
    {
        g_checks++;
        g_failures++;
        std::fprintf(stdout, "FAIL: COUNT: app %d, runtime %d\n", count, skill::kMoveCount);
    }
    else
    {
        g_checks++;
    }

    const std::vector<std::string> names = StringArray(src, "NAMES = arrayOf(");
    const std::vector<std::string> shortNames = StringArray(src, "SHORT = arrayOf(");
    const std::vector<bool> exits = BoolArray(src, "LEAVES_EXIT = booleanArrayOf(");
    const std::vector<std::string> hints = StringArray(src, "HINTS = arrayOf(");
    const std::vector<std::string> abilities = StringArray(src, "ABILITIES = arrayOf(");

    Check(int(names.size()) == skill::kMoveCount, "the app names every move");
    Check(int(shortNames.size()) == skill::kMoveCount, "the app has a short name per move");
    Check(int(exits.size()) == skill::kMoveCount, "the app has an exit flag per move");
    Check(int(hints.size()) == skill::kMoveCount, "the app has a hint per move");
    Check(int(abilities.size()) == skill::kMoveCount, "the app has an ability per move");

    for (int i = 0; i < skill::kMoveCount && size_t(i) < exits.size(); i++)
        Check(exits[size_t(i)] == skill::kMoves[i].leavesExitToPlayer,
              Label("LEAVES_EXIT", size_t(i)).c_str());

    for (int i = 0; i < skill::kMoveCount && size_t(i) < hints.size(); i++)
        CheckEqual(hints[size_t(i)], skill::kMoves[i].exitHint ? skill::kMoves[i].exitHint : "",
                   Label("HINTS", size_t(i)).c_str());

    for (int i = 0; i < skill::kMoveCount && size_t(i) < abilities.size(); i++)
        CheckEqual(abilities[size_t(i)],
                   skill::kMoves[i].ability ? skill::kMoves[i].ability : "",
                   Label("ABILITIES", size_t(i)).c_str());

    for (size_t i = 0; i < names.size() && i < shortNames.size(); i++)
    {
        Check(!names[i].empty(), Label("NAMES", i).c_str());
        Check(!shortNames[i].empty(), Label("SHORT", i).c_str());
        for (size_t j = 0; j < i; j++)
            Check(names[i] != names[j], Label("NAMES (a duplicate)", i).c_str());
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
