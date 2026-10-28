package dev.jan.ingamewiki.ui;

import dev.jan.ingamewiki.IngameWiki;
import dev.jan.ingamewiki.config.ModConfig;
import dev.jan.ingamewiki.config.OpenMode;
import dev.jan.qollib.screen.OptionGrid;
import dev.jan.qollib.screen.SettingsScreen;
import java.util.List;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Settings screen, reachable from the wiki screen and from Mod Menu.
 */
public final class ConfigScreen extends SettingsScreen {
	private static final List<Float> IMAGE_SCALES = List.of(0.25F, 0.5F, 0.75F, 1.0F, 1.5F);

	private @Nullable EditBox wikiUrl;
	private @Nullable EditBox homePage;

	public ConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("ingamewiki.config.title"), parent);
	}

	@Override
	protected void init() {
		ModConfig config = IngameWiki.config();
		OptionGrid grid = grid(Math.max(30, height / 2 - 80));
		int x = grid.x();
		grid.cycle("ingamewiki.mode.button", List.of(OpenMode.values()), () -> config.mode, value -> config.mode = value, OpenMode::label)
				.setTooltip(Tooltip.create(Component.translatable("ingamewiki.mode.tooltip")));
		grid.toggle("ingamewiki.config.confirmLinks", () -> config.confirmExternalLinks, value -> config.confirmExternalLinks = value);
		grid.toggle("ingamewiki.config.showImages", () -> config.showImages, value -> config.showImages = value);
		grid.cycle("ingamewiki.config.imageScale", IMAGE_SCALES, () -> config.imageScale, value -> config.imageScale = value,
				scale -> Math.round(scale * 100));
		grid.space(12);
		int y = grid.y();
		wikiUrl = addLabeledField(Component.translatable("ingamewiki.config.wikiUrl"), x, y, OptionGrid.WIDTH, config.wikiUrl, 256);
		wikiUrl.setTooltip(Tooltip.create(Component.translatable("ingamewiki.config.wikiUrl.tooltip")));
		y += 34;
		homePage = addLabeledField(Component.translatable("ingamewiki.config.homePage"), x, y, OptionGrid.WIDTH, config.homePage, 256);
		y += 30;
		addDoneButton(width / 2 - 100, y, 200);
	}

	@Override
	protected int titleY() {
		return Math.max(10, height / 2 - 105);
	}

	@Override
	protected void save() {
		ModConfig config = IngameWiki.config();
		if (wikiUrl != null) {
			config.wikiUrl = wikiUrl.getValue();
		}
		if (homePage != null) {
			config.homePage = homePage.getValue();
		}
		IngameWiki.saveConfig();
	}
}
