package dev.jan.ingamewiki.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.jan.ingamewiki.IngameWiki;
import dev.jan.ingamewiki.wiki.WikiClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Downloads wiki images on demand and keeps them as textures. Images that were not drawn for a while are released
 * once the cache grows large.
 */
public final class ImageCache {
	private static final int MAX_PARALLEL = 6;
	private static final int SOFT_LIMIT = 256;
	private static final long EVICT_AFTER_MS = 60_000;
	private static final int MAX_DIMENSION = 2048;

	public record Texture(Identifier id, int width, int height) {
	}

	private final WikiClient client;
	private final Map<URI, Texture> loaded = new ConcurrentHashMap<>();
	private final Map<URI, Long> lastUsed = new ConcurrentHashMap<>();
	private final Map<URI, Boolean> requested = new ConcurrentHashMap<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger counter = new AtomicInteger();
	private int generation;

	public ImageCache(WikiClient client) {
		this.client = client;
	}

	/**
	 * Returns the texture, or {@code null} while it is loading or if it failed. Starts the download if needed; call it
	 * only for images that are actually visible, so those are fetched first.
	 */
	public @Nullable Texture get(URI uri) {
		Texture texture = loaded.get(uri);
		if (texture != null) {
			lastUsed.put(uri, System.currentTimeMillis());
			return texture;
		}
		if (!requested.containsKey(uri) && reserveSlot()) {
			requested.put(uri, true);
			load(uri);
		}
		return null;
	}

	private boolean reserveSlot() {
		while (true) {
			int current = inFlight.get();
			if (current >= MAX_PARALLEL) {
				return false;
			}
			if (inFlight.compareAndSet(current, current + 1)) {
				return true;
			}
		}
	}

	private void load(URI uri) {
		int gen = generation;
		client.bytes(uri)
				.thenApply(bytes -> {
					try {
						return decode(bytes);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				})
				.whenComplete((image, error) -> {
					inFlight.decrementAndGet();
					if (error != null) {
						IngameWiki.LOGGER.debug("Could not load image {}", uri, error);
						return;
					}
					Minecraft.getInstance().execute(() -> {
						if (gen != generation) {
							image.close();
							return;
						}
						Identifier id = Identifier.fromNamespaceAndPath(IngameWiki.MOD_ID, "wiki_image/" + counter.incrementAndGet());
						Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
						loaded.put(uri, new Texture(id, image.getWidth(), image.getHeight()));
						lastUsed.put(uri, System.currentTimeMillis());
						evict();
					});
				});
	}

	private void evict() {
		if (loaded.size() <= SOFT_LIMIT) {
			return;
		}
		long cutoff = System.currentTimeMillis() - EVICT_AFTER_MS;
		Iterator<Map.Entry<URI, Texture>> it = loaded.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<URI, Texture> e = it.next();
			if (lastUsed.getOrDefault(e.getKey(), 0L) < cutoff) {
				Minecraft.getInstance().getTextureManager().release(e.getValue().id());
				it.remove();
				lastUsed.remove(e.getKey());
				requested.remove(e.getKey());
			}
		}
	}

	/**
	 * Releases all textures. Images that are still downloading are discarded when they arrive.
	 */
	public void clear() {
		generation++;
		List<Texture> textures = new ArrayList<>(loaded.values());
		loaded.clear();
		lastUsed.clear();
		requested.clear();
		for (Texture t : textures) {
			Minecraft.getInstance().getTextureManager().release(t.id());
		}
	}

	static NativeImage decode(byte[] bytes) throws IOException {
		if (isPng(bytes)) {
			NativeImage image = NativeImage.read(bytes);
			if (image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
				image.close();
				throw new IOException("Image too large");
			}
			return image;
		}
		// GIF (first frame) and JPEG.
		BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
		if (buffered == null) {
			throw new IOException("Unsupported image format");
		}
		int w = buffered.getWidth();
		int h = buffered.getHeight();
		if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
			throw new IOException("Image too large");
		}
		NativeImage image = new NativeImage(w, h, false);
		int[] argb = buffered.getRGB(0, 0, w, h, null, 0, w);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setPixel(x, y, argb[y * w + x]);
			}
		}
		return image;
	}

	private static boolean isPng(byte[] bytes) {
		return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
	}
}
