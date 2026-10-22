package dev.jan.ingamewiki.ui;

import dev.jan.ingamewiki.IngameWiki;
import dev.jan.ingamewiki.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Settings screen, reachable from the wiki screen and from Mod Menu.
 */
public final class ConfigScreen extends Screen {
	private static final float[] IMAGE_SCALES = {0.25F, 0.5F, 0.75F, 1.0F, 1.5F};

	private final @Nullable Screen parent;
	private @Nullable EditBox wikiUrl;
	private @Nullable EditBox homePage;

	public ConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("ingamewiki.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig config = IngameWiki.config();
		int x = width / 2 - 155;
		int y = Math.max(30, height / 2 - 80);
		addRenderableWidget(Button.builder(modeLabel(), b -> {
					config.mode = config.mode.next();
					b.setMessage(modeLabel());
				})
				.bounds(x, y, 150, 20)
				.tooltip(Tooltip.create(Component.translatable("ingamewiki.mode.tooltip")))
				.build());
		addRenderableWidget(Button.builder(onOff("ingamewiki.config.confirmLinks", config.confirmExternalLinks), b -> {
					config.confirmExternalLinks = !config.confirmExternalLinks;
					b.setMessage(onOff("ingamewiki.config.confirmLinks", config.confirmExternalLinks));
				})
				.bounds(x + 160, y, 150, 20)
				.build());
		y += 24;
		addRenderableWidget(Button.builder(onOff("ingamewiki.config.showImages", config.showImages), b -> {
					config.showImages = !config.showImages;
					b.setMessage(onOff("ingamewiki.config.showImages", config.showImages));
				})
				.bounds(x, y, 150, 20)
				.build());
		addRenderableWidget(Button.builder(scaleLabel(), b -> {
					config.imageScale = nextScale(config.imageScale);
					b.setMessage(scaleLabel());
				})
				.bounds(x + 160, y, 150, 20)
				.tooltip(Tooltip.create(Component.translatable("ingamewiki.config.imageScale.tooltip")))
				.build());
		y += 36;
		wikiUrl = addRenderableWidget(new EditBox(font, x, y, 310, 18, wikiUrl, Component.translatable("ingamewiki.config.wikiUrl")));
		wikiUrl.setMaxLength(256);
		wikiUrl.setValue(config.wikiUrl);
		wikiUrl.setTooltip(Tooltip.create(Component.translatable("ingamewiki.config.wikiUrl.tooltip")));
		y += 34;
		homePage = addRenderableWidget(new EditBox(font, x, y, 310, 18, homePage, Component.translatable("ingamewiki.config.homePage")));
		homePage.setMaxLength(256);
		homePage.setValue(config.homePage);
		y += 30;
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, y, 200, 20).build());
	}

	private static Component modeLabel() {
		return Component.translatable("ingamewiki.mode.button", IngameWiki.config().mode.label());
	}

	private static Component onOff(String key, boolean value) {
		return Component.translatable(key, value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private static Component scaleLabel() {
		return Component.translatable("ingamewiki.config.imageScale", Math.round(IngameWiki.config().imageScale * 100));
	}

	private static float nextScale(float current) {
		for (float scale : IMAGE_SCALES) {
			if (scale > current + 0.01F) {
				return scale;
			}
		}
		return IMAGE_SCALES[0];
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, Math.max(10, height / 2 - 105), 0xFFFFFFFF);
		if (wikiUrl != null) {
			graphics.text(font, Component.translatable("ingamewiki.config.wikiUrl"), wikiUrl.getX(), wikiUrl.getY() - 11, 0xFFA0A0A0, false);
		}
		if (homePage != null) {
			graphics.text(font, Component.translatable("ingamewiki.config.homePage"), homePage.getX(), homePage.getY() - 11, 0xFFA0A0A0, false);
		}
	}

	@Override
	public void onClose() {
		ModConfig config = IngameWiki.config();
		if (wikiUrl != null) {
			config.wikiUrl = wikiUrl.getValue();
		}
		if (homePage != null) {
			config.homePage = homePage.getValue();
		}
		IngameWiki.saveConfig();
		minecraft.gui.setScreen(parent);
	}
}
