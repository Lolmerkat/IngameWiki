package dev.jan.ingamewiki.mcef;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.jan.ingamewiki.IngameWiki;
import dev.jan.ingamewiki.WikiOpener;
import dev.jan.ingamewiki.ui.ConfigScreen;
import dev.jan.ingamewiki.ui.WikiScreen;
import dev.jan.ingamewiki.wiki.WikiLinks;
import dev.jan.ingamewiki.wiki.WikiRequest;
import java.net.URI;
import java.util.Locale;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.cef.browser.CefBrowser;
import org.jspecify.annotations.Nullable;

/**
 * Shows the wiki website in an embedded Chromium provided by MCEF Modern.
 */
final class McefBrowserScreen extends Screen {
	private static final int BAR_HEIGHT = 26;

	private final @Nullable Screen parent;
	private final URI initialUrl;
	private final MCEFApi.Initialization initialization;
	private @Nullable MCEFBrowser browser;
	private @Nullable Component error;
	private @Nullable Button backButton;
	private @Nullable Button forwardButton;
	private @Nullable Button modeButton;
	private boolean keepBrowser;
	private int browserWidth;
	private int browserHeight;

	McefBrowserScreen(@Nullable Screen parent, URI url) {
		super(Component.translatable("ingamewiki.title"));
		this.parent = parent;
		this.initialUrl = url;
		this.initialization = MCEFApi.initialize();
	}

