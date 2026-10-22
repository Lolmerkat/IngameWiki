package dev.jan.ingamewiki.render;

import dev.jan.ingamewiki.wiki.WikiLinks;

/**
 * @param images     whether images are laid out at all
 * @param imageScale GUI pixels per CSS pixel
 * @param links      used to resolve image URLs
 */
public record LayoutOptions(boolean images, float imageScale, WikiLinks links) {
}
