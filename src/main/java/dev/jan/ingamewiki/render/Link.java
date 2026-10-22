package dev.jan.ingamewiki.render;

import org.jspecify.annotations.Nullable;

/**
 * A hyperlink on a laid out page. All pieces of one {@code <a>} element share the same instance, so hovering one
 * highlights all of them.
 *
 * @param href    the raw {@code href} attribute
 * @param title   the {@code title} attribute (usually the target page)
 * @param missing whether the link points to a page that does not exist ("red link")
 */
public record Link(String href, @Nullable String title, boolean missing) {
	@Override
	public boolean equals(Object o) {
		return this == o;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}
}