	@Override
	protected void init() {
		int x = 4;
		backButton = addRenderableWidget(iconButton("←", "ingamewiki.back", x, b -> withCef(CefBrowser::goBack)));
		forwardButton = addRenderableWidget(iconButton("→", "ingamewiki.forward", x += 22, b -> withCef(CefBrowser::goForward)));
		addRenderableWidget(iconButton("⌂", "ingamewiki.home", x += 22,
				b -> withCef(cef -> cef.loadURL(new WikiRequest.Page(IngameWiki.config().homePage, null).webUrl(links()).toString()))));
		addRenderableWidget(iconButton("⟳", "ingamewiki.reload", x += 22, b -> withCef(CefBrowser::reload)));

		int right = width - 4;
		addRenderableWidget(iconButton("⚙", "ingamewiki.settings", right -= 20, b -> {
			keepBrowser = true;
			minecraft.gui.setScreen(new ConfigScreen(this));
		}));
		addRenderableWidget(iconButton("↗", "ingamewiki.openBrowser", right -= 22, b -> {
			Blaze3D.openUri(currentUrl());
			onClose();
		}));
		addRenderableWidget(iconButton("▤", "ingamewiki.openIngame", right -= 22, b -> openIngame()));
		right -= 102;
		modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
					IngameWiki.setMode(IngameWiki.config().mode.next());
					b.setMessage(modeLabel());
				})
				.bounds(right, 3, 100, 20)
				.tooltip(Tooltip.create(Component.translatable("ingamewiki.mode.tooltip")))
				.build());
		modeButton.setMessage(modeLabel());
		resizeBrowser();
	}

	private Button iconButton(String icon, String tooltip, int x, Button.OnPress onPress) {
		return Button.builder(Component.literal(icon), onPress)
				.bounds(x, 3, 20, 20)
				.tooltip(Tooltip.create(Component.translatable(tooltip)))
				.build();
	}

	private static Component modeLabel() {
		return Component.translatable("ingamewiki.mode.button", IngameWiki.config().mode.label());
	}

	private static WikiLinks links() {
		return IngameWiki.client().links();
	}

	private void withCef(java.util.function.Consumer<CefBrowser> action) {
		if (browser != null) {
			action.accept(browser.getCefBrowser());
		}
	}

	private URI currentUrl() {
		if (browser != null) {
			try {
				String url = browser.getCefBrowser().getURL();
				if (url != null && !url.isEmpty()) {
					return URI.create(url);
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		return initialUrl;
	}

	/**
	 * Re-opens the current page in the in-game renderer, if it is a page of the configured wiki.
	 */
	private void openIngame() {
		WikiRequest request = currentPage();
		if (request == null) {
			String query = queryParameter(currentUrl(), "search");
			request = query != null ? new WikiRequest.Go(query) : WikiOpener.home();
		}
		minecraft.gui.setScreen(new WikiScreen(parent, request));
	}

	/**
	 * The article currently shown, or {@code null} if the browser is not on an article of the configured wiki.
	 */
	private WikiRequest.@Nullable Page currentPage() {
		URI uri = currentUrl();
		String title = links().title(uri);
		if (title == null) {
			return null;
		}
		String fragment = uri.getRawFragment() == null ? null : WikiLinks.normalizeFragment(uri.getRawFragment());
		return new WikiRequest.Page(title, fragment);
	}

	private static @Nullable String queryParameter(URI uri, String name) {
		String query = uri.getRawQuery();
		if (query == null) {
			return null;
		}
		for (String part : query.split("&")) {
			if (part.startsWith(name + "=")) {
				return java.net.URLDecoder.decode(part.substring(name.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- browser lifecycle

	private int scale() {
		return Math.max(1, minecraft.getWindow().getGuiScale());
	}

	private int areaTop() {
		return BAR_HEIGHT;
	}

	private void resizeBrowser() {
		browserWidth = Math.max(1, width * scale());
		browserHeight = Math.max(1, (height - areaTop()) * scale());
		if (browser != null) {
			browser.resize(browserWidth, browserHeight);
		}
	}

	@Override
	public void tick() {
		if (browser == null && error == null && initialization.isDone()) {
			try {
				MCEFApi api = initialization.getFuture().join();
				browser = api.createBrowser(initialUrl.toString(), false);
				browser.resize(browserWidth, browserHeight);
				browser.setFocus(true);
			} catch (RuntimeException e) {
				IngameWiki.LOGGER.warn("Could not start MCEF", e);
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				error = Component.translatable("ingamewiki.mcef.failed", String.valueOf(cause.getMessage()));
			}
		}
		if (browser != null) {
			CefBrowser cef = browser.getCefBrowser();
			if (backButton != null) {
				backButton.active = cef.canGoBack();
			}
			if (forwardButton != null) {
				forwardButton.active = cef.canGoForward();
			}
		}
	}

	@Override
	public void removed() {
		WikiOpener.closed(null, currentPage());
		if (keepBrowser) {
			keepBrowser = false;
			return;
		}
		if (browser != null) {
			browser.close();
			browser = null;
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int top = areaTop();
		GpuTextureView view = browser == null ? null : browser.getTextureView();
		if (view != null) {
			graphics.blit(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR), 0, top, width, height, 0.0F, 1.0F, 0.0F, 1.0F);
			if (mouseY >= top) {
				graphics.requestCursor(browser.getCursorType());
			}
		} else {
			graphics.fill(0, top, width, height, 0xFF101010);
			Component text = error != null ? error : Component.translatable("ingamewiki.mcef.preparing", stageText());
			graphics.centeredText(font, text, width / 2, (top + height) / 2 - 4, error != null ? 0xFFFF7070 : 0xFFA0A0A0);
		}
		String url = currentUrl().toString();
		int urlLeft = 4 + 4 * 22 + 4;
		int urlRight = modeButton == null ? width - 4 : modeButton.getX() - 6;
		if (urlRight - urlLeft > 30) {
			graphics.text(font, font.plainSubstrByWidth(url, urlRight - urlLeft), urlLeft, 9, 0xFFB0B0B0, false);
		}
	}

	private Component stageText() {
		MCEFApi.Initialization.Stage stage = initialization.getStage();
		// jcefmaven reports 0-100, or -1 when unknown.
		int percent = Math.max(0, Math.round(initialization.getPercentage()));
		return Component.translatable("ingamewiki.mcef.stage." + stage.name().toLowerCase(Locale.ROOT), percent);
	}

	// ---------------------------------------------------------------- input forwarding

	private boolean inBrowser(double mouseY) {
		return browser != null && mouseY >= areaTop();
	}

	private int bx(double mouseX) {
		return (int) (mouseX * scale());
	}

	private int by(double mouseY) {
		return (int) ((mouseY - areaTop()) * scale());
	}

	private MouseButtonEvent toBrowser(MouseButtonEvent event) {
		return new MouseButtonEvent(bx(event.x()), by(event.y()), event.buttonInfo());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (inBrowser(event.y())) {
			setFocused(null);
			browser.setFocus(true);
			browser.onMouseClicked(toBrowser(event), doubleClick);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (browser != null) {
			browser.onMouseReleased(toBrowser(event));
		}
		return super.mouseReleased(event);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (browser != null) {
			browser.onMouseMoved(bx(mouseX), by(mouseY));
		}
		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (browser != null) {
			browser.onMouseMoved(bx(event.x()), by(event.y()));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (inBrowser(mouseY)) {
			browser.onMouseScrolled(bx(mouseX), by(mouseY), vertical);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			onClose();
			return true;
		}
		if (browser != null && getFocused() == null) {
			browser.onKeyPressed(event);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (browser != null && getFocused() == null) {
			browser.onKeyReleased(event);
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (browser != null && getFocused() == null) {
			browser.onCharTyped(event);
			return true;
		}
		return super.charTyped(event);
	}
}
