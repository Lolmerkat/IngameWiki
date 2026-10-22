package dev.jan.ingamewiki;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.jan.ingamewiki.config.OpenMode;
import dev.jan.ingamewiki.wiki.WikiRequest;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

/**
 * {@code /wiki [page]} and {@code /wikimode [ingame|browser|mcef]}.
 */
final class WikiCommands {
	private WikiCommands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("wiki")
				.executes(ctx -> {
					// The chat screen closes after the command ran, so open the wiki on the next tick.
					IngameWiki.runNextTick(() -> WikiOpener.open(WikiOpener.home(), null));
					return 1;
				})
				.then(argument("page", StringArgumentType.greedyString()).executes(ctx -> {
					String page = StringArgumentType.getString(ctx, "page");
					IngameWiki.runNextTick(() -> WikiOpener.open(new WikiRequest.Lookup(page), null));
					return 1;
				})));

		LiteralArgumentBuilder<FabricClientCommandSource> mode = literal("wikimode").executes(ctx -> {
			ctx.getSource().sendFeedback(Component.translatable("ingamewiki.mode.current", IngameWiki.config().mode.label()));
			return 1;
		});
		for (OpenMode m : OpenMode.values()) {
			mode.then(literal(m.id()).executes(ctx -> {
				IngameWiki.setMode(m);
				ctx.getSource().sendFeedback(Component.translatable("ingamewiki.mode.changed", m.label()));
				return 1;
			}));
		}
		dispatcher.register(mode);
	}
}
