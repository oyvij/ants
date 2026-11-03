# Handoff: the brown Ants look

The owner chose a new look for Ants in a prototyping session. Build it into the real app. Every choice below is
final. Don't re-open them. If something can't be built as described, ask the owner before you change the look.

Open `docs/brand/prototype.html` in a browser first. It is the target: a fake home page made from the real `nav.kte`
markup and the real `ants.css`, plus a `<style>` block with the new rules. Its Dark mode and Light mode buttons switch
the theme. `prototype-light.png` and `prototype-dark.png` are screenshots of it.

## What the owner chose

1. **Wordmark, no icon, in the menu.** At the top left of the sidebar, in the phone top bar and on the login page,
   "Ants" is written in heavy system-ui letters with a caramel-to-chestnut gradient (`#d9a066` to `#a0582c`). Two
   antennae with round tips grow out of the top of the "A", and three bent legs stand under the word. Source:
   `docs/brand/wordmark.svg`. In the prototype the sidebar shows it 64px high and the top bar 44px high. No purple tile
   and no 🐜 emoji.
2. **Brown accent instead of purple.** The four accent variables become:
   `--acc: light-dark(#8a4a26, #d4a373)`, `--accHover: light-dark(#6e3a1d, #b8875a)`,
   `--accSoft: light-dark(#f6ede4, #2a1d14)`, `--accText: light-dark(#6b3a1e, #e8c9a8)`.
   Keep `--vio`/`--vioSoft`. They are a status colour, not the accent.
3. **Primary buttons are a cream outline.** `button.primary, .btn.primary`:
   `background: light-dark(#fbf4ec, #2a1d14); border: 1.5px solid light-dark(#8a4a26, #d4a373); color: light-dark(#6b3a1e, #f0d4b4)`.
   Hover: `background: light-dark(#f3e3d1, #3a2718)`.
4. **The big ant on Home.** Put the owner's ant (`docs/brand/ant-alive.svg`) 200×154px to the left of the greeting in
   `home.kte`. Its antennae twitch. The greeting ("Good morning/afternoon/evening", from `Home.kt`) uses the
   wordmark's style: 800 weight, about 30px, the same brown gradient as clipped text. In dark mode the ant sits on a
   soft cream spotlight, `radial-gradient(closest-side, rgba(242,238,232,.85), rgba(242,238,232,.35) 70%, transparent)`,
   because the dark brown ant vanishes on the dark page.
5. **The working ant replaces the ✻ spinner.** Everywhere `<span class="spin">✻</span>` shows today, show a small
   brown ant with a box on its back, running on the spot (legs move, body bobs, antennae twitch). Use
   `docs/brand/working-ant.svg` on light and `docs/brand/working-ant-dark.svg` (caramel) on dark. In the prototype it is
   about 30×19px next to the verb. The ✻ that labels a Thinking block in a transcript (`<summary>✻ Thinking`) is not a
   spinner. Leave it.
6. **App icon: the ant on a cream tile.** Keep `docs/icon.svg` (it draws `static/ant.svg` on a tile), but make the
   tile gradient `#f7f1e8` to `#e9dcc8` instead of purple, and re-render `icon-192.png` and `icon-512.png` with the
   command in the README's Icon section. `docs/brand/icon-cream.png` is a 512px render of it.

7. **README header on GitHub.** The README on github.com/oyvij/ants opens with the owner's ant beside the brown
   wordmark (choice 1), in place of the plain `# Ants` line. Details under "README header" below.

Out of scope: the 🐜 in the PR attribution ("🐜 Created with Ants"), `--vio`, and the manifest's `theme_color`.

## State of the working tree (uncommitted, on main)

Branch first. An earlier step of the same session left these changes, which you should build on:

- `src/main/resources/static/ant.svg`: the owner's ant drawing (made in Claude Design, owned by the owner). Keep it.
- `docs/icon.svg`: draws `../src/main/resources/static/ant.svg` on a purple tile. Change the tile to cream (choice 6).
- `icon-192.png`, `icon-512.png`: rendered from the purple version. Re-render them.
- `README.md`: the old Twemoji credit is gone, and an "Icon" section explains the render command. Keep it, and update
  it if you move files.
- `PwaTest.kt`: the icon test checks for a brown ant pixel, but still checks a purple tile (`#6366f1` top left,
  `#a855f7` bottom right). Update it to the cream tile.
- `docs/brand/`: this handoff and its assets.

Everything else is as on main. The menu and login logo is still the 🐜 emoji on the purple `.logo b` tile
(`nav.kte`, `login.kte`, `ants.css`), and `ShellTest.kt` asserts that markup. Replace it with the wordmark
(choice 1). `Security.kt` permits only `/manifest.webmanifest`, `/icon-*.png`, `/ants.css`, `/markdown.js`,
`/mermaid.js` and `/mcp` without login. Add any new static file the login page needs, such as the wordmark if it
becomes a file.

## How to build each piece

**Wordmark.** An inline SVG in `nav.kte` (twice: sidebar `.logo` and top bar `a.logo`) and `login.kte` keeps the text
real and themable. Put it in one jte template (for example `wordmark.kte`) and call it from all three places. Two
inline copies on one page must not share a gradient `id`, so pass the id in or define the gradient once. The login
page is light (`body.login .logo { color: var(--text) }`), and the gradient reads on light too. Check it. Keep an
accessible name (`aria-label="Ants"` or `<title>`). Drop the `.logo b` purple-tile rule. Check the sidebar's
`.logo` padding against the prototype's `.logo { padding-bottom: 10px }`.

