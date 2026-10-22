package dev.jan.ingamewiki.config;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * Where wiki pages are shown.
 */
public enum OpenMode {
	/**
	 * Rendered by this mod in a Minecraft screen.
	 */
	INGAME,
	/**
	 * The system web browser.
	 */
	BROWSER,
	/**
	 * An embedded Chromium provided by MCEF Modern.
	 */
	MCEF;

	public OpenMode next() {
		return values()[(ordinal() + 1) % values().length];
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public Component label() {
		return Component.translatable("ingamewiki.mode." + id());
	}

	public static Optional<OpenMode> byId(String id) {
		for (OpenMode mode : values()) {
			if (mode.id().equalsIgnoreCase(id)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
