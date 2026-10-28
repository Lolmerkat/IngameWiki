package dev.jan.ingamewiki.render;

import dev.jan.ingamewiki.wiki.WikiLinks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Makes plain mentions of linked terms clickable. Wiki articles only link the first mention of a term, so later
 * mentions ("leatherworker villagers...") are plain text. This wraps them in links to the same target as the explicit
 * link, matched case-insensitively by the target page title or the section a link names, including simple plurals.
 * <p>
 * The generated {@code <a>} elements carry {@link #CLASS} so they can be drawn in the normal text color.
 */
public final class ImplicitLinks {
	public static final String CLASS = "ingamewiki-implicit";

	private static final int MAX_WORDS = 5;
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
	private static final Set<String> NO_LINK_TAGS = Set.of("a", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "code", "kbd",
			"tt", "samp", "button", "script", "style");
	private static final Set<String> NAMESPACES = Set.of("file", "image", "category", "template", "help", "user", "talk",
			"module", "mediawiki", "special", "minecraft wiki", "media");

	private ImplicitLinks() {
	}

	private record Target(String href, @Nullable String title) {
	}

	private record Match(int start, int end, Target target) {
	}

	/**
	 * @param self title of the page itself; mentions of it are not linked
	 */
	public static void apply(Element root, WikiLinks links, @Nullable String self) {
		Map<String, Target> terms = terms(root, links, self);
		if (terms.isEmpty()) {
			return;
		}
		List<TextNode> texts = new ArrayList<>();
		NodeTraversor.traverse(new NodeVisitor() {
			@Override
			public void head(Node node, int depth) {
				if (node instanceof TextNode text && !text.isBlank() && linkable(text)) {
					texts.add(text);
				}
			}
		}, root);
		for (TextNode text : texts) {
			link(text, terms);
		}
	}

	/**
	 * Collects the terms to link from the explicit links on the page. Link texts naming a section take precedence over
	 * page titles, and both over generated plural/singular forms; otherwise the first link in document order wins.
	 */
	static Map<String, Target> terms(Element root, WikiLinks links, @Nullable String self) {
		Map<String, Target> byText = new HashMap<>();
		Map<String, Target> byTitle = new HashMap<>();
		for (Element a : root.select("a[href]")) {
			if (a.hasClass("new") || a.hasClass("mw-file-description") || a.hasClass("external")) {
				continue;
			}
			if (!(links.parse(a.attr("href")) instanceof WikiLinks.Target.Page page)
					|| (self != null && page.title().equalsIgnoreCase(self)) || isNamespaced(page.title())) {
				continue;
			}
			String title = a.attr("title").isEmpty() ? null : a.attr("title");
			String text = normalize(a.text());
			String titleKey = normalize(page.title());
			// Link texts are often abbreviated ("iron" for Iron Sword in a table), so they only count if they name the
			// target page or section, like "sweep attack" for Melee attack#Sweep_attack.
			String sectionKey = page.fragment() == null ? null : normalize(page.fragment().replace('_', ' '));
			boolean names = text.equals(titleKey) || variants(titleKey).contains(text)
					|| sectionKey != null && (text.equals(sectionKey) || variants(sectionKey).contains(text));
			if (names && usable(text)) {
				byText.putIfAbsent(text, new Target(a.attr("href"), title));
			}
			if (usable(titleKey)) {
				// The link may point at a section; the title alone means the whole page.
				byTitle.putIfAbsent(titleKey, new Target(links.page(page.title(), null).toString(), page.title()));
			}
		}
		Map<String, Target> terms = new HashMap<>(byText);
		byTitle.forEach(terms::putIfAbsent);
		if (self != null) {
			terms.remove(normalize(self));
		}
		Map<String, Target> variants = new HashMap<>();
		terms.forEach((key, target) -> {
			for (String variant : variants(key)) {
				variants.putIfAbsent(variant, target);
			}
		});
		variants.forEach(terms::putIfAbsent);
		if (self != null) {
			for (String variant : variants(normalize(self))) {
				terms.remove(variant);
			}
		}
		return terms;
	}

	private static boolean linkable(TextNode text) {
		for (Node n = text.parent(); n != null; n = n.parent()) {
			if (n instanceof Element el && (NO_LINK_TAGS.contains(el.normalName()) || el.hasClass("mw-headline"))) {
				return false;
			}
		}
		return true;
	}

	private static void link(TextNode node, Map<String, Target> terms) {
		String text = node.getWholeText();
		List<int[]> words = new ArrayList<>();
		Matcher m = WORD.matcher(text);
		while (m.find()) {
			words.add(new int[] {m.start(), m.end()});
		}
		List<Match> matches = new ArrayList<>();
		int i = 0;
		while (i < words.size()) {
			Match found = null;
			int last = Math.min(words.size(), i + MAX_WORDS) - 1;
			// Only words separated by whitespace form a phrase.
			for (int j = i + 1; j <= last; j++) {
				if (!text.substring(words.get(j - 1)[1], words.get(j)[0]).isBlank()) {
					last = j - 1;
					break;
				}
			}
			for (int j = last; j >= i && found == null; j--) {
				int start = words.get(i)[0];
				int end = words.get(j)[1];
				Target target = terms.get(normalize(text.substring(start, end)));
				if (target != null) {
					found = new Match(start, end, target);
					i = j + 1;
				}
			}
			if (found == null) {
				i++;
			} else {
				matches.add(found);
			}
		}
		if (matches.isEmpty()) {
			return;
		}
		int pos = 0;
		for (Match match : matches) {
			if (match.start > pos) {
				node.before(new TextNode(text.substring(pos, match.start)));
			}
			Element a = new Element("a").attr("href", match.target.href).addClass(CLASS);
			if (match.target.title != null) {
				a.attr("title", match.target.title);
			}
			a.appendChild(new TextNode(text.substring(match.start, match.end)));
			node.before(a);
			pos = match.end;
		}
		if (pos < text.length()) {
			node.before(new TextNode(text.substring(pos)));
		}
		node.remove();
	}

	static String normalize(String text) {
		return text.replace(' ', ' ').replace('’', '\'').strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static boolean usable(String key) {
		if (key.length() < 3 || key.split(" ").length > MAX_WORDS) {
			return false;
		}
		// Only terms made of whole words, so they can be found by word matching.
		Matcher m = WORD.matcher(key);
		StringBuilder rebuilt = new StringBuilder();
		boolean letter = false;
		while (m.find()) {
			if (!rebuilt.isEmpty()) {
				rebuilt.append(' ');
			}
			rebuilt.append(m.group());
			letter |= m.group().chars().anyMatch(Character::isLetter);
		}
		return letter && rebuilt.toString().equals(key);
	}

	private static boolean isNamespaced(String title) {
		int colon = title.indexOf(':');
		return colon > 0 && NAMESPACES.contains(title.substring(0, colon).strip().toLowerCase(Locale.ROOT));
	}

	/**
	 * Simple English plural and singular forms of the last word.
	 */
	private static List<String> variants(String key) {
		List<String> result = new ArrayList<>(2);
		if (key.endsWith("ies") && key.length() > 4) {
			result.add(key.substring(0, key.length() - 3) + "y");
		} else if (key.endsWith("es") && (key.endsWith("ches") || key.endsWith("shes") || key.endsWith("xes") || key.endsWith("sses"))) {
			result.add(key.substring(0, key.length() - 2));
		} else if (key.endsWith("s") && !key.endsWith("ss") && key.length() > 3) {
			result.add(key.substring(0, key.length() - 1));
		}
		if (key.endsWith("y") && key.length() > 2 && "aeiou".indexOf(key.charAt(key.length() - 2)) < 0) {
			result.add(key.substring(0, key.length() - 1) + "ies");
		} else if (key.endsWith("s") || key.endsWith("x") || key.endsWith("ch") || key.endsWith("sh")) {
			result.add(key + "es");
		} else {
			result.add(key + "s");
		}
		return result;
	}
}
