package dev.jan.ingamewiki;

import dev.jan.ingamewiki.ui.ConfigScreen;
import dev.jan.qollib.compat.ModMenuEntrypoint;

/**
 * Opens the settings from the mod's config button in Mod Menu.
 */
public final class ModMenuIntegration extends ModMenuEntrypoint {
	public ModMenuIntegration() {
		super(ConfigScreen::new);
	}
}
