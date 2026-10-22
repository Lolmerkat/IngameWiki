package dev.jan.ingamewiki.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.jan.ingamewiki.IngameWiki;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Settings stored in {@code config/ingamewiki.json}.
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final String DEFAULT_WIKI_URL = "https://minecraft.wiki";
	public static final String DEFAULT_HOME_PAGE = "Minecraft Wiki";

	/**
	 * How the key binding and {@code /wiki} open pages.
	 */
	public OpenMode mode = OpenMode.INGAME;

	/**
	 * Base URL of the MediaWiki site; the API is expected at {@code <wikiUrl>/api.php} and pages at
	 * {@code <wikiUrl>/w/<title>}.
	 */
	public String wikiUrl = DEFAULT_WIKI_URL;

	/**
	 * Page opened when there is nothing to look up.
	 */
	public String homePage = DEFAULT_HOME_PAGE;

	/**
	 * Whether the in-game renderer loads images.
	 */
	public boolean showImages = true;

	/**
	 * GUI pixels per CSS pixel for images in the in-game renderer.
	 */
	public float imageScale = 0.5F;

	/**
	 * Zoom factor of the in-game viewer's page content.
	 */
	public float zoom = 1.0F;

	/**
	 * Whether links to other sites ask for confirmation before opening in the browser.
	 */
	public boolean confirmExternalLinks = true;

	public static ModConfig load(Path file) {
		ModConfig config = null;
		if (Files.exists(file)) {
			try {
				config = GSON.fromJson(Files.readString(file), ModConfig.class);
			} catch (IOException | JsonParseException e) {
				IngameWiki.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		if (config == null) {
			config = new ModConfig();
		}
		config.sanitize();
		config.save(file);
		return config;
	}

	public void sanitize() {
		if (mode == null) {
			mode = OpenMode.INGAME;
		}
		wikiUrl = sanitizeUrl(wikiUrl);
		if (homePage == null || homePage.isBlank()) {
			homePage = DEFAULT_HOME_PAGE;
		}
		if (!(imageScale >= 0.1F && imageScale <= 2.0F)) {
			imageScale = 0.5F;
		}
		if (!(zoom >= 0.5F && zoom <= 3.0F)) {
			zoom = 1.0F;
		}
	}

	static String sanitizeUrl(String url) {
		if (url == null || url.isBlank()) {
			return DEFAULT_WIKI_URL;
		}
		String result = url.strip();
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		try {
			URI uri = URI.create(result);
			if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
				return DEFAULT_WIKI_URL;
			}
		} catch (IllegalArgumentException e) {
			return DEFAULT_WIKI_URL;
		}
		return result;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(this));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			IngameWiki.LOGGER.warn("Could not save {}", file, e);
		}
	}
}
