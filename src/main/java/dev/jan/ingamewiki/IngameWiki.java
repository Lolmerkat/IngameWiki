package dev.jan.ingamewiki;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jan.ingamewiki.config.ModConfig;
import dev.jan.ingamewiki.config.OpenMode;
import dev.jan.ingamewiki.config.WikiData;
import dev.jan.ingamewiki.wiki.WikiClient;
import dev.jan.ingamewiki.wiki.WikiLinks;
import dev.jan.ingamewiki.wiki.WikiRequest;
import dev.jan.qollib.ClientMessages;
import dev.jan.qollib.ModContext;
import dev.jan.qollib.config.ConfigFile;
import dev.jan.qollib.screen.ContainerScreens;
import dev.jan.qollib.texture.RemoteTextureCache;
import java.net.URI;
import java.time.Duration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Client entrypoint: key bindings, commands and the session-wide wiki client.
 */
public final class IngameWiki implements ClientModInitializer {
	public static final String MOD_ID = "ingamewiki";
	public static final ModContext MOD = ModContext.of(MOD_ID);
	public static final Logger LOGGER = MOD.logger();

	private static final ConfigFile<ModConfig> CONFIG = ConfigFile.of(MOD.configFile(".json"), ModConfig.class, ModConfig::new);
	private static final ConfigFile<WikiData> DATA = ConfigFile.of(MOD.configFile("-data.json"), WikiData.class, WikiData::new);
	private static WikiClient client;
	private static RemoteTextureCache<URI> images;
	private static KeyMapping lookupKey;
	private static KeyMapping openKey;
	private static KeyMapping modeKey;

	@Override
	public void onInitializeClient() {
		ModConfig config = CONFIG.load();
		DATA.load();
		client = new WikiClient(MOD.userAgent("IngameWiki"), new WikiLinks(config.wikiUrl));
		// Wiki pages can show any number of images, so ones that have not been drawn for a minute are released once
		// more than 256 are loaded.
		images = new RemoteTextureCache<>(MOD_ID, "wiki_image/", client::bytes, 256, Duration.ofMinutes(1));

		lookupKey = MOD.registerKey("lookup", InputConstants.KEY_H);
		openKey = MOD.registerKey("open", InputConstants.UNKNOWN.getValue());
		modeKey = MOD.registerKey("cycleMode", InputConstants.UNKNOWN.getValue());

		ClientTickEvents.END_CLIENT_TICK.register(IngameWiki::tick);
		ContainerScreens.onSlotKey(lookupKey, (screen, slot) ->
				WikiOpener.open(new WikiRequest.Lookup(WikiLookup.itemName(slot.getItem())), screen));
		ClientCommandRegistrationCallback.EVENT.register(WikiCommands::register);
	}

	private static void tick(Minecraft minecraft) {
		while (lookupKey.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				String target = WikiLookup.crosshairTarget(minecraft);
				WikiOpener.open(target == null ? WikiOpener.home() : new WikiRequest.Lookup(target), null);
			}
		}
		while (openKey.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				WikiOpener.reopen();
			}
		}
		while (modeKey.consumeClick()) {
			OpenMode mode = config().mode.next();
			setMode(mode);
			ClientMessages.overlay(Component.translatable("ingamewiki.mode.changed", mode.label()));
		}
	}

	public static ModConfig config() {
		return CONFIG.get();
	}

	public static WikiData data() {
		return DATA.get();
	}

	public static void saveData() {
		DATA.save();
	}

	public static WikiClient client() {
		return client;
	}

	public static RemoteTextureCache<URI> images() {
		return images;
	}

	public static void setMode(OpenMode mode) {
		config().mode = mode;
		CONFIG.save();
	}

	/**
	 * Saves the config and applies changes to the wiki URL.
	 */
	public static void saveConfig() {
		CONFIG.save();
		String wikiUrl = config().wikiUrl;
		if (!client.links().base().equals(wikiUrl)) {
			client.setLinks(new WikiLinks(wikiUrl));
			images.clear();
		}
	}
}
