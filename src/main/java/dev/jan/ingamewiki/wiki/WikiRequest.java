package dev.jan.ingamewiki.wiki;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * Something to show from the wiki.
 */
public sealed interface WikiRequest {
	/**
	 * Text used while loading and in the history.
	 */
	String label();

	/**
	 * The same thing on the website.
	 */
	URI webUrl(WikiLinks links);

	/**
	 * A page with a known title.
	 */
	record Page(String title, @Nullable String fragment) implements WikiRequest {
		@Override
		public String label() {
			return title;
		}

		@Override
		public URI webUrl(WikiLinks links) {
			return links.page(title, fragment);
		}
	}

	/**
	 * The page called {@code query}, or the best search match. Used for things looked up from the game.
	 */
	record Lookup(String query) implements WikiRequest {
		@Override
		public String label() {
			return query;
		}

		@Override
		public URI webUrl(WikiLinks links) {
			return links.search(query);
		}
	}

	/**
	 * The page called {@code query}, or a list of search results if there is none. Used for the search box.
	 */
	record Go(String query) implements WikiRequest {
		@Override
		public String label() {
			return query;
		}

		@Override
		public URI webUrl(WikiLinks links) {
			return links.search(query);
		}
	}

	/**
	 * Full-text search results.
	 */
	record Search(String query) implements WikiRequest {
		@Override
		public String label() {
			return query;
		}

		@Override
		public URI webUrl(WikiLinks links) {
			return URI.create(links.search(query).toString().replace("&go=Go", "&fulltext=1"));
		}
	}

	/**
	 * The list of bookmarked pages.
	 */
	record Bookmarks() implements WikiRequest {
		@Override
		public String label() {
			return "Bookmarks";
		}

		@Override
		public URI webUrl(WikiLinks links) {
			return URI.create(links.base() + "/");
		}
	}
}
