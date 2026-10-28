package dev.jan.ingamewiki.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Case-insensitive search for text on a laid out page. Adjacent text pieces of one line (a line is split wherever the
 * style or link changes) are searched together, so a match may span several pieces; matches do not continue across
 * line breaks.
 */
public final class FindInPage {
	private FindInPage() {
	}

	/**
	 * The characters {@code [from, to)} of one text piece.
	 */
	public record Segment(Piece.Text piece, int from, int to) {
	}

	/**
	 * One match, made of the segments of the pieces it covers.
	 */
	public record Hit(List<Segment> segments) {
		public int x() {
			return segments.getFirst().piece().x();
		}

		public int y() {
			return segments.getFirst().piece().y();
		}

		public int bottom() {
			return segments.getFirst().piece().bottom();
		}
	}

	/**
	 * Matches of {@code query} in reading order (top to bottom, then left to right).
	 */
	public static List<Hit> find(List<Piece> pieces, String query) {
		String needle = fold(query);
		List<Hit> hits = new ArrayList<>();
		if (needle.isBlank()) {
			return hits;
		}
		List<Piece.Text> line = new ArrayList<>();
		for (Piece piece : pieces) {
			if (!(piece instanceof Piece.Text t)) {
				continue;
			}
			if (!line.isEmpty()) {
				Piece.Text last = line.getLast();
				if (t.y() != last.y() || t.scale() != last.scale() || t.x() != last.x() + last.width()) {
					search(line, needle, hits);
					line.clear();
				}
			}
			line.add(t);
		}
		search(line, needle, hits);
		hits.sort(Comparator.comparingInt(Hit::y).thenComparingInt(Hit::x));
		return hits;
	}

	private static void search(List<Piece.Text> line, String needle, List<Hit> hits) {
		if (line.isEmpty()) {
			return;
		}
		StringBuilder text = new StringBuilder();
		int[] starts = new int[line.size() + 1];
		for (int i = 0; i < line.size(); i++) {
			starts[i] = text.length();
			text.append(fold(line.get(i).text().getString()));
		}
		starts[line.size()] = text.length();
		int at = text.indexOf(needle);
		while (at >= 0) {
			int end = at + needle.length();
			List<Segment> segments = new ArrayList<>(1);
			for (int i = 0; i < line.size(); i++) {
				int from = Math.max(at, starts[i]);
				int to = Math.min(end, starts[i + 1]);
				if (from < to) {
					segments.add(new Segment(line.get(i), from - starts[i], to - starts[i]));
				}
			}
			hits.add(new Hit(segments));
			at = text.indexOf(needle, end);
		}
	}

	/**
	 * Lower case without changing the length, so positions in the folded text are positions in the original.
	 */
	private static String fold(String text) {
		char[] chars = text.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			char c = chars[i];
			chars[i] = c == '\u00A0' || c == '\u202F' ? ' ' : Character.toLowerCase(c);
		}
		return new String(chars);
	}
}
