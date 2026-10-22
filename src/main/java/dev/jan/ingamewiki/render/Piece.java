package dev.jan.ingamewiki.render;

import java.net.URI;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Something drawn at a fixed position of a laid out page. Coordinates are relative to the top left corner of the page.
 */
public sealed interface Piece {
	int top();

	int bottom();

	Piece offset(int dx, int dy);

	/**
	 * A run of text in one style, drawn with the font scaled by {@code scale}.
	 *
	 * @param width scaled width in GUI pixels
	 */
	record Text(int x, int y, Component text, int width, float scale, @Nullable Link link, boolean shadow) implements Piece {
		@Override
		public int top() {
			return y;
		}

		@Override
		public int bottom() {
			return y + (int) Math.ceil(9 * scale);
		}

		@Override
		public Piece offset(int dx, int dy) {
			return new Text(x + dx, y + dy, text, width, scale, link, shadow);
		}
	}

	/**
	 * An image loaded from {@code src}, stretched to the given size.
	 */
	record Image(int x, int y, int width, int height, URI src, @Nullable Link link, @Nullable String tooltip) implements Piece {
		@Override
		public int top() {
			return y;
		}

		@Override
		public int bottom() {
			return y + height;
		}

		@Override
		public Piece offset(int dx, int dy) {
			return new Image(x + dx, y + dy, width, height, src, link, tooltip);
		}
	}

	record Fill(int x0, int y0, int x1, int y1, int color) implements Piece {
		@Override
		public int top() {
			return y0;
		}

		@Override
		public int bottom() {
			return y1;
		}

		@Override
		public Piece offset(int dx, int dy) {
			return new Fill(x0 + dx, y0 + dy, x1 + dx, y1 + dy, color);
		}
	}

	record Outline(int x, int y, int width, int height, int color) implements Piece {
		@Override
		public int top() {
			return y;
		}

		@Override
		public int bottom() {
			return y + height;
		}

		@Override
		public Piece offset(int dx, int dy) {
			return new Outline(x + dx, y + dy, width, height, color);
		}
	}
}
