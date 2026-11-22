# PES 2017 Android 0.2.4

**Uninstall 0.2.3 before installing this.** Both are signed with the debug key
of the machine that cut them, and this one was cut somewhere else, so Android
will refuse it as an update. Back up your saves first (Settings > Back up
saves): they are in the app's own storage. Same requirements and Xbox 360
Europe game files as 0.2.3.

This build carries an engine rebuilt from the current sources rather than the
0.2.3 one, so the fixes below are actually in the game code.

## What's new

- **Music that went missing.** Before starting its own soundtrack the game asks
  the system's music player whether it owns playback. That question was answered
  with a success and nothing else, so on some phones the title decided music was
  playing elsewhere and kept the menu and stadium music off while effects and
  commentary still worked. The two queries are now answered properly, and a
  malformed one is refused and logged instead of guessed at.
- **Fast cores actually used on more chips.** A core counted as "big" only if it
  clocked faster than the rest, so a chip whose efficiency cores run close to
  its performance cores had every core marked fast and the big-core pinning did
  nothing. Cores are now classified by how much work they can do, with the clock
  as the fallback.
- **8 GB phones keep their settings.** The shader prewarm and memory thresholds
  were set just above what a phone sold as 8 GB reports (about 7.7 GB), so those
  phones were treated as smaller than they are. They now get the larger texture
  budget.
- **The Low preset is about memory again, not about guessing.** It no longer
  forces the low-memory path on a phone that has the RAM for more.
- **Finesse shots stay finesse shots.** Holding the sprint rim and then swiping
  to shoot left RB held down, which cancelled the finesse. It is released as the
  swipe starts.
- **Game files without the copy.** A folder that already holds the game files
  can be played in place; the installer no longer insists on copying it to the
  app's own storage first.
- **Launcher tidying:** selection fills recoloured, the password dialogs
  restyled to match.

## Known issues

Same as 0.2.0. Please send a capture (Settings > Advanced > Developer tools >
CAPTURE) with a report on anything new.
