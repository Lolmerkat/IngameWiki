package dev.jan.ingamewiki.rei;

import dev.jan.ingamewiki.WikiLookup;
import dev.jan.ingamewiki.WikiOpener;
import dev.jan.ingamewiki.wiki.WikiRequest;
import dev.jan.qollib.screen.ContainerScreens;
import me.shedaniel.math.Point;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.gui.widgets.TextField;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Looks up the REI entry under the mouse. Only loaded through {@link ReiSupport} when REI is installed.
 */
final class ReiLookup {
	/**
	 * Id of REI's fluid entry type, compared by id since its value class comes from Architectury.
	 */
	private static final Identifier FLUID = Identifier.withDefaultNamespace("fluid");

	private ReiLookup() {
	}

	static void register(KeyMapping key) {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) ->
				ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
					if (key.matches(event)) {
						lookup(minecraft, screen);
					}
				}));
	}

	private static void lookup(Minecraft minecraft, Screen screen) {
		// Item slots of container screens are looked up by IngameWiki's own slot key handler.
		Slot slot = ContainerScreens.hoveredSlot(screen);
		if (slot != null && slot.hasItem() || ContainerScreens.isTyping(screen) || typingInSearch()) {
			return;
		}
		Point mouse = new Point(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
				minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
		EntryStack<?> entry = ScreenRegistry.getInstance().getFocusedStack(screen, mouse);
		String name = entry == null ? null : name(entry);
		if (name != null) {
			WikiOpener.open(new WikiRequest.Lookup(name), screen);
		}
	}

	private static boolean typingInSearch() {
		TextField search = REIRuntime.getInstance().getSearchTextField();
		return search != null && search.isFocused();
	}

	/**
	 * The wiki page name for an item or fluid entry, or {@code null} for other entry types.
	 */
	private static @Nullable String name(EntryStack<?> entry) {
		if (entry.isEmpty()) {
			return null;
		}
		if (entry.getType() == VanillaEntryTypes.ITEM) {
			return WikiLookup.itemName(entry.<ItemStack>castValue());
		}
		if (entry.getType().getId().equals(FLUID)) {
			return WikiLookup.fluidName(entry.getIdentifier());
		}
		return null;
	}
}
