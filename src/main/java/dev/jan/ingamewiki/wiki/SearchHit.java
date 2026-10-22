package dev.jan.ingamewiki.wiki;

/**
 * One full-text search result.
 *
 * @param snippet HTML excerpt with matches wrapped in {@code <span class="searchmatch">}
 */
public record SearchHit(String title, String snippet) {
}
