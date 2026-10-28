package dev.jan.ingamewiki.config;

import dev.jan.qollib.config.JsonConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Bookmarks and the last viewed page, stored in {@code config/ingamewiki-data.json}. Entries remember the wiki they
 * belong to, so switching {@link ModConfig#wikiUrl} does not mix up titles of different sites.
 */
public final class WikiData implements JsonConfig {

	public List<Bookmark> bookmarks = new ArrayList<>();

	/**
	 * Page the wiki was last closed on.
	 */
	public @Nullable LastPage lastPage;

	/**
	 * @param wiki base URL of the wiki, as in {@link ModConfig#wikiUrl}
	 */
	public record Bookmark(String wiki, String title) {
	}

	public record LastPage(String wiki, String title, @Nullable String fragment) {
	}

	@Override
	public void sanitize() {
		if (bookmarks == null) {
			bookmarks = new ArrayList<>();
		}
		bookmarks.removeIf(b -> b == null || b.wiki() == null || b.title() == null || b.title().isBlank());
		if (lastPage != null && (lastPage.wiki() == null || lastPage.title() == null || lastPage.title().isBlank())) {
			lastPage = null;
		}
	}

	public boolean isBookmarked(String wiki, String title) {
		return bookmarks.contains(new Bookmark(wiki, title));
	}

	/**
	 * Adds or removes a bookmark.
	 *
	 * @return whether the page is bookmarked now
	 */
	public boolean toggleBookmark(String wiki, String title) {
		Bookmark bookmark = new Bookmark(wiki, title);
		if (bookmarks.remove(bookmark)) {
			return false;
		}
		bookmarks.add(bookmark);
		return true;
	}

	public void removeBookmark(String wiki, String title) {
		bookmarks.remove(new Bookmark(wiki, title));
	}

	/**
	 * Titles bookmarked on {@code wiki}, sorted alphabetically.
	 */
	public List<String> bookmarks(String wiki) {
		return bookmarks.stream()
				.filter(b -> b.wiki().equals(wiki))
				.map(Bookmark::title)
				.sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
				.toList();
	}
}
