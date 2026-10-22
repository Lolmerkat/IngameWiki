package dev.jan.ingamewiki.wiki;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param suggestion spelling suggestion offered by the wiki, if any
 */
public record SearchResults(String query, List<SearchHit> hits, @Nullable String suggestion) {
}
