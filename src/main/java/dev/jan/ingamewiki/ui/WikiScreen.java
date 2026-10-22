package dev.jan.ingamewiki.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jan.ingamewiki.IngameWiki;
import dev.jan.ingamewiki.WikiOpener;
import dev.jan.ingamewiki.config.ModConfig;
import dev.jan.ingamewiki.config.OpenMode;
import dev.jan.ingamewiki.config.WikiData;
import dev.jan.ingamewiki.mcef.McefSupport;
import dev.jan.ingamewiki.render.HtmlLayout;
import dev.jan.ingamewiki.render.ImageCache;
import dev.jan.ingamewiki.render.LayoutOptions;
import dev.jan.ingamewiki.render.Link;
import dev.jan.ingamewiki.render.PageLayout;
import dev.jan.ingamewiki.render.Piece;
import dev.jan.ingamewiki.wiki.SearchHit;
import dev.jan.ingamewiki.wiki.SearchResults;
import dev.jan.ingamewiki.wiki.WikiClient;
import dev.jan.ingamewiki.wiki.WikiException;
import dev.jan.ingamewiki.wiki.WikiLinks;
import dev.jan.ingamewiki.wiki.WikiPage;
import dev.jan.ingamewiki.wiki.WikiRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jspecify.annotations.Nullable;

/**
 * Shows wiki pages rendered by {@link HtmlLayout}, with back/forward history, a search box and a table of contents.
 */
public final class WikiScreen extends Screen {
	private static final int BAR_HEIGHT = 26;
	private static final int MARGIN = 6;
	private static final int PAD = 8;
	private static final int SCROLLBAR = 6;
	private static final int TOC_WIDTH = 130;
	private static final int MAX_TEXT_WIDTH = 900;
	private static final int SCROLL_STEP = 30;
	private static final int PANEL_COLOR = 0xD0141414;
	private static final int CACHE_SIZE = 24;
	private static final float[] ZOOM_STEPS = {0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 1.75F, 2.0F, 2.5F, 3.0F};

	/**
	 * Recently loaded pages, so going back and forth does not hit the network.
	 */
	private static final Map<WikiRequest, Loaded> CACHE = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<WikiRequest, Loaded> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	/**
	 * A page ready to be laid out.
	 *
	 * @param title    canonical page title, or {@code null} for generated pages (search results)
	 * @param fragment section to scroll to after loading
	 */
	private record Loaded(@Nullable String title, String displayTitle, Document document, @Nullable String fragment,
			URI webUrl, String base) {
	}

	private static final class Entry {
		final WikiRequest request;
		double scroll;
		boolean restoreScroll;

		Entry(WikiRequest request) {
			this.request = request;
		}
	}

	private final @Nullable Screen parent;
	private final Deque<Entry> back = new ArrayDeque<>();
	private final Deque<Entry> forward = new ArrayDeque<>();
	private Entry current;

	private @Nullable Loaded page;
	private @Nullable PageLayout layout;
	private @Nullable LayoutOptions layoutOptions;
	/**
	 * GUI pixels per layout pixel of the current layout.
	 */
	private float zoom = 1.0F;
	private @Nullable String pendingFragment;
	private @Nullable Component message;
	private boolean messageIsError;
	private int generation;
	private boolean started;

	private double scroll;
	private double tocScroll;
	private boolean draggingScrollbar;
	private @Nullable Link hoveredLink;

	private @Nullable EditBox search;
	private @Nullable Button backButton;
	private @Nullable Button forwardButton;
	private @Nullable Button modeButton;
	private @Nullable Button zoomButton;
	private @Nullable Button bookmarkButton;

	public WikiScreen(@Nullable Screen parent, WikiRequest request) {
		super(Component.translatable("ingamewiki.title"));
		this.parent = parent;
		this.current = new Entry(request);
	}

	// ---------------------------------------------------------------- geometry

	private boolean tocVisible() {
		return layout != null && !layout.toc().isEmpty() && width >= 520;
	}

	private int panelLeft() {
		return MARGIN + (tocVisible() ? TOC_WIDTH + MARGIN : 0);
	}

	private int panelRight() {
		return width - MARGIN;
	}

	private int panelTop() {
		return BAR_HEIGHT + 2;
	}

	private int panelBottom() {
		return height - MARGIN;
	}

