package dev.jan.ingamewiki.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.jan.qollib.http.Futures;
import dev.jan.qollib.http.ModHttp;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Talks to the MediaWiki action API of the configured wiki.
 */
public final class WikiClient {
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private final ModHttp http;
	private volatile WikiLinks links;

	public WikiClient(String userAgent, WikiLinks links) {
		this.http = new ModHttp(userAgent, TIMEOUT);
		this.links = links;
	}

	public WikiLinks links() {
		return links;
	}

	public void setLinks(WikiLinks links) {
		this.links = links;
	}

	/**
	 * Fetches the rendered HTML of a page, following redirects.
	 *
	 * @throws WikiException.Missing (wrapped) if the page does not exist
	 */
	public CompletableFuture<WikiPage> page(String title) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("action", "parse");
		params.put("page", title);
		params.put("prop", "text|displaytitle");
		params.put("redirects", "1");
		params.put("disableeditsection", "1");
		params.put("disablelimitreport", "1");
		params.put("disabletoc", "1");
		return api(params).thenApply(json -> {
			JsonObject error = object(json, "error");
			if (error != null) {
				if ("missingtitle".equals(string(error, "code")) || "invalidtitle".equals(string(error, "code"))) {
					throw new WikiException.Missing(title);
				}
				throw new WikiException(string(error, "info") != null ? string(error, "info") : "API error");
			}
			JsonObject parse = object(json, "parse");
			if (parse == null) {
				throw new WikiException("Unexpected response");
			}
			String fragment = null;
			JsonArray redirects = parse.has("redirects") ? parse.getAsJsonArray("redirects") : null;
			if (redirects != null && !redirects.isEmpty()) {
				fragment = string(redirects.get(redirects.size() - 1).getAsJsonObject(), "tofragment");
			}
			String canonical = string(parse, "title");
			String display = string(parse, "displaytitle");
			return new WikiPage(
					canonical == null ? title : canonical,
					display == null ? (canonical == null ? title : canonical) : stripTags(display),
					string(parse, "text") == null ? "" : string(parse, "text"),
					fragment == null ? null : WikiLinks.normalizeFragment(fragment));
		});
	}

	/**
	 * Full-text search.
	 */
	public CompletableFuture<SearchResults> search(String query, int limit) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("action", "query");
		params.put("list", "search");
		params.put("srsearch", query);
		params.put("srlimit", Integer.toString(limit));
		params.put("srprop", "snippet");
		params.put("srnamespace", "0");
		return api(params).thenApply(json -> {
			JsonObject q = object(json, "query");
			List<SearchHit> hits = new ArrayList<>();
			String suggestion = null;
			if (q != null) {
				JsonObject info = object(q, "searchinfo");
				if (info != null) {
					suggestion = string(info, "suggestion");
				}
				if (q.has("search")) {
					for (JsonElement e : q.getAsJsonArray("search")) {
						JsonObject hit = e.getAsJsonObject();
						String t = string(hit, "title");
						if (t != null) {
							hits.add(new SearchHit(t, string(hit, "snippet") == null ? "" : string(hit, "snippet")));
						}
					}
				}
			}
			return new SearchResults(query, hits, suggestion);
		});
	}

	/**
	 * Loads the page called {@code query}, or, if there is none, the best full-text search match.
	 */
	public CompletableFuture<WikiPage> lookup(String query) {
		return page(query).exceptionallyCompose(error -> {
			if (!(Futures.unwrap(error) instanceof WikiException.Missing)) {
				return CompletableFuture.failedFuture(error);
			}
			return search(query, 1).thenCompose(results -> {
				if (!results.hits().isEmpty()) {
					return page(results.hits().getFirst().title());
				}
				if (results.suggestion() != null && !results.suggestion().equalsIgnoreCase(query)) {
					return search(results.suggestion(), 1).thenCompose(suggested -> suggested.hits().isEmpty()
							? CompletableFuture.failedFuture(new WikiException.Missing(query))
							: page(suggested.hits().getFirst().title()));
				}
				return CompletableFuture.failedFuture(new WikiException.Missing(query));
			});
		});
	}

	public CompletableFuture<byte[]> bytes(URI uri) {
		return http.getBytes(uri);
	}

	private CompletableFuture<JsonObject> api(Map<String, String> params) {
		URI uri = URI.create(links.api() + "?format=json&formatversion=2&maxlag=5&" + ModHttp.form(params));
		HttpRequest request = http.request(uri).header("Accept", "application/json").build();
		return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new WikiException("HTTP " + response.statusCode());
			}
			try {
				return JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (JsonParseException | IllegalStateException e) {
				throw new WikiException("Invalid response", e);
			}
		});
	}

	private static @Nullable JsonObject object(JsonObject json, String key) {
		JsonElement e = json.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static @Nullable String string(JsonObject json, String key) {
		JsonElement e = json.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static String stripTags(String html) {
		return org.jsoup.Jsoup.parse(html).text();
	}
}
