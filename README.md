# Ingame Wiki

Client-side Fabric mod (Minecraft 26.3) for reading [Minecraft Wiki](https://minecraft.wiki/) pages without leaving
the game. Pages can be shown three ways; you can switch between them at any time:

| Mode | What it does |
| --- | --- |
| **In-game** (default) | The mod renders the page itself: text, links, headings, lists, tables, infoboxes, images, inventory slots and crafting/smelting grids, plus a table of contents. |
| **Browser** | Opens the page in your system web browser. |
| **MCEF** | Shows the real website in an embedded Chromium. Needs [MCEF Modern](https://modrinth.com/mod/mcef-modern), which downloads Chromium on first use. Without it the page opens in-game instead. |

## Opening pages
- **H** (rebindable, "Look Up in Wiki"): opens the page for the block or entity under the crosshair, or for the
  hovered item in any inventory screen. If nothing is targeted, the main page opens.
- **/wiki** opens the main page; **/wiki &lt;name&gt;** opens that page, or the best search match.
- "Open Wiki" key (unbound by default): reopens the wiki where you last closed it (in-game, with its back/forward
  history and scroll position), or the main page the first time. The last page is remembered across restarts.

Items, blocks and entities are looked up by their English name, since the wiki uses English titles whatever the game
language is.

## Switching modes
- The **Open: …** button in the wiki screen, the MCEF screen and the settings
- **/wikimode ingame|browser|mcef** (or `/wikimode` to show the current mode)
- "Cycle Wiki Open Mode" key (unbound by default)

The wiki screen also has buttons that open the current page in the browser or in MCEF, and the MCEF screen has one that
opens the current page in-game.

## The in-game viewer
- Back/forward buttons, mouse buttons 4/5, Alt+←/→ or Backspace
- Search box (Ctrl+F or Ctrl+L): opens the page with that name, or lists search results
- Bookmarks: ☆ (or Ctrl+D) bookmarks the current page, ☰ (or Ctrl+B) lists your bookmarks, where "(remove)"
  deletes one. Bookmarks are kept per wiki URL in `config/ingamewiki-data.json`.
- Click a link to follow it. Middle-click or Ctrl+click opens it in the browser instead.
- Scroll with the mouse wheel, the scrollbar, arrow keys, Page Up/Down, Space, Home/End. F5 reloads.
- Zoom with Ctrl+scroll wheel or Ctrl+Plus/Minus (50–300 %); Ctrl+0 or the zoom button resets to 100 %. The page
  reflows to fit the window at every zoom level.

Some things are not rendered: SVG and WebP images (the wiki does not rasterize SVGs), animations (the first frame is
shown), navigation boxes and anything that needs JavaScript.

## Config (`config/ingamewiki.json`, or ⚙ / Mod Menu)
| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `INGAME` | `INGAME`, `BROWSER` or `MCEF` |
| `wikiUrl` | `https://minecraft.wiki` | Any MediaWiki site with the API at `<wikiUrl>/api.php` and pages at `<wikiUrl>/w/<title>`, e.g. a language edition such as `https://de.minecraft.wiki` |
| `homePage` | `Minecraft Wiki` | Page opened when nothing is looked up |
| `showImages` | `true` | Load images in the in-game viewer |
| `imageScale` | `0.5` | GUI pixels per website pixel for images |
| `zoom` | `1.0` | Zoom of the in-game viewer (0.5–3.0) |
| `confirmExternalLinks` | `true` | Ask before opening links to other sites |

## Building
`./gradlew build` (tests: `./gradlew test`, dev client: `./gradlew runClient`). Requires Java 25.
`./gradlew runClient -Pmcef` adds MCEF Modern to the dev client (it downloads about 500 MB of Chromium into
`run/config/mcef-modern` on first start).

This mod uses the public MediaWiki API of minecraft.wiki and is not affiliated with the Minecraft Wiki or Mojang.
