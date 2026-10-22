package dev.jan.ingamewiki.wiki;

import org.jspecify.annotations.Nullable;

/**
 * A parsed wiki page.
 *
 * @param title        canonical title after following redirects
 * @param displayTitle title as shown on the page, without markup
 * @param html         rendered page content (the {@code mw-parser-output} element)
 * @param fragment     section a redirect points at, if any
 */
public record WikiPage(String title, String displayTitle, String html, @Nullable String fragment) {
}
