package dev.jan.ingamewiki;

import com.mojang.blaze3d.Blaze3D;
import dev.jan.ingamewiki.config.OpenMode;
import dev.jan.ingamewiki.config.WikiData;
import dev.jan.ingamewiki.mcef.McefSupport;
import dev.jan.ingamewiki.ui.WikiScreen;
import dev.jan.ingamewiki.wiki.WikiRequest;
import java.net.URI;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Opens wiki pages according to the configured {@link OpenMode}.
 */
public final class WikiOpener {
	/**
	 * The in-game screen the wiki was last closed from, kept so reopening it restores history and scroll position.
	 */
	private static @Nullable WikiScreen lastScreen;

	private WikiOpener() {
	}

	public static WikiRequest home() {
		return new WikiRequest.Page(IngameWiki.config().homePage, null);
	}

	/**
	 * The page the wiki was last closed on, or the main page.
	 */
	public static WikiRequest lastPage() {
		WikiData.LastPage last = IngameWiki.data().lastPage;
		if (last != null && last.wiki().equals(IngameWiki.client().links().base())) {
			return new WikiRequest.Page(last.title(), last.fragment());
		}
		return home();
	}

	/**
	 * Opens the wiki where it was last closed.
	 */
	public static void reopen() {
		if (IngameWiki.config().mode == OpenMode.INGAME && lastScreen != null) {
			Minecraft.getInstance().gui.setScreen(lastScreen);
		} else {
			open(lastPage(), null);
		}
	}

	/**
	 * Remembers where the wiki was closed.
	 *
	 * @param screen the in-game screen to show again, or {@code null} to open {@code page} in a new one
	 * @param page   the page that was shown, or {@code null} if it was not a wiki article
	 */
	public static void closed(@Nullable WikiScreen screen, WikiRequest.@Nullable Page page) {
		lastScreen = screen;
		if (page != null) {
			WikiData.LastPage last = new WikiData.LastPage(IngameWiki.client().links().base(), page.title(), page.fragment());
			if (!last.equals(IngameWiki.data().lastPage)) {
				IngameWiki.data().lastPage = last;
				IngameWiki.saveData();
			}
		}
	}

	/**
	 * Opens {@code request} the way the player configured.
	 *
	 * @param parent screen to return to when the wiki is closed
	 */
	public static void open(WikiRequest request, @Nullable Screen parent) {
		openIn(IngameWiki.config().mode, request, parent);
	}

	public static void openIn(OpenMode mode, WikiRequest request, @Nullable Screen parent) {
		Minecraft minecraft = Minecraft.getInstance();
		switch (mode) {
			case INGAME -> minecraft.gui.setScreen(new WikiScreen(parent, request));
			case BROWSER -> {
				URI uri = request.webUrl(IngameWiki.client().links());
				Blaze3D.openUri(uri);
				notify(Component.translatable("ingamewiki.opened.browser", request.label()));
			}
			case MCEF -> {
				if (McefSupport.installed()) {
					McefSupport.open(parent, request.webUrl(IngameWiki.client().links()));
				} else {
					notify(Component.translatable("ingamewiki.mcef.missing").withStyle(ChatFormatting.YELLOW));
					minecraft.gui.setScreen(new WikiScreen(parent, request));
				}
			}
		}
	}

	/**
	 * Opens a link to another site in the system browser, asking first if configured.
	 */
	public static void openExternal(Screen current, URI uri) {
		if (IngameWiki.config().confirmExternalLinks) {
			ConfirmLinkScreen.confirmLinkNow(current, uri);
		} else {
			Blaze3D.openUri(uri);
		}
	}

	public static void notify(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(message);
		}
	}
}
