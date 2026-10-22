package dev.jan.ingamewiki;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jan.ingamewiki.config.ModConfig;
import dev.jan.ingamewiki.config.OpenMode;
import dev.jan.ingamewiki.config.WikiData;
import dev.jan.ingamewiki.render.ImageCache;
import dev.jan.ingamewiki.wiki.WikiClient;
import dev.jan.ingamewiki.wiki.WikiLinks;
import dev.jan.ingamewiki.wiki.WikiRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: key bindings, commands and the session-wide wiki client.
 */
public final class IngameWiki implements ClientModInitializer {
	public static final String MOD_ID = "ingamewiki";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModConfig config;
	private static WikiData data;
	private static WikiClient client;
	private static ImageCache images;
	private static KeyMapping lookupKey;
	private static KeyMapping openKey;
	private static KeyMapping modeKey;
	private static final List<Runnable> nextTick = new ArrayList<>();

	@Override
	public void onInitializeClient() {
		config = ModConfig.load(configFile());
		data = WikiData.load(dataFile());
		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
		client = new WikiClient("IngameWiki/" + version + " (Fabric mod; Minecraft " + SharedConstants.getCurrentVersion().name() + ")",
				new WikiLinks(config.wikiUrl));
		images = new ImageCache(client);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		lookupKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ingamewiki.lookup", InputConstants.KEY_H, category));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ingamewiki.open", InputConstants.UNKNOWN.getValue(), category));
		modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ingamewiki.cycleMode", InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.END_CLIENT_TICK.register(IngameWiki::tick);
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof AbstractContainerScreen<?>) {
				ScreenKeyboardEvents.afterKeyPress(screen).register(IngameWiki::onContainerKey);
			}
		});
		ClientCommandRegistrationCallback.EVENT.register(WikiCommands::register);
	}

	private static void tick(Minecraft minecraft) {
		if (!nextTick.isEmpty()) {
			List<Runnable> tasks = new ArrayList<>(nextTick);
			nextTick.clear();
			tasks.forEach(Runnable::run);
		}
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
			OpenMode mode = config.mode.next();
			setMode(mode);
			WikiOpener.notify(Component.translatable("ingamewiki.mode.changed", mode.label()));
		}
	}

	private static void onContainerKey(Screen screen, KeyEvent event) {
		if (!lookupKey.matches(event)) {
			return;
		}
		String item = WikiLookup.hoveredItem(screen);
		if (item != null) {
			WikiOpener.open(new WikiRequest.Lookup(item), screen);
		}
	}

	static void runNextTick(Runnable task) {
		nextTick.add(task);
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
	}

	public static Path dataFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + "-data.json");
	}

	public static ModConfig config() {
		return config;
	}

	public static WikiData data() {
		return data;
	}

	public static void saveData() {
		data.save(dataFile());
	}

	public static WikiClient client() {
		return client;
	}

	public static ImageCache images() {
		return images;
	}

	public static void setMode(OpenMode mode) {
		config.mode = mode;
		config.save(configFile());
	}

	/**
	 * Saves the config and applies changes to the wiki URL.
	 */
	public static void saveConfig() {
		config.sanitize();
		config.save(configFile());
		if (!client.links().base().equals(config.wikiUrl)) {
			client.setLinks(new WikiLinks(config.wikiUrl));
			images.clear();
		}
	}
}
