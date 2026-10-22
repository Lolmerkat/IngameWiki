package dev.jan.ingamewiki.render;

import java.util.List;
import java.util.Map;

/**
 * A page laid out for a given width.
 *
 * @param anchors y position of every element id, used for {@code #fragment} links
 * @param toc     section headings in document order
 */
public record PageLayout(List<Piece> pieces, int width, int height, Map<String, Integer> anchors, List<TocEntry> toc) {
	public record TocEntry(int level, String text, String anchor) {
	}
}
