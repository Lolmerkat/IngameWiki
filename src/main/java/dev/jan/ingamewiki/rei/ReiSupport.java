package dev.jan.ingamewiki.rei;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

/**
 * Entry point for the optional Roughly Enough Items integration. This class does not reference REI itself, so it can
 * be loaded when REI is not installed.
 */
public final class ReiSupport {
	public static final String MOD_ID = "roughlyenoughitems";

	private ReiSupport() {
	}

	public static boolean installed() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	/**
	 * Lets {@code key} look up the REI entry under the mouse (item list, favorites, recipe screens) if REI is
	 * installed.
	 */
	public static void register(KeyMapping key) {
		if (installed()) {
			ReiLookup.register(key);
		}
	}
}