**Accent and buttons.** Change the four variables in the `:root` block at the top of `ants.css` and the
`.primary` rules near line 145. Look for any hard-coded purple left: `grep -n "6366f1\|a855f7\|4f46e5\|818cf8\|4338ca" ants.css`.

**Big ant on Home.** Ship `ant-alive.svg` as a static file (for example `static/ant-alive.svg`) and use an `<img>`.
CSS animations inside an SVG still run when it is an `<img>`. The prototype puts the ant inside the `h1` with
`display:flex; gap:20px`. Think about the phone layout (`.ph` wraps) and keep it looking right at 390px. The dark
spotlight needs a dark-theme selector. `light-dark()` only takes colours, not gradients.
`ant-alive.svg` is `ant.svg` with the antennae wrapped in `<g class="antR">` / `<g class="antL">` and a `<style>` of
keyframes. It also has wrappers and keyframes-ready classes (`head`, `body`, `lid`, `sweat`) from other experiments.
Only the antennae animate. It honours `prefers-reduced-motion`.

**Working ant.** The least invasive way: keep the markup `<span class="spin">✻</span>` everywhere (so the server code
and its tests keep their shape), and draw the ant in CSS. Hide the glyph and show the SVG as a background:
`.spin { width: 1.9em; height: 1.2em; vertical-align: -.2em; color: transparent; overflow: hidden; background: url(/working-ant.svg) center / contain no-repeat; }`.
Then stop `liven()` in `static/shell.js` from cycling `GLYPHS` into `.spin` (around line 128), or the glyph change
does nothing visible but wastes work. `pending()` in `shell.js` also creates a `.spin` for buttons while they submit,
and that gets the ant too, which is fine. Remove `.side .spin { color: #93c5fd; }` and `button[aria-busy=true] .spin`
if they no longer matter.
Dark theme: the theme is the page's `<meta name="color-scheme">` (`light`, `dark` or `light dark`, from Settings), not
a class. `light-dark()` won't switch a `url()`. Pick the dark file with a selector the page already supports, such
as `:root:has(meta[name=color-scheme][content=dark]) .spin` plus a `@media (prefers-color-scheme: dark)` rule for
`light dark`. Or inline the SVG and colour it with CSS variables. Verify it in LookTest's dark shots.
Reduced motion: both SVGs carry `@media (prefers-reduced-motion: reduce) { * { animation: none !important } }`. Check
it holds when the SVG is a background image. If it doesn't, add a static fallback.
Where the spinner shows: `running.kte` (the sidebar's "N working"), `PageChats.kt:178`, `Transcript.kt:111` and
`:156`, and `shell.js` `pending()`. Make sure the ant isn't clipped where the spinner sits in tight spots (sidebar
row, chips, buttons).

**README header.** Make one banner SVG, for example `docs/readme-header.svg`, holding the ant (`ant-alive.svg`'s
drawing, antennae twitching) on the left and the wordmark on the right, and put it at the top of `README.md`:
`<h1 align="center"><img src="docs/readme-header.svg" alt="Ants" width="420"></h1>`. Keep the README's text below
it as it is. GitHub only shows an SVG through `<img>`, so the banner must be self-contained: inline the ant's markup
(no `<image href>` to another file, since GitHub's image proxy won't follow it), no scripts, no web fonts. Its own
`<style>` and CSS animations do run on GitHub. The wordmark's `system-ui` text falls back to each viewer's system font,
which is fine. If not, turn the text into paths. GitHub has a dark theme: the dark ant fades on it, so either give the
banner the cream spotlight behind the ant, or use `<picture>` with a
`<source media="(prefers-color-scheme: dark)" srcset="docs/readme-header-dark.svg">` that has the spotlight. Check it
on github.com in both themes after pushing the branch. A pushed branch's README shows at
`https://github.com/oyvij/ants/blob/<branch>/README.md`.

**App icon.** Edit the two `stop-color`s in `docs/icon.svg`, re-render both PNGs, and update `PwaTest`.

## Tests

Follow the project's CLAUDE.md. While building, run `scripts/affected-tests --run`. This change touches the pages, so
run the browser shards locally: `./gradlew test -Pshard=browser` and `./gradlew test -Pshard=look` (Chromium:
`./gradlew playwright --args="install chromium"`). Look at the LookTest screenshots in
`build/reports/browser/look/` and `look-phone/`, light and dark, and compare them with `prototype-*.png`.
Tests that mention the spinner or logo: `TranscriptTest`, `RunTest`, `HomeTest`, `RunPageTest`, `BrowserTest` (20
mentions of `✻`/`spin`), `ShellTest`, `PwaTest`. If you keep the `<span class="spin">✻</span>` markup, most of them
should pass unchanged. Before marking the PR ready, merge main and run the full suite on CI on that tip
(`gh workflow run ci.yml --ref <branch>`) until every shard is green.

## Done when

- The sidebar, phone top bar and login page show the brown wordmark with antennae and legs, and no tile or emoji.
- Links, accents and primary buttons are brown, primary buttons are cream outlines, and violet statuses are unchanged.
- Home shows the big ant with twitching antennae beside a brown gradient greeting, with a spotlight in dark mode.
- Every running spinner is the box-carrying ant, caramel on dark, and still under reduced motion.
- The app icons are the ant on a cream tile, and the README's Icon section is still correct.
- The README on GitHub opens with the ant and the brown wordmark, readable in GitHub's light and dark themes.
- LookTest shots in light and dark match the prototype, and CI is green on the merged tip.
- Delete `docs/brand/` in the PR once its assets live where the app serves them, or keep only what the README points to.
