package dev.jan.ingamewiki.render;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jspecify.annotations.Nullable;

/**
 * Lays out MediaWiki HTML into {@link Piece}s: a small block/inline flow engine that understands paragraphs, headings,
 * lists, tables (with col- and rowspans), images, right-floating infoboxes and thumbnails, and the Minecraft Wiki's
 * inventory slots and crafting grids. Everything else falls back to plain text.
 *
 * <p>Not thread-safe; uses the game font, so it must run on the render thread.
 */
public final class HtmlLayout {
	public static final int TEXT_COLOR = 0xFFDDDDDD;
	public static final int LINK_COLOR = 0xFF7FB2F0;
	public static final int MISSING_LINK_COLOR = 0xFFE07070;
	private static final int HEADING_COLOR = 0xFFFFFFFF;
	private static final int DIM_COLOR = 0xFFA8A8A8;
	private static final int CODE_COLOR = 0xFFE6D9A0;
	private static final int RULE_COLOR = 0x50FFFFFF;
	private static final int BORDER_COLOR = 0xFF4A4A4A;
	private static final int HEADER_CELL = 0x26FFFFFF;
	private static final int ARROW_COLOR = 0xFF8B8B8B;

	private static final int LINE_GAP = 1;
	private static final int INDENT = 12;
	private static final int CELL_PAD = 3;
	private static final int FLOAT_GAP = 8;
	private static final int INFOBOX_WIDTH = 180;
	private static final int SLOT = 18;
	private static final int LARGE_SLOT = 26;

