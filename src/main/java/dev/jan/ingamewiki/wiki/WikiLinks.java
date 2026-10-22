package dev.jan.ingamewiki.wiki;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Converts between page titles and URLs of a MediaWiki site such as {@code https://minecraft.wiki}.
 */
public final class WikiLinks {
	private final String base;
	private final URI baseUri;

	/**
	 * @param base site URL without trailing slash, e.g. {@code https://minecraft.wiki}
	 */
	public WikiLinks(String base) {
		this.base = base;
		this.baseUri = URI.create(base + "/");
	}

	public String base() {
		return base;
	}

	public URI api() {
		return URI.create(base + "/api.php");
	}

	public URI page(String title, @Nullable String fragment) {
		String url = base + "/w/" + encodeTitle(title);
		if (fragment != null && !fragment.isEmpty()) {
			url += "#" + encodeTitle(fragment);
		}
		return URI.create(url);
	}

	/**
	 * A URL that shows the page called {@code query} if it exists, and search results otherwise.
	 */
	public URI search(String query) {
		return URI.create(base + "/index.php?title=Special:Search&go=Go&search=" + encode(query));
	}

	/**
	 * Resolves a possibly relative URL (as found in {@code src} or {@code href} attributes) against the site.
	 */
	public @Nullable URI resolve(String url) {
		try {
			if (url.startsWith("//")) {
				return URI.create(baseUri.getScheme() + ":" + url);
			}
			return baseUri.resolve(url.replace(" ", "%20"));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Classifies a link target found on a page.
	 */
	public @Nullable Target parse(String href) {
		if (href.isEmpty()) {
			return null;
		}
		if (href.startsWith("#")) {
			return new Target.Anchor(normalizeFragment(href.substring(1)));
		}
		URI uri = resolve(href);
		if (uri == null || uri.getScheme() == null) {
			return null;
		}
		if (!sameSite(uri)) {
			return uri.getScheme().startsWith("http") ? new Target.External(uri) : null;
		}
		String title = title(uri);
		if (title == null) {
			return new Target.External(uri);
		}
		String fragment = uri.getRawFragment() == null ? null : normalizeFragment(uri.getRawFragment());
		return new Target.Page(title, fragment);
	}

	/**
	 * Returns the page title a URL of this site points at, or {@code null} for anything that is not an article view
	 * (special pages, edit links with other actions, files on the image server...).
	 */
	public @Nullable String title(URI uri) {
		if (!sameSite(uri)) {
			return null;
		}
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		String basePath = baseUri.getRawPath() == null ? "/" : baseUri.getRawPath();
		if (!path.startsWith(basePath)) {
			return null;
		}
		path = path.substring(basePath.length());
		String title = null;
		if (path.startsWith("w/") && path.length() > 2) {
			title = decode(path.substring(2));
		} else if (path.equals("index.php") || path.isEmpty()) {
			String query = uri.getRawQuery();
			if (query == null) {
				return null;
			}
			String action = null;
			for (String part : query.split("&")) {
				int eq = part.indexOf('=');
				String key = eq < 0 ? part : part.substring(0, eq);
				String value = eq < 0 ? "" : decode(part.substring(eq + 1));
				if (key.equals("title")) {
					title = value;
				} else if (key.equals("action")) {
					action = value;
				}
			}
			// Red links point to the editor; treat them as page views so the missing page can be reported.
			if (action != null && !action.equals("view") && !action.equals("edit")) {
				return null;
			}
		}
		if (title == null || title.isBlank()) {
			return null;
		}
		title = title.replace('_', ' ').strip();
		if (title.regionMatches(true, 0, "Special:", 0, 8)) {
			return null;
		}
		return title;
	}

	private boolean sameSite(URI uri) {
		return uri.getHost() != null && uri.getHost().equalsIgnoreCase(baseUri.getHost());
	}

	/**
	 * Anchors on MediaWiki pages use underscores for spaces and are percent-decoded in the DOM.
	 */
	public static String normalizeFragment(String fragment) {
		return decode(fragment).replace(' ', '_');
	}

	public static String encodeTitle(String title) {
		return encode(title.strip().replace(' ', '_'))
				.replace("%2F", "/")
				.replace("%3A", ":")
				.replace("%28", "(")
				.replace("%29", ")")
				.replace("%2C", ",");
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return value;
		}
	}

	/**
	 * Turns a registry path such as {@code diamond_sword} into a title-cased name ({@code Diamond Sword}).
	 */
	public static String titleCase(String path) {
		StringBuilder sb = new StringBuilder();
		for (String word : path.split("[_/]+")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
		}
		return sb.toString();
	}

	/**
	 * Where a link on a wiki page leads.
	 */
	public sealed interface Target {
		/**
		 * A page of the configured wiki.
		 */
		record Page(String title, @Nullable String fragment) implements Target {
		}

		/**
		 * A section of the current page.
		 */
		record Anchor(String fragment) implements Target {
		}

		/**
		 * Anything else; opened in the system browser.
		 */
		record External(URI uri) implements Target {
		}
	}
}
