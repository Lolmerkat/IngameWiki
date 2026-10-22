package dev.jan.ingamewiki;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import dev.jan.ingamewiki.mixin.AbstractContainerScreenAccessor;
import dev.jan.ingamewiki.wiki.WikiLinks;
import org.jspecify.annotations.Nullable;

/**
 * Finds the wiki page name for what the player is pointing at. Names are taken from the English translations, since
 * the Minecraft Wiki uses those as page titles regardless of the game language.
 */
public final class WikiLookup {
	private static @Nullable Language english;

	private WikiLookup() {
	}

	/**
	 * The item under the mouse in an inventory screen, or {@code null}.
	 */
	public static @Nullable String hoveredItem(Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?>) || screen.getFocused() instanceof EditBox) {
			return null;
		}
		Slot slot = ((AbstractContainerScreenAccessor) screen).ingamewiki$getHoveredSlot();
		return slot != null && slot.hasItem() ? itemName(slot.getItem()) : null;
	}

	/**
	 * The block or entity under the crosshair, or {@code null}.
	 */
	public static @Nullable String crosshairTarget(Minecraft minecraft) {
		HitResult hit = minecraft.hitResult;
		if (hit == null || hit.getType() == HitResult.Type.MISS || minecraft.level == null) {
			return null;
		}
		if (hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			if (entity instanceof ItemEntity item) {
				return itemName(item.getItem());
			}
			if (entity instanceof Player) {
				return "Player";
			}
			return english(entity.getType().getDescriptionId());
		}
		if (hit instanceof BlockHitResult blockHit) {
			return english(minecraft.level.getBlockState(blockHit.getBlockPos()).getBlock().getDescriptionId());
		}
		return null;
	}

	public static String itemName(ItemStack stack) {
		return english(stack.getItem().getDescriptionId());
	}

	/**
	 * English text for a translation key such as {@code block.minecraft.oak_log}; falls back to the title-cased path.
	 */
	static String english(String key) {
		Language language = english();
		if (language.has(key)) {
			return language.getOrDefault(key, key);
		}
		String[] parts = key.split("\\.");
		return WikiLinks.titleCase(parts[parts.length - 1]);
	}

	private static Language english() {
		if (english == null) {
			english = ClientLanguage.loadFrom(Minecraft.getInstance().getResourceManager(), List.of("en_us"), false);
		}
		return english;
	}

	/**
	 * Forgets the loaded translations, e.g. after resource packs changed.
	 */
	public static void reset() {
		english = null;
	}
}
