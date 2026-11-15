# User-supplied visual comparison references — Phase 6

The user provided four PNG comparison screenshots on 2026-11-15.
The **PNG binaries are not yet committed** to this repository. They are
distributed separately as `phase6_visual_references.zip` in the ChatGPT
conversation and must be copied into this directory before running Codex.

| Expected filename | Reference content | Required interactive audit |
| --- | --- | --- |
| `01_home_player_comparison.png` | Kotlin and Flutter Home layouts, active player and differing content | Open Home in both, navigate carousels, start playback, inspect playing player |
| `02_home_states_comparison.png` | Recents, Listen again, Quick picks, dock and player states | Scroll/resize and compare matched sections, text, player state |
| `03_account_popup_comparison.png` | Kotlin multi-account popup versus Flutter simple session popover | Open avatar popup, inspect rows, selection, add/manage, close and persisted session |
| `04_settings_comparison.png` | Kotlin Settings category/source/module hierarchy versus Flutter minimal Appearance/Audio | Enumerate and navigate **every** Kotlin Settings menu and subpage, including disabled items |

The images are defect reports, not automatically paired goldens: first
identify which half/window is Kotlin versus Flutter using titlebar,
settings and app state, then collect your own matched screenshots.
Screenshots may contain personal profile names and account metadata;
do not log tokens, cookies, passwords, signed URLs or sensitive account
data in CI artifacts. Do not blindly commit raw new authenticated captures.

**One-command, checksum-verified installation and GitHub upload:** download
`phase6_visual_references.zip` from the ChatGPT conversation to
`~/Downloads/`, then run from the repository root:

```bash
bash flutterDesktop/verification/phase6/import_user_references.sh
```

The helper verifies SHA-256 hashes of all **original** PNGs, stages only those
four files, commits them, and pushes to `codex/phase6-player-lyrics`.
It rejects the wrong branch and pre-existing staged changes, and never
auto-merges. You can also pass a different ZIP location as its argument.

**File-transfer status:** the GitHub connector cannot receive binary bytes
straight from the chat ZIP in this session. The helper is committed, but the
four PNG files are **not** uploaded until the helper runs successfully.

If the archive is unavailable, report the four images as **NOT PRESENT**
and ask for the user-provided attachment rather than pretending to have
inspected them. Once installed, preserve filenames and add them to the
Phase 6 PR if user approves inclusion. Their absence must not be
misrepresented as proof of parity.

## Non-negotiable
Inspect both real apps after **interaction**, including actual playback
and Fullscreen Player. Build all Settings menu visuals and disable
unimplemented actions. See `.codex/phases/phase-6.md`.
