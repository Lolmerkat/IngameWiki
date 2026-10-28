package dev.jan.ingamewiki.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.jan.ingamewiki.wiki.WikiLinks;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class ImplicitLinksTest {
	private final WikiLinks links = new WikiLinks("https://minecraft.wiki");

	private Element apply(String html, String self) {
		Element body = Jsoup.parseBodyFragment(html, "https://minecraft.wiki/").body();
		ImplicitLinks.apply(body, links, self);
		return body;
	}

	private static String implicit(Element body) {
		StringBuilder sb = new StringBuilder();
		for (Element a : body.select("a." + ImplicitLinks.CLASS)) {
			sb.append(a.text()).append(" -> ").append(a.attr("href")).append('\n');
		}
		return sb.toString();
	}

	@Test
	void linksLaterMentionsOfLinkedTerms() {
		Element body = apply("""
				<p><a href="/w/Leatherworker" title="Leatherworker">leatherworker</a> villagers throw leather.</p>
				<p>Leatherworker villagers now buy leather. Two leatherworkers.</p>""", "Leather");
		assertEquals("""
				Leatherworker -> /w/Leatherworker
				leatherworkers -> /w/Leatherworker
				""", implicit(body));
		assertEquals("Leatherworker villagers now buy leather. Two leatherworkers.", body.select("p").get(1).text());
	}

	@Test
	void linkTextKeepsSectionAndTitleLinksWholePage() {
		Element body = apply("""
				<p>A <a href="/w/Melee_attack#Sweep_attack" title="Melee attack">sweep attack</a>.</p>
				<p>The sweep attack is a melee attack. Sweep attacks hit nearby mobs.</p>""", "Sword");
		assertEquals("""
				sweep attack -> /w/Melee_attack#Sweep_attack
				melee attack -> https://minecraft.wiki/w/Melee_attack
				Sweep attacks -> /w/Melee_attack#Sweep_attack
				""", implicit(body));
	}

	@Test
	void prefersLongestPhrase() {
		Element body = apply("""
				<p><a href="/w/Iron">iron</a> and <a href="/w/Iron_Golem">iron golem</a></p>
				<p>An iron golem drops iron.</p>""", "Mob");
		assertEquals("""
				iron golem -> /w/Iron_Golem
				iron -> /w/Iron
				""", implicit(body));
	}

	@Test
	void ignoresAbbreviatedLinkTexts() {
		Element body = apply("""
				<table><tr><td><a href="/w/Iron_Sword">Iron</a></td><td><a href="/w/Creeper_Head">Creeper</a></td></tr></table>
				<p>Iron swords deal more damage than a creeper. An iron sword.</p>""", "Sword");
		assertEquals("""
				Iron swords -> https://minecraft.wiki/w/Iron_Sword
				iron sword -> https://minecraft.wiki/w/Iron_Sword
				""", implicit(body));
	}

	@Test
	void skipsSelfHeadingsCodeRedLinksAndFiles() {
		Element body = apply("""
				<p><a href="/w/Leather">leather</a> <a href="/w/Fish" class="new">fish</a>
				<a href="/w/File:Cow.png" class="mw-file-description">cow</a> <a href="/w/File:Pig.png">pig</a>
				<a href="/w/Wool">wool</a></p>
				<h2>Wool and leather</h2><p><code>wool</code> fish cow pig leather</p>""", "Leather");
		assertEquals("", implicit(body));
	}

	@Test
	void doesNotMatchInsideWords() {
		Element body = apply("<p><a href=\"/w/Bow\">bow</a></p><p>A bowl and a crossbow.</p>", "Item");
		assertEquals("", implicit(body));
	}
}
