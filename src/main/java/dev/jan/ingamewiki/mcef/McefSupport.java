package dev.jan.ingamewiki.mcef;

import java.net.URI;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Entry point for the optional MCEF Modern integration. This class does not reference MCEF itself, so it can be
 * loaded when MCEF is not installed.
 */
public final class McefSupport {
	public static final String MOD_ID = "mcef-modern";

	private McefSupport() {
	}

	public static boolean installed() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	/**
	 * Opens {@code uri} in an embedded browser screen. Only call this if {@link #installed()}.
	 */
	public static void open(@Nullable Screen parent, URI uri) {
		Minecraft.getInstance().gui.setScreen(McefScreens.create(parent, uri));
	}
}
