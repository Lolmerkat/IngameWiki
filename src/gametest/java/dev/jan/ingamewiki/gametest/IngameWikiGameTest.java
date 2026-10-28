package dev.jan.ingamewiki.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.jan.ingamewiki.IngameWiki;
import dev.jan.ingamewiki.ui.ConfigScreen;
import dev.jan.ingamewiki.ui.WikiScreen;
import dev.jan.qollib.screen.ContainerScreens;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.Slot;

/**
 * Changes a setting in the config screen and looks up an inventory item with the lookup key. Loading the page needs
 * internet access to minecraft.wiki; the screenshot shows whether text and images arrived.
 */
public final class IngameWikiGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.setScreen(() -> new ConfigScreen(null));
		context.takeScreenshot("ingamewiki-config");
		clickButton(context, "ingamewiki.config.confirmLinks");
		check(!context.computeOnClient(minecraft -> IngameWiki.config().confirmExternalLinks), "clicking the toggle changes the setting");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTick();
		check(readConfig().contains("\"confirmExternalLinks\": false"), "closing the screen saves the config");
		context.runOnClient(minecraft -> {
			IngameWiki.config().confirmExternalLinks = true;
			IngameWiki.saveConfig();
		});

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("give @p minecraft:oak_log");
			context.waitTicks(5);

			context.getInput().pressKey(options -> options.keyInventory);
			context.waitForScreen(InventoryScreen.class);
			double[] pos = context.computeOnClient(minecraft -> {
				AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) minecraft.gui.screen();
				Slot slot = screen.getMenu().slots.stream()
						.filter(s -> s.container == minecraft.player.getInventory() && s.getContainerSlot() == 0)
						.findFirst()
						.orElseThrow();
				int scale = minecraft.getWindow().getGuiScale();
				return new double[] {(ContainerScreens.left(screen) + slot.x + 8) * scale, (ContainerScreens.top(screen) + slot.y + 8) * scale};
			});
			context.getInput().setCursorPos(pos[0], pos[1]);
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_H);
			context.waitForScreen(WikiScreen.class);
			context.waitTicks(200);
			context.takeScreenshot("ingamewiki-lookup");
			context.setScreen(() -> null);
		}
	}

	/**
	 * Activates the button whose label is the translation {@code key} (with any arguments) by focusing it and pressing
	 * Enter, which runs the same action as a click.
	 */
	private static void clickButton(ClientGameTestContext context, String key) {
		context.runOnClient(minecraft -> {
			Screen screen = minecraft.gui.screen();
			Button button = screen.children().stream()
					.filter(child -> child instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
					.map(Button.class::cast)
					.findFirst()
					.orElseThrow(() -> new AssertionError("No button " + key));
			screen.setFocused(button);
		});
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTick();
	}

	private static String readConfig() {
		try {
			return Files.readString(FabricLoader.getInstance().getConfigDir().resolve("ingamewiki.json"));
		} catch (IOException e) {
			throw new AssertionError("Could not read the config", e);
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Expected: " + what);
		}
	}
}
