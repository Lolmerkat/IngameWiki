package dev.jan.ingamewiki.mcef;

import java.net.URI;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the MCEF-dependent screen class out of {@link McefSupport}'s signature, so it is only loaded on demand.
 */
final class McefScreens {
	private McefScreens() {
	}

	static Screen create(@Nullable Screen parent, URI uri) {
		return new McefBrowserScreen(parent, uri);
	}
}