	private int textWidth() {
		return Math.max(60, Math.min(MAX_TEXT_WIDTH, panelRight() - panelLeft() - 2 * PAD - SCROLLBAR));
	}

	/**
	 * Width of the text column in layout pixels at the given zoom.
	 */
	private int layoutWidth(float zoom) {
		return Math.max(60, Math.round(textWidth() / zoom));
	}

	private int originX() {
		int avail = panelRight() - panelLeft() - 2 * PAD - SCROLLBAR;
		return panelLeft() + PAD + Math.max(0, (avail - textWidth()) / 2);
	}

	private int originY() {
		return panelTop() + PAD - (int) scroll;
	}

	private int viewHeight() {
		return panelBottom() - panelTop();
	}

	private double maxScroll() {
		return layout == null ? 0 : Math.max(0, (int) Math.ceil(layout.height() * zoom) + 2 * PAD - viewHeight());
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		int x = 4;
		backButton = addRenderableWidget(iconButton("←", "ingamewiki.back", x, b -> goBack()));
		forwardButton = addRenderableWidget(iconButton("→", "ingamewiki.forward", x += 22, b -> goForward()));
		addRenderableWidget(iconButton("⌂", "ingamewiki.home", x += 22, b -> navigate(WikiOpener.home())));
		addRenderableWidget(iconButton("⟳", "ingamewiki.reload", x += 22, b -> reload()));
		bookmarkButton = addRenderableWidget(iconButton("☆", "ingamewiki.bookmark.add", x += 22, b -> toggleBookmark()));
		addRenderableWidget(iconButton("☰", "ingamewiki.bookmarks.tooltip", x += 22, b -> openBookmarks()));
		int left = x + 24;

		int right = width - 4;
		addRenderableWidget(iconButton("⚙", "ingamewiki.settings", right -= 20, b -> minecraft.gui.setScreen(new ConfigScreen(this))));
		if (McefSupport.installed()) {
			addRenderableWidget(iconButton("◎", "ingamewiki.openMcef", right -= 22, b -> openCurrentIn(OpenMode.MCEF)));
		}
		addRenderableWidget(iconButton("↗", "ingamewiki.openBrowser", right -= 22, b -> openCurrentIn(OpenMode.BROWSER)));
		zoomButton = addRenderableWidget(Button.builder(zoomLabel(), b -> setZoom(1.0F))
				.bounds(right -= 34, 3, 32, 20)
				.tooltip(Tooltip.create(Component.translatable("ingamewiki.zoom.tooltip")))
				.build());
		int modeWidth = Math.clamp((right - left) / 3, 60, 100);
		right -= modeWidth + 2;
		modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> cycleMode())
				.bounds(right, 3, modeWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("ingamewiki.mode.tooltip")))
				.build());

		String previous = search == null ? null : search.getValue();
		search = addRenderableWidget(new EditBox(font, left, 4, Math.max(40, right - left - 4), 18, search,
				Component.translatable("ingamewiki.search")));
		search.setMaxLength(256);
		search.setHint(Component.translatable("ingamewiki.search").withStyle(EditBox.SEARCH_HINT_STYLE));
		if (previous != null) {
			search.setValue(previous);
		} else if (page != null) {
			search.setValue(page.displayTitle());
		}
		updateButtons();

		if (!started) {
			started = true;
			load(current, false);
		} else if (page != null && (!page.base().equals(IngameWiki.client().links().base()))) {
			reload();
		} else {
			relayout();
		}
	}

	private Button iconButton(String icon, String tooltip, int x, Button.OnPress onPress) {
		return Button.builder(Component.literal(icon), onPress)
				.bounds(x, 3, 20, 20)
				.tooltip(Tooltip.create(Component.translatable(tooltip)))
				.build();
	}

	private Component modeLabel() {
		return Component.translatable("ingamewiki.mode.button", IngameWiki.config().mode.label());
	}

	private void cycleMode() {
		IngameWiki.setMode(IngameWiki.config().mode.next());
		if (modeButton != null) {
			modeButton.setMessage(modeLabel());
		}
	}

	private static Component zoomLabel() {
		return Component.translatable("ingamewiki.zoom", Math.round(IngameWiki.config().zoom * 100));
	}

	/**
	 * Zooms one step in ({@code direction > 0}) or out.
	 */
	private void stepZoom(int direction) {
		float current = IngameWiki.config().zoom;
		float next = current;
		if (direction > 0) {
			for (float step : ZOOM_STEPS) {
				if (step > current + 0.01F) {
					next = step;
					break;
				}
			}
		} else {
			for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) {
				if (ZOOM_STEPS[i] < current - 0.01F) {
					next = ZOOM_STEPS[i];
					break;
				}
			}
		}
		setZoom(next);
	}

	private void setZoom(float value) {
		ModConfig config = IngameWiki.config();
		if (config.zoom == value) {
			return;
		}
		config.zoom = value;
		IngameWiki.saveConfig();
		if (zoomButton != null) {
			zoomButton.setMessage(zoomLabel());
		}
		relayout();
	}

	private void updateButtons() {
		if (backButton != null) {
			backButton.active = !back.isEmpty();
		}
		if (forwardButton != null) {
			forwardButton.active = !forward.isEmpty();
		}
		if (modeButton != null) {
			modeButton.setMessage(modeLabel());
		}
		if (bookmarkButton != null) {
			String title = page == null ? null : page.title();
			boolean bookmarked = title != null && IngameWiki.data().isBookmarked(page.base(), title);
			bookmarkButton.active = title != null;
			bookmarkButton.setMessage(bookmarked ? Component.literal("★").withStyle(ChatFormatting.GOLD) : Component.literal("☆"));
			bookmarkButton.setTooltip(Tooltip.create(Component.translatable(bookmarked ? "ingamewiki.bookmark.remove" : "ingamewiki.bookmark.add")));
		}
	}

	private void toggleBookmark() {
		if (page == null || page.title() == null) {
			return;
		}
		IngameWiki.data().toggleBookmark(page.base(), page.title());
		IngameWiki.saveData();
		updateButtons();
	}

	private void openBookmarks() {
		if (current.request instanceof WikiRequest.Bookmarks) {
			reload();
		} else {
			navigate(new WikiRequest.Bookmarks());
		}
	}

	@Override
	protected void setInitialFocus() {
		// Keep the focus off the search box so key presses scroll the page.
	}

	private void openCurrentIn(OpenMode mode) {
		WikiRequest request = page != null && page.title() != null
				? new WikiRequest.Page(page.title(), currentSection())
				: current.request;
		if (mode == OpenMode.MCEF) {
			McefSupport.open(parent, request.webUrl(IngameWiki.client().links()));
		} else {
			WikiOpener.openIn(mode, request, parent);
		}
	}

	// ---------------------------------------------------------------- navigation

	public void navigate(WikiRequest request) {
		current.scroll = scroll;
		back.push(current);
		forward.clear();
		current = new Entry(request);
		load(current, false);
	}

	private void goBack() {
		if (back.isEmpty()) {
			return;
		}
		current.scroll = scroll;
		forward.push(current);
		current = back.pop();
		current.restoreScroll = true;
		load(current, false);
	}

	private void goForward() {
		if (forward.isEmpty()) {
			return;
		}
		current.scroll = scroll;
		back.push(current);
		current = forward.pop();
		current.restoreScroll = true;
		load(current, false);
	}

	private void reload() {
		current.scroll = scroll;
		current.restoreScroll = true;
		load(current, true);
	}

	private void load(Entry entry, boolean bypassCache) {
		int gen = ++generation;
		updateButtons();
		hoveredLink = null;
		if (entry.request instanceof WikiRequest.Bookmarks) {
			// Built locally and always up to date, so neither cached nor fetched.
			show(entry, bookmarksPage(IngameWiki.client().links()));
			return;
		}
		Loaded cached;
		synchronized (CACHE) {
			cached = bypassCache ? null : CACHE.get(entry.request);
		}
		if (cached != null && cached.base().equals(IngameWiki.client().links().base())) {
			show(entry, cached);
			return;
		}
		page = null;
		layout = null;
		updateButtons();
		message = Component.translatable("ingamewiki.loading", entry.request.label());
		messageIsError = false;
		fetch(entry.request).whenComplete((loaded, error) -> minecraft.execute(() -> {
			if (gen != generation) {
				return;
			}
			if (error != null) {
				Throwable cause = WikiClient.unwrap(error);
				IngameWiki.LOGGER.warn("Loading {} failed", entry.request, cause);
				message = Component.translatable("ingamewiki.error", entry.request.label())
						.append("\n").append(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage())
						.append("\n").append(Component.translatable("ingamewiki.retry"));
				messageIsError = true;
				return;
			}
			synchronized (CACHE) {
				CACHE.put(entry.request, loaded);
			}
			show(entry, loaded);
		}));
	}

	private void show(Entry entry, Loaded loaded) {
		page = loaded;
		message = null;
		layout = null;
		relayout();
		if (entry.restoreScroll) {
			entry.restoreScroll = false;
			scroll = Mth.clamp(entry.scroll, 0, maxScroll());
		} else {
			scroll = 0;
			String fragment = entry.request instanceof WikiRequest.Page p && p.fragment() != null ? p.fragment() : loaded.fragment();
			if (fragment != null) {
				scrollTo(fragment);
			}
		}
		tocScroll = 0;
		if (search != null && !search.isFocused()) {
			search.setValue(loaded.displayTitle());
			search.moveCursorToStart(false);
		}
		updateButtons();
	}

	private CompletableFuture<Loaded> fetch(WikiRequest request) {
		WikiClient client = IngameWiki.client();
		WikiLinks links = client.links();
		return switch (request) {
			case WikiRequest.Page p -> client.page(p.title())
					.thenApply(result -> article(result, links))
					.exceptionallyCompose(error -> WikiClient.unwrap(error) instanceof WikiException.Missing
							? client.search(p.title(), 20).thenApply(results -> searchPage(results, p.title(), links))
							: CompletableFuture.failedFuture(error));
			case WikiRequest.Lookup l -> client.lookup(l.query())
					.thenApply(result -> article(result, links))
					.exceptionallyCompose(error -> WikiClient.unwrap(error) instanceof WikiException.Missing
							? client.search(l.query(), 20).thenApply(results -> searchPage(results, l.query(), links))
							: CompletableFuture.failedFuture(error));
			case WikiRequest.Go g -> client.page(g.query())
					.thenApply(result -> article(result, links))
					.exceptionallyCompose(error -> WikiClient.unwrap(error) instanceof WikiException.Missing
							? client.search(g.query(), 20).thenApply(results -> searchPage(results, g.query(), links))
							: CompletableFuture.failedFuture(error));
			case WikiRequest.Search s -> client.search(s.query(), 20).thenApply(results -> searchPage(results, null, links));
			case WikiRequest.Bookmarks b -> CompletableFuture.completedFuture(bookmarksPage(links));
		};
	}

	private static Loaded article(WikiPage result, WikiLinks links) {
		String html = "<h1>" + Entities.escape(result.displayTitle()) + "</h1>" + result.html();
		return new Loaded(result.title(), result.displayTitle(), parse(html, links), result.fragment(),
				links.page(result.title(), result.fragment()), links.base());
	}

	/**
	 * Builds a page listing search results.
	 *
	 * @param missing title of a page that was requested but does not exist
	 */
	private static Loaded searchPage(SearchResults results, @Nullable String missing, WikiLinks links) {
		StringBuilder html = new StringBuilder();
		String heading = Component.translatable("ingamewiki.searchResults", results.query()).getString();
		html.append("<h1>").append(Entities.escape(heading)).append("</h1>");
		if (missing != null) {
			html.append("<p><i>").append(Entities.escape(Component.translatable("ingamewiki.missing", missing).getString())).append("</i></p>");
		}
		if (results.suggestion() != null) {
			html.append("<p>").append(Entities.escape(Component.translatable("ingamewiki.didYouMean").getString()))
					.append(" <a href=\"search:").append(URLEncoder.encode(results.suggestion(), StandardCharsets.UTF_8)).append("\"><b>")
					.append(Entities.escape(results.suggestion())).append("</b></a></p>");
		}
		if (results.hits().isEmpty()) {
			html.append("<p>").append(Entities.escape(Component.translatable("ingamewiki.noResults", results.query()).getString())).append("</p>");
		} else {
			html.append("<ul>");
			for (SearchHit hit : results.hits()) {
				html.append("<li><a href=\"").append(Entities.escape(links.page(hit.title(), null).toString())).append("\"><b>")
						.append(Entities.escape(hit.title())).append("</b></a>");
				if (!hit.snippet().isBlank()) {
					html.append("<br><small>").append(hit.snippet()).append("</small>");
				}
				html.append("</li>");
			}
			html.append("</ul>");
		}
		return new Loaded(null, results.query(), parse(html.toString(), links), null,
				new WikiRequest.Search(results.query()).webUrl(links), links.base());
	}

	private static Loaded bookmarksPage(WikiLinks links) {
		StringBuilder html = new StringBuilder();
		String heading = Component.translatable("ingamewiki.bookmarks").getString();
		html.append("<h1>").append(Entities.escape(heading)).append("</h1>");
		List<String> titles = IngameWiki.data().bookmarks(links.base());
		if (titles.isEmpty()) {
			html.append("<p><i>").append(Entities.escape(Component.translatable("ingamewiki.bookmarks.empty").getString())).append("</i></p>");
		} else {
			String remove = Entities.escape(Component.translatable("ingamewiki.bookmarks.removeLink").getString());
			html.append("<ul>");
			for (String title : titles) {
				html.append("<li><a href=\"").append(Entities.escape(links.page(title, null).toString())).append("\"><b>")
						.append(Entities.escape(title)).append("</b></a> <small><a href=\"unbookmark:")
						.append(URLEncoder.encode(title, StandardCharsets.UTF_8)).append("\">").append(remove).append("</a></small></li>");
			}
			html.append("</ul>");
		}
		return new Loaded(null, heading, parse(html.toString(), links), null, new WikiRequest.Bookmarks().webUrl(links), links.base());
	}

	private static Document parse(String html, WikiLinks links) {
		return Jsoup.parseBodyFragment(html, links.base() + "/");
	}

	private void relayout() {
		if (page == null) {
			layout = null;
			return;
		}
		ModConfig config = IngameWiki.config();
		LayoutOptions options = new LayoutOptions(config.showImages, config.imageScale, IngameWiki.client().links());
		float newZoom = config.zoom;
		if (layout != null && zoom == newZoom && layout.width() == layoutWidth(newZoom) && options.equals(layoutOptions)) {
			return;
		}
		double ratio = layout == null || maxScroll() <= 0 ? 0 : scroll / maxScroll();
		long start = System.nanoTime();
		layout = HtmlLayout.layout(page.document().body(), font, layoutWidth(newZoom), options);
		if (layout.width() != layoutWidth(newZoom)) {
			// The table of contents appeared or disappeared, which changes the available width.
			layout = HtmlLayout.layout(page.document().body(), font, layoutWidth(newZoom), options);
		}
		layoutOptions = options;
		zoom = newZoom;
		IngameWiki.LOGGER.debug("Laid out {} in {} ms ({} pieces)", page.displayTitle(), (System.nanoTime() - start) / 1_000_000, layout.pieces().size());
		scroll = Mth.clamp(ratio * maxScroll(), 0, maxScroll());
	}

	private void scrollTo(String fragment) {
		if (layout == null) {
			return;
		}
		Integer y = layout.anchors().get(fragment);
		if (y == null) {
			y = layout.anchors().get(WikiLinks.normalizeFragment(fragment));
		}
		if (y != null) {
			scroll = Mth.clamp(y * zoom - 4, 0, maxScroll());
		}
	}

	/**
	 * Anchor of the section at the top of the view, if any.
	 */
	private @Nullable String currentSection() {
		if (layout == null || scroll <= 0) {
			return null;
		}
		String result = null;
		for (PageLayout.TocEntry entry : layout.toc()) {
			Integer y = layout.anchors().get(entry.anchor());
			if (y != null && y * zoom <= scroll + 8) {
				result = entry.anchor();
			}
		}
		return result;
	}

	private void follow(Link link, boolean external) {
		String href = link.href();
		if (href.startsWith("search:")) {
			navigate(new WikiRequest.Search(URLDecoder.decode(href.substring(7), StandardCharsets.UTF_8)));
			return;
		}
		if (href.startsWith("unbookmark:")) {
			WikiData data = IngameWiki.data();
			data.removeBookmark(IngameWiki.client().links().base(), URLDecoder.decode(href.substring(11), StandardCharsets.UTF_8));
			IngameWiki.saveData();
			reload();
			return;
		}
		WikiLinks links = IngameWiki.client().links();
		WikiLinks.Target target = links.parse(href);
		switch (target) {
			case null -> {
			}
			case WikiLinks.Target.Anchor a -> scrollTo(a.fragment());
			case WikiLinks.Target.Page p -> {
				if (external) {
					WikiOpener.openIn(OpenMode.BROWSER, new WikiRequest.Page(p.title(), p.fragment()), this);
				} else if (page != null && page.title() != null && page.title().equalsIgnoreCase(p.title()) && p.fragment() != null) {
					scrollTo(p.fragment());
				} else {
					navigate(new WikiRequest.Page(p.title(), p.fragment()));
				}
			}
			case WikiLinks.Target.External e -> WikiOpener.openExternal(this, e.uri());
		}
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = panelLeft();
		int right = panelRight();
		int top = panelTop();
		int bottom = panelBottom();
		graphics.fill(left, top, right, bottom, PANEL_COLOR);
		graphics.outline(left - 1, top - 1, right - left + 2, bottom - top + 2, 0xFF303030);

		if (layout == null) {
			hoveredLink = null;
			Component text = message != null ? message : Component.empty();
			List<FormattedCharSequence> lines = font.split(text, right - left - 2 * PAD);
			int y = (top + bottom) / 2 - lines.size() * 5;
			for (FormattedCharSequence line : lines) {
				graphics.centeredText(font, line, (left + right) / 2, y, messageIsError ? 0xFFFF7070 : 0xFFA0A0A0);
				y += 10;
			}
			return;
		}

		boolean inPanel = mouseX >= left && mouseX < right - SCROLLBAR && mouseY >= top && mouseY < bottom;
		hoveredLink = null;
		Piece hovered = inPanel ? pieceAt(mouseX, mouseY) : null;
		if (hovered instanceof Piece.Text t) {
			hoveredLink = t.link();
		} else if (hovered instanceof Piece.Image i) {
			hoveredLink = i.link();
		}

		graphics.enableScissor(left, top, right, bottom);
		drawPieces(graphics, top, bottom);
		graphics.disableScissor();
		if (hovered instanceof Piece.Image i && i.tooltip() != null && !i.tooltip().isEmpty()) {
			graphics.setTooltipForNextFrame(font, Component.literal(i.tooltip()), mouseX, mouseY);
		}
		drawScrollbar(graphics, right - SCROLLBAR, top, bottom);
		if (tocVisible()) {
			drawToc(graphics, mouseX, mouseY);
		}
		if (hoveredLink != null) {
			graphics.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND);
			String status = hoveredLink.title() != null && !hoveredLink.href().startsWith("http") ? hoveredLink.title() : hoveredLink.href();
			if (hoveredLink.missing()) {
				status += " (missing)";
			}
			status = font.plainSubstrByWidth(status, right - left - 8);
			graphics.fill(left, bottom - 11, left + font.width(status) + 6, bottom, 0xE0000000);
			graphics.text(font, status, left + 3, bottom - 10, 0xFFB0B0B0, false);
		}
	}

	/**
	 * Draws the visible pieces, scaled by {@link #zoom} around the page origin.
	 */
	private void drawPieces(GuiGraphicsExtractor graphics, int top, int bottom) {
		int ox = originX();
		int oy = originY();
		ImageCache images = IngameWiki.images();
		graphics.pose().pushMatrix();
		graphics.pose().translate(ox, oy);
		graphics.pose().scale(zoom, zoom);
		for (Piece piece : layout.pieces()) {
			if (piece.bottom() * zoom + oy < top || piece.top() * zoom + oy > bottom) {
				continue;
			}
			switch (piece) {
				case Piece.Fill f -> graphics.fill(f.x0(), f.y0(), f.x1(), f.y1(), f.color());
				case Piece.Outline o -> graphics.outline(o.x(), o.y(), o.width(), o.height(), o.color());
				case Piece.Text t -> {
					int x = t.x();
					int y = t.y();
					if (t.scale() == 1.0F) {
						graphics.text(font, t.text(), x, y, HtmlLayout.TEXT_COLOR, t.shadow());
					} else {
						graphics.pose().pushMatrix();
						graphics.pose().translate(x, y);
						graphics.pose().scale(t.scale(), t.scale());
						graphics.text(font, t.text(), 0, 0, HtmlLayout.TEXT_COLOR, t.shadow());
						graphics.pose().popMatrix();
					}
					if (t.link() != null && t.link() == hoveredLink) {
						int underline = y + (int) Math.ceil(8 * t.scale());
						graphics.fill(x, underline, x + t.width(), underline + 1,
								t.link().missing() ? HtmlLayout.MISSING_LINK_COLOR : HtmlLayout.LINK_COLOR);
					}
				}
				case Piece.Image i -> {
					ImageCache.Texture texture = images.get(i.src());
					if (texture != null) {
						graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(), i.x(), i.y(), 0.0F, 0.0F, i.width(), i.height(),
								texture.width(), texture.height(), texture.width(), texture.height());
					} else {
						graphics.fill(i.x(), i.y(), i.x() + i.width(), i.y() + i.height(), 0x18FFFFFF);
					}
				}
			}
		}
		graphics.pose().popMatrix();
	}

	private @Nullable Piece pieceAt(int mouseX, int mouseY) {
		if (layout == null) {
			return null;
		}
		int px = (int) Math.floor((mouseX - originX()) / zoom);
		int py = (int) Math.floor((mouseY - originY()) / zoom);
		Piece found = null;
		for (Piece piece : layout.pieces()) {
			switch (piece) {
				case Piece.Text t when t.link() != null && px >= t.x() && px < t.x() + t.width() && py >= t.top() && py < t.bottom() -> found = t;
				case Piece.Image i when px >= i.x() && px < i.x() + i.width() && py >= i.y() && py < i.bottom() -> found = i;
				default -> {
				}
			}
		}
		return found;
	}

	private void drawScrollbar(GuiGraphicsExtractor graphics, int x, int top, int bottom) {
		double max = maxScroll();
		if (max <= 0) {
			return;
		}
		int track = bottom - top;
		int thumb = Math.max(16, (int) ((long) track * track / (track + max)));
		int thumbY = top + (int) ((track - thumb) * (scroll / max));
		graphics.fill(x, top, x + SCROLLBAR, bottom, 0x40000000);
		graphics.fill(x, thumbY, x + SCROLLBAR, thumbY + thumb, draggingScrollbar ? 0xFFC0C0C0 : 0xFF808080);
		graphics.fill(x, thumbY, x + SCROLLBAR - 1, thumbY + thumb - 1, draggingScrollbar ? 0xFFE0E0E0 : 0xFFA8A8A8);
	}

	private void drawToc(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = MARGIN;
		int right = MARGIN + TOC_WIDTH;
		int top = panelTop();
		int bottom = panelBottom();
		graphics.fill(left, top, right, bottom, PANEL_COLOR);
		graphics.outline(left - 1, top - 1, TOC_WIDTH + 2, bottom - top + 2, 0xFF303030);
		graphics.text(font, Component.translatable("ingamewiki.contents").withStyle(s -> s.withBold(true)), left + 5, top + 5, 0xFFFFFFFF, false);
		String active = currentSection();
		graphics.enableScissor(left, top + 16, right, bottom);
		int y = top + 18 - (int) tocScroll;
		for (PageLayout.TocEntry entry : layout.toc()) {
			int indent = left + 5 + Math.max(0, entry.level() - 2) * 8;
			String text = font.plainSubstrByWidth(entry.text(), right - indent - 4);
			boolean hovered = mouseX >= left && mouseX < right && mouseY >= y - 1 && mouseY < y + 10 && mouseY >= top + 16;
			int color = entry.anchor().equals(active) ? 0xFFFFE080 : hovered ? 0xFFFFFFFF : entry.level() <= 2 ? 0xFFC8C8C8 : 0xFF989898;
			if (hovered) {
				graphics.fill(left + 1, y - 1, right - 1, y + 10, 0x20FFFFFF);
			}
			graphics.text(font, text, indent, y, color, false);
			y += 11;
		}
		graphics.disableScissor();
	}

	private int tocContentHeight() {
		return layout == null ? 0 : layout.toc().size() * 11 + 20;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (search != null && search.isFocused()) {
			setFocused(null);
		}
		int button = event.button();
		if (button == 3) {
			goBack();
			return true;
		}
		if (button == 4) {
			goForward();
			return true;
		}
		double mx = event.x();
		double my = event.y();
		int sx = panelRight() - SCROLLBAR;
		if (button == 0 && mx >= sx && mx < panelRight() && my >= panelTop() && my < panelBottom() && maxScroll() > 0) {
			draggingScrollbar = true;
			dragScrollbar(my);
			return true;
		}
		if (tocVisible() && mx >= MARGIN && mx < MARGIN + TOC_WIDTH && my >= panelTop() + 16 && my < panelBottom()) {
			int index = (int) ((my - (panelTop() + 17) + tocScroll) / 11);
			if (layout != null && index >= 0 && index < layout.toc().size()) {
				scrollTo(layout.toc().get(index).anchor());
			}
			return true;
		}
		if ((button == 0 || button == 2) && layout != null) {
			Piece piece = pieceAt((int) mx, (int) my);
			Link link = piece instanceof Piece.Text t ? t.link() : piece instanceof Piece.Image i ? i.link() : null;
			if (link != null) {
				follow(link, button == 2 || event.hasControlDown());
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingScrollbar) {
			dragScrollbar(event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	private void dragScrollbar(double mouseY) {
		double max = maxScroll();
		int track = panelBottom() - panelTop();
		int thumb = Math.max(16, (int) ((long) track * track / (track + max)));
		double ratio = (mouseY - panelTop() - thumb / 2.0) / Math.max(1, track - thumb);
		scroll = Mth.clamp(ratio * max, 0, max);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (minecraft.hasControlDown()) {
			if (vertical != 0) {
				stepZoom(vertical > 0 ? 1 : -1);
			}
			return true;
		}
		if (tocVisible() && mouseX < MARGIN + TOC_WIDTH) {
			tocScroll = Mth.clamp(tocScroll - vertical * SCROLL_STEP, 0, Math.max(0, tocContentHeight() - viewHeight()));
			return true;
		}
		scroll = Mth.clamp(scroll - vertical * SCROLL_STEP, 0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (search != null && search.isFocused()) {
			if (event.isConfirmation()) {
				String query = search.getValue().strip();
				if (!query.isEmpty()) {
					setFocused(null);
					navigate(new WikiRequest.Go(query));
				}
				return true;
			}
			if (event.isEscape()) {
				setFocused(null);
				return true;
			}
			return super.keyPressed(event);
		}
		int key = event.key();
		double page = viewHeight() - 20;
		switch (key) {
			case InputConstants.KEY_F5 -> reload();
			case InputConstants.KEY_BACKSPACE -> goBack();
			case InputConstants.KEY_PAGEUP -> scroll = Mth.clamp(scroll - page, 0, maxScroll());
			case InputConstants.KEY_PAGEDOWN, InputConstants.KEY_SPACE -> scroll = Mth.clamp(scroll + page, 0, maxScroll());
			case InputConstants.KEY_HOME -> scroll = 0;
			case InputConstants.KEY_END -> scroll = maxScroll();
			case InputConstants.KEY_UP -> scroll = Mth.clamp(scroll - SCROLL_STEP, 0, maxScroll());
			case InputConstants.KEY_DOWN -> scroll = Mth.clamp(scroll + SCROLL_STEP, 0, maxScroll());
			case InputConstants.KEY_LEFT -> {
				if (!event.hasAltDown()) {
					return super.keyPressed(event);
				}
				goBack();
			}
			case InputConstants.KEY_RIGHT -> {
				if (!event.hasAltDown()) {
					return super.keyPressed(event);
				}
				goForward();
			}
			case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD, InputConstants.KEY_MINUS, InputConstants.KEY_0, InputConstants.KEY_NUMPAD0 -> {
				if (!event.hasControlDown()) {
					return super.keyPressed(event);
				}
				if (key == InputConstants.KEY_0 || key == InputConstants.KEY_NUMPAD0) {
					setZoom(1.0F);
				} else {
					stepZoom(key == InputConstants.KEY_MINUS ? -1 : 1);
				}
			}
			case InputConstants.KEY_D, InputConstants.KEY_B -> {
				if (!event.hasControlDown()) {
					return super.keyPressed(event);
				}
				if (key == InputConstants.KEY_D) {
					toggleBookmark();
				} else {
					openBookmarks();
				}
			}
			case InputConstants.KEY_F, InputConstants.KEY_L -> {
				if (!event.hasControlDown() || search == null) {
					return super.keyPressed(event);
				}
				setFocused(search);
				search.moveCursorToEnd(false);
				search.moveCursorToStart(true);
			}
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	@Override
	public void removed() {
		// Also runs when a child screen such as the settings opens; that just records the same page early.
		WikiRequest.Page shown = page != null && page.title() != null ? new WikiRequest.Page(page.title(), currentSection()) : null;
		// A screen opened from an inventory would return to that inventory, so only reuse screens without a parent.
		WikiOpener.closed(parent == null ? this : null, shown);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