	private static final Set<String> SKIP_TAGS = Set.of("style", "script", "noscript", "link", "meta", "input", "button",
			"template", "select", "textarea", "map", "video", "audio", "source", "svg", "math", "object", "iframe", "head");
	private static final Set<String> SKIP_CLASSES = Set.of("navbox", "noprint", "mw-editsection", "hidden-alt-text",
			"mw-empty-elt", "toc", "printfooter", "catlinks", "load-page", "mw-collapsible-toggle", "mw-jump-link",
			"navbox-styles", "mw-cite-backlink", "navigation-not-searchable", "sprite-file-hidden");
	private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "section", "article", "main", "header", "footer",
			"nav", "aside", "blockquote", "figure", "figcaption", "center", "dl", "dt", "dd", "caption", "form",
			"fieldset", "details", "summary", "address", "li", "body", "html", "tr", "tbody", "thead", "tfoot");
	private static final Set<String> CENTER_CLASSES = Set.of("infobox-title", "mcwiki-header", "infobox-imagearea",
			"infobox-invimages", "center", "gallerytext");
	private static final Pattern STYLE_COLOR = Pattern.compile("(?:^|;)\\s*color\\s*:\\s*#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b");
	private static final Pattern STYLE_BACKGROUND = Pattern.compile("background(?:-color)?\\s*:\\s*#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b");
	private static final Pattern STYLE_HIDDEN = Pattern.compile("display\\s*:\\s*none");
	private static final Pattern STYLE_FLOAT_RIGHT = Pattern.compile("float\\s*:\\s*right");
	private static final Pattern STYLE_CENTER = Pattern.compile("text-align\\s*:\\s*center");

	private final Font font;
	private final LayoutOptions options;
	private final boolean measuring;
	private final int rootX;
	private final int rootWidth;
	private final boolean allowFloats;

	private final List<Piece> out = new ArrayList<>();
	private final Map<String, Integer> anchors = new HashMap<>();
	private final List<PageLayout.TocEntry> toc = new ArrayList<>();
	private final List<Atom> atoms = new ArrayList<>();

	private Ctx ctx;
	private int y;
	private int pendingMargin;
	private boolean started;
	private float flowScale = 1.0F;
	private int floatLeft = Integer.MAX_VALUE;
	private int floatBottom = Integer.MIN_VALUE;
	private int listDepth;
	/**
	 * Widest unbreakable item and rightmost line end, for table column sizing.
	 */
	private int maxAtom;
	private int maxRight;

	private HtmlLayout(Font font, LayoutOptions options, boolean measuring, int width, boolean allowFloats) {
		this.font = font;
		this.options = options;
		this.measuring = measuring;
		this.rootX = 0;
		this.rootWidth = width;
		this.allowFloats = allowFloats;
		this.ctx = new Ctx(0, width, false, false);
	}

	/**
	 * Lays out the children of {@code root} (normally the page's {@code <body>}).
	 */
	public static PageLayout layout(Element root, Font font, int width, LayoutOptions options) {
		HtmlLayout layout = new HtmlLayout(font, options, false, width, true);
		layout.walkChildren(root, Style.EMPTY, null);
		layout.flush();
		return layout.result();
	}

	private PageLayout result() {
		int height = Math.max(y, floatBottom == Integer.MIN_VALUE ? 0 : floatBottom);
		return new PageLayout(out, rootWidth, height, anchors, toc);
	}

	private HtmlLayout child(int width, boolean measure) {
		return new HtmlLayout(font, options, measure || measuring, width, false);
	}

	// ---------------------------------------------------------------- tree walk

	private void walkChildren(Element el, Style style, @Nullable Link link) {
		for (Node child : el.childNodes()) {
			walk(child, style, link);
		}
	}

	private void walk(Node node, Style style, @Nullable Link link) {
		if (node instanceof TextNode text) {
			addText(text.getWholeText(), style, link);
			return;
		}
		if (!(node instanceof Element el) || skip(el)) {
			return;
		}
		if (!el.id().isEmpty()) {
			anchors.putIfAbsent(el.id(), y);
		}
		if (isFloat(el)) {
			flush();
			floatBlock(el, style);
			return;
		}
		element(el, style, link);
	}

	private void element(Element el, Style style, @Nullable Link link) {
		String tag = el.normalName();
		switch (tag) {
			case "br" -> {
				atoms.add(Break.INSTANCE);
				return;
			}
			case "img" -> {
				image(el, link);
				return;
			}
			case "hr" -> {
				flush();
				margin(4);
				applyMargin();
				out.add(new Piece.Fill(ctx.x, y, ctx.x + ctx.width, y + 1, RULE_COLOR));
				y += 1;
				margin(4);
				return;
			}
			case "table" -> {
				table(el, style);
				return;
			}
			case "h1", "h2", "h3", "h4", "h5", "h6" -> {
				heading(el, tag.charAt(1) - '0', style);
				return;
			}
			case "ul", "ol" -> {
				if (el.hasClass("gallery")) {
					gallery(el, style);
				} else {
					list(el, tag.equals("ol"), style);
				}
				return;
			}
			case "pre" -> {
				pre(el, style);
				return;
			}
			case "a" -> {
				Link l = link(el);
				if (l != null) {
					link = l;
					style = style.withColor(l.missing() ? MISSING_LINK_COLOR : LINK_COLOR);
				}
				walkChildren(el, style, link);
				return;
			}
			default -> {
			}
		}
		if (el.hasClass("invslot")) {
			atoms.add(slot(el));
			return;
		}
		if (el.hasClass("mcui")) {
			Box box = mcui(el);
			if (box != null) {
				atoms.add(box);
				return;
			}
		}
		if (el.hasClass("animated")) {
			Element active = active(el.children());
			if (active != null) {
				walk(active, style, link);
			}
			return;
		}

		style = inlineStyle(el, tag, style);
		if (!BLOCK_TAGS.contains(tag)) {
			walkChildren(el, style, link);
			return;
		}

		// Generic block element.
		flush();
		int before = switch (tag) {
			case "p", "blockquote", "figure", "dl", "details" -> 5;
			default -> 0;
		};
		margin(before);
		Ctx saved = ctx;
		boolean center = ctx.center || hasAnyClass(el, CENTER_CLASSES) || tag.equals("center")
				|| STYLE_CENTER.matcher(el.attr("style")).find() || "center".equalsIgnoreCase(el.attr("align"));
		int indent = switch (tag) {
			case "dd", "blockquote" -> INDENT;
			default -> el.hasClass("hatnote") ? 8 : 0;
		};
		if (tag.equals("dt") || hasAnyClass(el, Set.of("infobox-title", "mcwiki-header"))) {
			style = style.withBold(true);
		}
		if (el.hasClass("hatnote")) {
			style = style.withItalic(true).withColor(DIM_COLOR);
		}
		if (tag.equals("figcaption")) {
			style = style.withColor(DIM_COLOR);
		}
		ctx = new Ctx(ctx.x + indent, Math.max(20, ctx.width - indent), center, ctx.pre);
		int top = y;
		int index = out.size();
		walkChildren(el, style, link);
		flush();
		if (tag.equals("blockquote") && y > top) {
			out.add(index, new Piece.Fill(saved.x + 2, top, saved.x + 4, y, RULE_COLOR));
		}
		ctx = saved;
		margin(before);
	}

	private boolean skip(Element el) {
		if (SKIP_TAGS.contains(el.normalName())) {
			return true;
		}
		for (String c : el.classNames()) {
			if (SKIP_CLASSES.contains(c)) {
				return true;
			}
		}
		String style = el.attr("style");
		return !style.isEmpty() && STYLE_HIDDEN.matcher(style).find();
	}

	private static boolean hasAnyClass(Element el, Set<String> classes) {
		for (String c : el.classNames()) {
			if (classes.contains(c)) {
				return true;
			}
		}
		return false;
	}

	private Style inlineStyle(Element el, String tag, Style style) {
		style = switch (tag) {
			case "b", "strong", "th" -> style.withBold(true);
			case "i", "em", "cite", "var", "dfn" -> style.withItalic(true);
			case "u", "ins" -> style.withUnderlined(true);
			case "s", "del", "strike" -> style.withStrikethrough(true);
			case "code", "kbd", "tt", "samp" -> style.withColor(CODE_COLOR);
			case "sup", "sub", "small" -> style.getColor() == null ? style.withColor(DIM_COLOR) : style;
			default -> style;
		};
		if (el.hasClass("searchmatch")) {
			style = style.withBold(true).withColor(0xFFFFE080);
		}
		String css = el.attr("style");
		if (!css.isEmpty()) {
			Matcher color = STYLE_COLOR.matcher(css);
			if (color.find() && !css.contains("color: transparent") && !css.contains("color:transparent")) {
				style = style.withColor(readable(parseHex(color.group(1))));
			}
			if (css.contains("font-weight:bold") || css.contains("font-weight: bold")) {
				style = style.withBold(true);
			}
			if (css.contains("font-style:italic") || css.contains("font-style: italic")) {
				style = style.withItalic(true);
			}
		}
		return style;
	}

	private static int parseHex(String hex) {
		if (hex.length() == 3) {
			hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
		}
		return Integer.parseInt(hex, 16);
	}

	/**
	 * Wiki colors are chosen for a white background; brighten dark ones so they stay readable on our dark panel.
	 */
	private static int readable(int rgb) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		int luma = (r * 299 + g * 587 + b * 114) / 1000;
		if (luma >= 110) {
			return rgb;
		}
		if (luma < 40 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 40) {
			return TEXT_COLOR & 0xFFFFFF;
		}
		r = r + (255 - r) / 2;
		g = g + (255 - g) / 2;
		b = b + (255 - b) / 2;
		return r << 16 | g << 8 | b;
	}

	private static @Nullable Link link(Element a) {
		String href = a.attr("href");
		if (href.isEmpty()) {
			return null;
		}
		String title = a.attr("title");
		return new Link(href, title.isEmpty() ? null : title, a.hasClass("new"));
	}

	private static @Nullable Element active(Elements children) {
		for (Element child : children) {
			if (child.hasClass("animated-active")) {
				return child;
			}
		}
		return children.isEmpty() ? null : children.getFirst();
	}

	// ---------------------------------------------------------------- inline flow

	private sealed interface Atom permits Word, Space, Box, Break {
	}

	private record Word(String text, Style style, @Nullable Link link, float scale, int width) implements Atom {
	}

	private record Space(Style style, @Nullable Link link, float scale, int width) implements Atom {
	}

	private record Box(int width, int height, List<Piece> pieces) implements Atom {
	}

	private enum Break implements Atom {
		INSTANCE
	}

	private record Ctx(int x, int width, boolean center, boolean pre) {
	}

	private void addText(String raw, Style style, @Nullable Link link) {
		if (raw.isEmpty()) {
			return;
		}
		raw = raw.replace("​", "").replace("­", "");
		if (ctx.pre) {
			String[] lines = raw.replace("\t", "    ").split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				if (i > 0) {
					atoms.add(Break.INSTANCE);
				}
				if (!lines[i].isEmpty()) {
					addWord(lines[i], style, link);
				}
			}
			return;
		}
		int i = 0;
		int n = raw.length();
		while (i < n) {
			char c = raw.charAt(i);
			if (Character.isWhitespace(c)) {
				while (i < n && Character.isWhitespace(raw.charAt(i))) {
					i++;
				}
				addSpace(style, link);
			} else {
				int start = i;
				while (i < n && !Character.isWhitespace(raw.charAt(i))) {
					i++;
				}
				addWord(raw.substring(start, i), style, link);
			}
		}
	}

	private void addWord(String text, Style style, @Nullable Link link) {
		text = text.replace(' ', ' ').replace(' ', ' ');
		atoms.add(new Word(text, style, link, flowScale, textWidth(text, style, flowScale)));
	}

	private void addSpace(Style style, @Nullable Link link) {
		if (atoms.isEmpty() || atoms.getLast() instanceof Space || atoms.getLast() instanceof Break) {
			return;
		}
		atoms.add(new Space(style, link, flowScale, textWidth(" ", style, flowScale)));
	}

	private int textWidth(String text, Style style, float scale) {
		int w = font.width(FormattedText.of(text, style));
		return scale == 1.0F ? w : (int) Math.ceil(w * scale);
	}

	private static int width(Atom atom) {
		return switch (atom) {
			case Word w -> w.width;
			case Space s -> s.width;
			case Box b -> b.width;
			case Break b -> 0;
		};
	}

	private void margin(int amount) {
		pendingMargin = Math.max(pendingMargin, amount);
	}

	private void applyMargin() {
		if (started) {
			y += pendingMargin;
		}
		pendingMargin = 0;
	}

	/**
	 * Width available to a line starting at {@code y}, taking a right float into account. Moves below the float if
	 * the remaining space is too narrow.
	 */
	private int available() {
		if (y < floatBottom && ctx.x < floatLeft) {
			int avail = Math.min(ctx.x + ctx.width, floatLeft - FLOAT_GAP) - ctx.x;
			if (avail >= 100) {
				return avail;
			}
			y = floatBottom;
		}
		return ctx.width;
	}

	private void clearFloat() {
		if (y < floatBottom && ctx.x < floatLeft) {
			y = floatBottom;
		}
	}

	/**
	 * Breaks the collected inline atoms into lines and emits them.
	 */
	private void flush() {
		if (atoms.isEmpty()) {
			return;
		}
		boolean visible = false;
		for (Atom a : atoms) {
			if (a instanceof Word || a instanceof Box) {
				visible = true;
				break;
			}
		}
		if (!visible) {
			atoms.clear();
			return;
		}
		applyMargin();
		boolean emitted = false;
		List<Atom> line = new ArrayList<>();
		int i = 0;
		while (i < atoms.size()) {
			line.clear();
			int lineWidth = 0;
			int avail = available();
			boolean explicitBreak = false;
			while (i < atoms.size()) {
				Atom a = atoms.get(i);
				if (a == Break.INSTANCE) {
					i++;
					explicitBreak = true;
					break;
				}
				if (a instanceof Space && line.isEmpty()) {
					i++;
					continue;
				}
				int w = width(a);
				if (w > maxAtom && !(a instanceof Space)) {
					maxAtom = w;
				}
				if (lineWidth + w > avail && !line.isEmpty()) {
					if (a instanceof Space) {
						i++;
					}
					break;
				}
				if (lineWidth + w > avail && a instanceof Word word && word.text.length() > 1) {
					int cut = fit(word, avail - lineWidth);
					Word head = new Word(word.text.substring(0, cut), word.style, word.link, word.scale,
							textWidth(word.text.substring(0, cut), word.style, word.scale));
					Word tail = new Word(word.text.substring(cut), word.style, word.link, word.scale,
							textWidth(word.text.substring(cut), word.style, word.scale));
					atoms.set(i, tail);
					line.add(head);
					lineWidth += head.width;
					break;
				}
				line.add(a);
				lineWidth += w;
				i++;
			}
			while (!line.isEmpty() && line.getLast() instanceof Space s) {
				line.removeLast();
				lineWidth -= s.width;
			}
			if (line.isEmpty()) {
				if (explicitBreak && emitted && i < atoms.size()) {
					y += 9 + LINE_GAP;
				}
				continue;
			}
			emitLine(line, lineWidth, avail);
			emitted = true;
		}
		atoms.clear();
	}

	/**
	 * Number of leading chars of {@code word} that fit into {@code width}; at least one.
	 */
	private int fit(Word word, int width) {
		int cut = 1;
		while (cut < word.text.length() && textWidth(word.text.substring(0, cut + 1), word.style, word.scale) <= width) {
			cut++;
		}
		if (Character.isHighSurrogate(word.text.charAt(cut - 1)) && cut < word.text.length()) {
			cut++;
		}
		return cut;
	}

	private void emitLine(List<Atom> line, int lineWidth, int avail) {
		float scale = 1.0F;
		int height = 0;
		for (Atom a : line) {
			if (a instanceof Word w) {
				scale = Math.max(scale, w.scale);
				height = Math.max(height, (int) Math.ceil(9 * w.scale));
			} else if (a instanceof Space s) {
				height = Math.max(height, (int) Math.ceil(9 * s.scale));
			} else if (a instanceof Box b) {
				height = Math.max(height, b.height);
			}
		}
		int textHeight = (int) Math.ceil(9 * scale);
		height = Math.max(height, textHeight);
		int x = ctx.x + (ctx.center ? Math.max(0, (avail - lineWidth) / 2) : 0);
		int textY = y + height - textHeight;

		StringBuilder run = new StringBuilder();
		Style runStyle = null;
		Link runLink = null;
		int runX = x;
		int runWidth = 0;
		float runScale = scale;
		for (Atom a : line) {
			String text;
			Style style;
			Link link;
			if (a instanceof Word w) {
				text = w.text;
				style = w.style;
				link = w.link;
			} else if (a instanceof Space s) {
				text = " ";
				style = s.style;
				link = s.link;
			} else {
				Box b = (Box) a;
				if (!run.isEmpty()) {
					emitText(run, runStyle, runLink, runX, textY, runWidth, runScale);
				}
				for (Piece p : b.pieces) {
					out.add(p.offset(x, y + height - b.height));
				}
				x += b.width;
				runX = x;
				runWidth = 0;
				continue;
			}
			if (!run.isEmpty() && (!style.equals(runStyle) || link != runLink)) {
				emitText(run, runStyle, runLink, runX, textY, runWidth, runScale);
				runX = x;
				runWidth = 0;
			}
			run.append(text);
			runStyle = style;
			runLink = link;
			int w = width(a);
			runWidth += w;
			x += w;
		}
		if (!run.isEmpty()) {
			emitText(run, runStyle, runLink, runX, textY, runWidth, runScale);
		}
		maxRight = Math.max(maxRight, ctx.x + lineWidth);
		y += height + LINE_GAP;
		started = true;
	}

	private void emitText(StringBuilder run, Style style, @Nullable Link link, int x, int y, int width, float scale) {
		if (!measuring) {
			out.add(new Piece.Text(x, y, Component.literal(run.toString()).setStyle(style), width, scale, link, false));
		}
		run.setLength(0);
	}

	// ---------------------------------------------------------------- images and boxes

	private void image(Element img, @Nullable Link link) {
		if (!options.images()) {
			return;
		}
		URI uri = imageUri(img);
		if (uri == null) {
			return;
		}
		int[] size = displaySize(img);
		int w = size[0];
		int h = size[1];
		if (w > ctx.width && ctx.width > 0) {
			h = Math.max(1, h * ctx.width / w);
			w = ctx.width;
		}
		String tooltip = link != null ? link.title() : null;
		atoms.add(new Box(w, h, List.of(new Piece.Image(0, 0, w, h, uri, link, tooltip))));
	}

	private @Nullable URI imageUri(Element img) {
		String src = img.attr("src");
		if (src.isEmpty() || src.startsWith("data:")) {
			src = img.attr("data-src");
		}
		if (src.isEmpty() || src.startsWith("data:")) {
			return null;
		}
		URI uri = options.links().resolve(src);
		if (uri == null || uri.getPath() == null) {
			return null;
		}
		String path = uri.getPath().toLowerCase(Locale.ROOT);
		// Formats we cannot decode: the wiki serves SVGs unrasterized and WebP is not supported by ImageIO.
		if (path.endsWith(".svg") || path.endsWith(".webp")) {
			return null;
		}
		return uri;
	}

	private int[] displaySize(Element img) {
		int cssW = intAttr(img, "width", -1);
		int cssH = intAttr(img, "height", -1);
		int fileW = intAttr(img, "data-file-width", -1);
		int fileH = intAttr(img, "data-file-height", -1);
		if (cssW <= 0 && cssH <= 0) {
			cssW = fileW > 0 ? fileW : 16;
			cssH = fileH > 0 ? fileH : 16;
		} else if (cssW <= 0) {
			cssW = fileW > 0 && fileH > 0 ? cssH * fileW / fileH : cssH;
		} else if (cssH <= 0) {
			cssH = fileW > 0 && fileH > 0 ? cssW * fileH / fileW : cssW;
		}
		float scale = options.imageScale();
		int w = Math.max(1, Math.round(cssW * scale));
		int h = Math.max(1, Math.round(cssH * scale));
		// Keep small icons at least as tall as the text next to them.
		int minH = Math.min(cssH, 9);
		if (h < minH) {
			w = Math.max(1, Math.round(cssW * (minH / (float) cssH)));
			h = minH;
		}
		return new int[] {w, h};
	}

	private static int intAttr(Element el, String name, int fallback) {
		String value = el.attr(name).strip();
		if (value.endsWith("px")) {
			value = value.substring(0, value.length() - 2);
		}
		try {
			return value.isEmpty() ? fallback : (int) Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * A Minecraft-style inventory slot ({@code span.invslot}) with the item's image and stack size.
	 */
	private Box slot(Element el) {
		int size = el.hasClass("invslot-large") ? LARGE_SLOT : SLOT;
		List<Piece> pieces = new ArrayList<>();
		pieces.add(new Piece.Fill(0, 0, size, size, 0xFF8B8B8B));
		pieces.add(new Piece.Fill(0, 0, size, 1, 0xFF373737));
		pieces.add(new Piece.Fill(0, 0, 1, size, 0xFF373737));
		pieces.add(new Piece.Fill(0, size - 1, size, size, 0xFFFFFFFF));
		pieces.add(new Piece.Fill(size - 1, 0, size, size, 0xFFFFFFFF));
		Element item = active(el.select(".invslot-item"));
		if (item != null) {
			Element a = item.selectFirst("a[href]");
			Link link = a == null ? null : link(a);
			String title = null;
			Element titled = item.selectFirst("[title]");
			if (titled != null) {
				title = titled.attr("title");
			} else if (link != null) {
				title = link.title();
			}
			Element img = item.selectFirst("img");
			URI uri = img == null || !options.images() ? null : imageUri(img);
			int inner = size - 2;
			if (uri != null) {
				pieces.add(new Piece.Image(1, 1, inner, inner, uri, link, title));
			} else if (title != null && !title.isEmpty() && !measuring) {
				// No image: show the first letters of the item name so the slot is not mistaken for an empty one.
				String abbreviation = title.substring(0, Math.min(2, title.length()));
				pieces.add(new Piece.Text(2, 5, Component.literal(abbreviation), font.width(abbreviation), 1.0F, link, true));
			}
			Element count = item.selectFirst(".invslot-stacksize");
			if (count != null && !count.text().isBlank() && !measuring) {
				String text = count.text().strip();
				int w = font.width(text);
				pieces.add(new Piece.Text(size - 1 - w, size - 9, Component.literal(text).withColor(0xFFFFFF), w, 1.0F, null, true));
			}
		}
		return new Box(size, size, pieces);
	}

	/**
	 * Crafting table, furnace and similar UIs: the input slots as a grid, an arrow and the output slot.
	 */
	private @Nullable Box mcui(Element el) {
		Element input = el.selectFirst(".mcui-input");
		Element output = el.selectFirst(".mcui-output");
		if (input == null && output == null) {
			return null;
		}
		List<List<Element>> rows = new ArrayList<>();
		if (input != null) {
			for (Element row : input.select(".mcui-row")) {
				List<Element> slots = new ArrayList<>();
				for (Element child : row.children()) {
					if (child.hasClass("invslot")) {
						slots.add(child);
					}
				}
				if (!slots.isEmpty()) {
					rows.add(slots);
				}
			}
			if (rows.isEmpty()) {
				for (Element slot : el.select(".invslot")) {
					if (output == null || !slot.parents().contains(output)) {
						rows.add(List.of(slot));
					}
				}
			}
		}
		List<Piece> pieces = new ArrayList<>();
		int gridW = 0;
		int gridH = rows.size() * SLOT;
		for (int r = 0; r < rows.size(); r++) {
			List<Element> row = rows.get(r);
			gridW = Math.max(gridW, row.size() * SLOT);
			for (int c = 0; c < row.size(); c++) {
				for (Piece p : slot(row.get(c)).pieces) {
					pieces.add(p.offset(c * SLOT, r * SLOT));
				}
			}
		}
		List<Box> outputs = new ArrayList<>();
		if (output != null) {
			for (Element slot : output.select(".invslot")) {
				outputs.add(slot(slot));
				break;
			}
		}
		int height = gridH;
		for (Box b : outputs) {
			height = Math.max(height, b.height);
		}
		int gridTop = (height - gridH) / 2;
		if (gridTop > 0) {
			pieces.replaceAll(p -> p.offset(0, gridTop));
		}
		int x = gridW;
		if (!outputs.isEmpty()) {
			x += 4;
			int mid = height / 2;
			pieces.add(new Piece.Fill(x, mid - 1, x + 9, mid + 1, ARROW_COLOR));
			for (int i = 0; i < 5; i++) {
				pieces.add(new Piece.Fill(x + 9 + i, mid - 5 + i, x + 10 + i, mid + 5 - i, ARROW_COLOR));
			}
			x += 18;
			for (Box b : outputs) {
				int top = (height - b.height) / 2;
				for (Piece p : b.pieces) {
					pieces.add(p.offset(x, top));
				}
				x += b.width;
			}
		}
		return new Box(Math.max(1, x), Math.max(1, height), pieces);
	}

	private void gallery(Element ul, Style style) {
		flush();
		margin(5);
		for (Element li : ul.children()) {
			if (skip(li)) {
				continue;
			}
			Element img = li.selectFirst("img");
			int boxW = 80;
			if (img != null) {
				boxW = Math.max(boxW, displaySize(img)[0] + 8);
			}
			boxW = Math.min(boxW, ctx.width);
			HtmlLayout sub = child(boxW, false);
			sub.ctx = new Ctx(0, boxW, true, false);
			sub.walkChildren(li, style, null);
			sub.flush();
			PageLayout laid = sub.result();
			atoms.add(new Box(boxW, Math.max(1, laid.height()), laid.pieces()));
			addSpace(style, null);
		}
		flush();
		margin(5);
	}

	// ---------------------------------------------------------------- blocks

	private void heading(Element el, int level, Style style) {
		flush();
		String anchor = el.id();
		if (anchor.isEmpty()) {
			Element headline = el.selectFirst(".mw-headline[id]");
			if (headline != null) {
				anchor = headline.id();
			}
		}
		margin(level <= 2 ? 12 : 8);
		applyMargin();
		if (!anchor.isEmpty()) {
			anchors.put(anchor, y);
		}
		if (level >= 2 && !measuring && !anchor.isEmpty()) {
			toc.add(new PageLayout.TocEntry(level, el.text(), anchor));
		}
		float scale = switch (level) {
			case 1 -> 2.0F;
			case 2 -> 1.5F;
			case 3 -> 1.25F;
			default -> 1.0F;
		};
		Style hs = style.withColor(level <= 4 ? HEADING_COLOR : DIM_COLOR).withBold(level >= 3);
		float previous = flowScale;
		flowScale = scale;
		walkChildren(el, hs, null);
		flush();
		flowScale = previous;
		if (level <= 2) {
			int w = available();
			out.add(new Piece.Fill(ctx.x, y + 1, ctx.x + w, y + 2, RULE_COLOR));
			y += 3;
		}
		started = true;
		margin(4);
	}

	private void list(Element el, boolean ordered, Style style) {
		flush();
		margin(listDepth == 0 ? 5 : 0);
		int number = intAttr(el, "start", 1);
		Ctx saved = ctx;
		ctx = new Ctx(ctx.x + INDENT, Math.max(20, ctx.width - INDENT), false, ctx.pre);
		listDepth++;
		for (Node node : el.childNodes()) {
			if (!(node instanceof Element li) || !li.normalName().equals("li")) {
				walk(node, style, null);
				continue;
			}
			if (skip(li)) {
				continue;
			}
			flush();
			applyMargin();
			int markerY = y;
			int index = out.size();
			if (!li.id().isEmpty()) {
				anchors.putIfAbsent(li.id(), y);
			}
			walkChildren(li, style, null);
			flush();
			if (y > markerY && !measuring) {
				if (ordered) {
					String label = number + ".";
					int w = font.width(label);
					out.add(index, new Piece.Text(ctx.x - 3 - w, markerY, Component.literal(label), w, 1.0F, null, false));
				} else {
					int size = listDepth > 1 ? 2 : 3;
					out.add(index, new Piece.Fill(ctx.x - 8, markerY + 3, ctx.x - 8 + size, markerY + 3 + size, TEXT_COLOR));
				}
			}
			number++;
		}
		listDepth--;
		ctx = saved;
		margin(listDepth == 0 ? 5 : 0);
	}

	private void pre(Element el, Style style) {
		flush();
		margin(5);
		applyMargin();
		clearFloat();
		int top = y;
		int index = out.size();
		Ctx saved = ctx;
		ctx = new Ctx(ctx.x + 4, Math.max(20, ctx.width - 8), false, true);
		y += 3;
		started = false;
		walkChildren(el, style.withColor(CODE_COLOR), null);
		flush();
		y += 2;
		started = true;
		out.add(index, new Piece.Fill(saved.x, top, saved.x + saved.width, y, 0x40000000));
		ctx = saved;
		margin(5);
	}

	private boolean isFloat(Element el) {
		if (el.hasClass("infobox") && !el.normalName().equals("table")) {
			return true;
		}
		String tag = el.normalName();
		if (tag.equals("figure")) {
			String type = el.attr("typeof");
			boolean thumb = type.contains("mw:File/Thumb") || type.contains("mw:File/Frame");
			return thumb && !el.hasClass("mw-halign-left") && !el.hasClass("mw-halign-center") && !el.hasClass("mw-halign-none");
		}
		if (el.hasClass("thumb") && el.hasClass("tright")) {
			return true;
		}
		if (el.hasClass("infobox")) {
			return true;
		}
		String css = el.attr("style");
		return !css.isEmpty() && STYLE_FLOAT_RIGHT.matcher(css).find() && (tag.equals("table") || tag.equals("div"));
	}

	private int floatWidth(Element el) {
		if (el.hasClass("infobox") || el.hasClass("notaninfobox")) {
			return INFOBOX_WIDTH;
		}
		Element img = el.selectFirst("img");
		if (img != null && (el.normalName().equals("figure") || el.hasClass("thumb"))) {
			return Math.max(80, displaySize(img)[0] + 8);
		}
		HtmlLayout measure = child(100_000, true);
		measure.element(el, Style.EMPTY, null);
		measure.flush();
		return Math.clamp(measure.maxRight + 8, 60, 220);
	}

	/**
	 * Infoboxes and thumbnails float right if there is enough room; otherwise they become narrow centered blocks.
	 */
	private void floatBlock(Element el, Style style) {
		int desired = floatWidth(el);
		boolean canFloat = allowFloats && !measuring && ctx.x == rootX && rootWidth >= desired * 2 + 60;
		int width = Math.min(desired, ctx.width);
		HtmlLayout sub = child(width, false);
		sub.ctx = new Ctx(0, width, el.normalName().equals("figure") || el.hasClass("thumb"), false);
		sub.element(el, style, null);
		sub.flush();
		PageLayout laid = sub.result();
		int height = laid.height() + 4;
		maxAtom = Math.max(maxAtom, width);
		if (measuring) {
			maxRight = Math.max(maxRight, ctx.x + width);
			y += height;
			return;
		}
		int x;
		int top;
		if (canFloat) {
			x = rootX + rootWidth - width;
			top = y < floatBottom ? floatBottom : y + (started ? Math.min(pendingMargin, 4) : 0);
			floatLeft = y < floatBottom ? Math.min(floatLeft, x) : x;
			floatBottom = top + height + 4;
		} else {
			margin(5);
			applyMargin();
			clearFloat();
			x = ctx.x + Math.max(0, (ctx.width - width) / 2);
			top = y;
			y += height;
			started = true;
			margin(5);
		}
		boolean boxed = el.hasClass("infobox") || el.hasClass("notaninfobox") || el.normalName().equals("figure") || el.hasClass("thumb");
		if (boxed) {
			out.add(new Piece.Fill(x, top, x + width, top + height, 0x50000000));
			out.add(new Piece.Outline(x, top, width, height, BORDER_COLOR));
		}
		for (Piece p : laid.pieces()) {
			out.add(p.offset(x, top + 2));
		}
		for (Map.Entry<String, Integer> e : laid.anchors().entrySet()) {
			anchors.putIfAbsent(e.getKey(), e.getValue() + top);
		}
	}

	// ---------------------------------------------------------------- tables

	private static final class Cell {
		final Element el;
		final int row;
		final int col;
		final int rowSpan;
		final int colSpan;
		final boolean header;
		int height;

		Cell(Element el, int row, int col, int rowSpan, int colSpan) {
			this.el = el;
			this.row = row;
			this.col = col;
			this.rowSpan = rowSpan;
			this.colSpan = colSpan;
			this.header = el.normalName().equals("th");
		}
	}

	private void table(Element table, Style style) {
		flush();
		margin(5);
		applyMargin();

		Elements rowElements = table.select("> tr, > thead > tr, > tbody > tr, > tfoot > tr");
		List<Cell> cells = new ArrayList<>();
		List<boolean[]> occupied = new ArrayList<>();
		int columns = 0;
		int rowCount = 0;
		for (Element tr : rowElements) {
			if (skip(tr)) {
				continue;
			}
			int r = rowCount++;
			int c = 0;
			for (Element td : tr.children()) {
				String tag = td.normalName();
				if (!tag.equals("td") && !tag.equals("th") || skip(td)) {
					continue;
				}
				while (isOccupied(occupied, r, c)) {
					c++;
				}
				int colSpan = Math.clamp(intAttr(td, "colspan", 1), 1, 30);
				int rowSpan = Math.clamp(intAttr(td, "rowspan", 1), 1, 500);
				Cell cell = new Cell(td, r, c, rowSpan, colSpan);
				cells.add(cell);
				for (int dr = 0; dr < rowSpan; dr++) {
					for (int dc = 0; dc < colSpan; dc++) {
						occupy(occupied, r + dr, c + dc);
					}
				}
				c += colSpan;
				columns = Math.max(columns, c);
			}
		}
		if (columns == 0 || cells.isEmpty()) {
			return;
		}
		// Row spans may not reach beyond the last row.
		List<Cell> clipped = new ArrayList<>(cells.size());
		for (Cell cell : cells) {
			int span = Math.min(cell.rowSpan, rowCount - cell.row);
			clipped.add(span == cell.rowSpan ? cell : new Cell(cell.el, cell.row, cell.col, span, cell.colSpan));
		}
		cells = clipped;

		// Column widths: a simplified version of the CSS automatic table layout.
		int[] colMin = new int[columns];
		int[] colMax = new int[columns];
		List<Cell> spanning = new ArrayList<>();
		for (Cell cell : cells) {
			if (cell.colSpan > 1) {
				spanning.add(cell);
				continue;
			}
			int[] m = measure(cell, style);
			colMin[cell.col] = Math.max(colMin[cell.col], m[0]);
			colMax[cell.col] = Math.max(colMax[cell.col], m[1]);
		}
		for (Cell cell : spanning) {
			int[] m = measure(cell, style);
			distribute(colMin, cell.col, cell.colSpan, m[0]);
			distribute(colMax, cell.col, cell.colSpan, m[1]);
		}
		int totalMin = 0;
		int totalMax = 0;
		for (int c = 0; c < columns; c++) {
			colMax[c] = Math.max(colMax[c], colMin[c]);
			totalMin += colMin[c];
			totalMax += colMax[c];
		}
		maxAtom = Math.max(maxAtom, totalMin);
		// Sit next to a float if the table can be squeezed into the remaining width, otherwise move below it.
		Ctx outer = ctx;
		if (y < floatBottom && ctx.x < floatLeft) {
			int beside = Math.min(ctx.x + ctx.width, floatLeft - FLOAT_GAP) - ctx.x;
			if (totalMin <= beside) {
				ctx = new Ctx(ctx.x, beside, ctx.center, ctx.pre);
			} else {
				y = floatBottom;
			}
		}
		Element caption = table.selectFirst("> caption");
		if (caption != null) {
			Ctx saved = ctx;
			ctx = new Ctx(ctx.x, Math.min(ctx.width, Math.max(totalMax, 60)), true, false);
			walkChildren(caption, style.withBold(true), null);
			flush();
			ctx = saved;
		}
		int avail = ctx.width;
		int[] widths = new int[columns];
		if (totalMax <= avail) {
			System.arraycopy(colMax, 0, widths, 0, columns);
		} else if (totalMin >= avail) {
			for (int c = 0; c < columns; c++) {
				widths[c] = Math.max(2 * CELL_PAD + 6, colMin[c] * avail / Math.max(1, totalMin));
			}
		} else {
			int extra = avail - totalMin;
			int range = totalMax - totalMin;
			for (int c = 0; c < columns; c++) {
				widths[c] = colMin[c] + (int) ((long) (colMax[c] - colMin[c]) * extra / Math.max(1, range));
			}
		}
		int[] colX = new int[columns + 1];
		colX[0] = ctx.x;
		for (int c = 0; c < columns; c++) {
			colX[c + 1] = colX[c] + widths[c];
		}
		maxRight = Math.max(maxRight, colX[columns]);

		// Lay out the cells row by row.
		int index = out.size();
		int[] rowTop = new int[rowCount + 1];
		rowTop[0] = y;
		int next = 0;
		boolean savedStarted = started;
		int savedFloatBottom = floatBottom;
		floatBottom = Integer.MIN_VALUE;
		for (int r = 0; r < rowCount; r++) {
			int rowHeight = 9 + 2 * CELL_PAD;
			while (next < cells.size() && cells.get(next).row == r) {
				Cell cell = cells.get(next++);
				int x0 = colX[cell.col];
				int x1 = colX[cell.col + cell.colSpan];
				boolean center = cell.header || STYLE_CENTER.matcher(cell.el.attr("style")).find()
						|| "center".equalsIgnoreCase(cell.el.attr("align")) || hasAnyClass(cell.el, CENTER_CLASSES);
				ctx = new Ctx(x0 + CELL_PAD, Math.max(4, x1 - x0 - 2 * CELL_PAD), center, false);
				y = rowTop[r] + CELL_PAD;
				started = false;
				pendingMargin = 0;
				walkChildren(cell.el, cell.header ? style.withBold(true) : style, null);
				flush();
				pendingMargin = 0;
				cell.height = y - rowTop[r] + CELL_PAD;
				if (cell.rowSpan == 1) {
					rowHeight = Math.max(rowHeight, cell.height);
				}
			}
			for (Cell cell : cells) {
				if (cell.rowSpan > 1 && cell.row + cell.rowSpan - 1 == r) {
					rowHeight = Math.max(rowHeight, cell.height - (rowTop[r] - rowTop[cell.row]));
				}
			}
			rowTop[r + 1] = rowTop[r] + rowHeight;
		}
		ctx = outer;
		started = savedStarted;
		floatBottom = savedFloatBottom;

		if (!measuring) {
			boolean borders = table.hasClass("wikitable") || table.hasClass("infobox-rows") || table.hasClass("sortable")
					|| table.hasClass("stikitable") || !table.attr("border").isEmpty() && !table.attr("border").equals("0");
			List<Piece> backgrounds = new ArrayList<>();
			for (Cell cell : cells) {
				int x0 = colX[cell.col];
				int x1 = colX[cell.col + cell.colSpan];
				int y0 = rowTop[cell.row];
				int y1 = rowTop[cell.row + cell.rowSpan];
				Matcher bg = STYLE_BACKGROUND.matcher(cell.el.attr("style"));
				if (bg.find()) {
					backgrounds.add(new Piece.Fill(x0, y0, x1, y1, 0x50000000 | parseHex(bg.group(1))));
				} else if (cell.header && borders) {
					backgrounds.add(new Piece.Fill(x0, y0, x1, y1, HEADER_CELL));
				}
				if (borders) {
					backgrounds.add(new Piece.Outline(x0, y0, x1 - x0 + 1, y1 - y0 + 1, BORDER_COLOR));
				}
			}
			out.addAll(index, backgrounds);
		}
		y = rowTop[rowCount] + 1;
		started = true;
		margin(5);
	}

	/**
	 * Minimum and preferred width of a cell, including padding.
	 */
	private int[] measure(Cell cell, Style style) {
		HtmlLayout m = child(100_000, true);
		m.walkChildren(cell.el, cell.header ? style.withBold(true) : style, null);
		m.flush();
		int min = m.maxAtom + 2 * CELL_PAD + 1;
		int max = Math.max(m.maxRight, m.maxAtom) + 2 * CELL_PAD + 1;
		return new int[] {Math.min(min, 260), max};
	}

	private static void distribute(int[] widths, int start, int span, int needed) {
		int have = 0;
		for (int c = start; c < start + span; c++) {
			have += widths[c];
		}
		if (have >= needed) {
			return;
		}
		int extra = needed - have;
		for (int c = start; c < start + span; c++) {
			widths[c] += extra / span + (c - start < extra % span ? 1 : 0);
		}
	}

	private static boolean isOccupied(List<boolean[]> occupied, int r, int c) {
		return r < occupied.size() && c < occupied.get(r).length && occupied.get(r)[c];
	}

	private static void occupy(List<boolean[]> occupied, int r, int c) {
		while (occupied.size() <= r) {
			occupied.add(new boolean[8]);
		}
		boolean[] row = occupied.get(r);
		if (c >= row.length) {
			row = java.util.Arrays.copyOf(row, Math.max(c + 1, row.length * 2));
			occupied.set(r, row);
		}
		row[c] = true;
	}
}
