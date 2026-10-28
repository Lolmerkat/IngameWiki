package dev.jan.ingamewiki.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class FindInPageTest {
	private static Piece.Text text(int x, int y, String text) {
		return new Piece.Text(x, y, Component.literal(text), text.length() * 6, 1.0F, null, false);
	}

	private static List<String> describe(List<FindInPage.Hit> hits) {
		List<String> result = new ArrayList<>();
		for (FindInPage.Hit hit : hits) {
			StringBuilder sb = new StringBuilder();
			for (FindInPage.Segment s : hit.segments()) {
				sb.append(s.piece().text().getString(), s.from(), s.to()).append('|');
			}
			result.add(sb.toString());
		}
		return result;
	}

	@Test
	void caseInsensitiveWithinAPiece() {
		List<Piece> pieces = List.of(text(0, 0, "Leather and leather armor"));
		assertEquals(List.of("Leather|", "leather|"), describe(FindInPage.find(pieces, "LEATHER")));
	}

	@Test
	void matchesAcrossAdjacentPiecesOfALine() {
		Piece.Text a = text(0, 0, "Adult ");
		Piece.Text b = text(a.width(), 0, "piglins");
		Piece.Text c = text(a.width() + b.width(), 0, " may barter");
		assertEquals(List.of("lt |pig|"), describe(FindInPage.find(List.of(a, b, c), "lt pig")));
	}

	@Test
	void doesNotJoinSeparateLinesOrGaps() {
		Piece.Text a = text(0, 0, "iron");
		Piece.Text b = text(0, 10, " golem");
		Piece.Text c = text(100, 0, " golem");
		assertEquals(List.of(), FindInPage.find(List.of(a, b, c), "iron golem"));
	}

	@Test
	void sortsByPosition() {
		Piece.Text lower = text(0, 50, "cow");
		Piece.Text upper = text(200, 0, "cow");
		List<FindInPage.Hit> hits = FindInPage.find(List.of(lower, upper), "cow");
		assertEquals(List.of(0, 50), hits.stream().map(FindInPage.Hit::y).toList());
	}

	@Test
	void blankQueryFindsNothing() {
		assertEquals(List.of(), FindInPage.find(List.of(text(0, 0, "a b")), " "));
	}
}
