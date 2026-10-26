package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.utils.TextParser;
import com.mczju.ceasefire.game.menu.TeamSelectMenu;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /ceasefire team} —— 等待阶段选择队伍。
 *
 * <ul>
 *   <li>{@code /ceasefire team} 打开选队菜单</li>
 *   <li>{@code /ceasefire team <red|blue|yellow|green>} 直接入队</li>
 * </ul>
 */
public final class TeamCommand {

    /** 颜色名 -> 队伍编号（顺序必须与 CeasefireGame#teamName 一致）。 */
    private static final List<String> COLORS = List.of("red", "blue", "yellow", "green");

    private TeamCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("ceasefire")
                .requires(source -> source.getSender().hasPermission("ceasefire.team"))
                .then(Commands.literal("team")
                        .executes(ctx -> openMenu(ctx.getSource().getSender()))
                        .then(Commands.argument("color", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (String color : COLORS) {
                                        builder.suggest(color);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> select(
                                        ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "color")))))
                .build();
    }

    private static int openMenu(CommandSender rawSender) {
        Player player = asPlayer(rawSender);
        if (player == null) {
            return 0;
        }
        CeasefireGame game = waitingGame(player);
        if (game == null) {
            return 0;
        }
        new TeamSelectMenu(player, game).open();
        return Command.SINGLE_SUCCESS;
    }

    private static int select(CommandSender rawSender, String color) {
        Player player = asPlayer(rawSender);
        if (player == null) {
            return 0;
        }
        int team = COLORS.indexOf(color.toLowerCase());
        if (team < 0) {
            rawSender.sendMessage(TextParser.parse("<red>未知队伍，可用：red / blue / yellow / green"));
            return 0;
        }
        CeasefireGame game = waitingGame(player);
        if (game == null) {
            return 0;
        }
        return game.selectTeam(new PlayerExt(player), team) ? Command.SINGLE_SUCCESS : 0;
    }

    private static Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(TextParser.parse("<red>该命令只能由玩家执行"));
        return null;
    }

    private static CeasefireGame waitingGame(Player player) {
        AbstractGame game = new PlayerExt(player).getGame();
        if (!(game instanceof CeasefireGame ceasefire)) {
            player.sendMessage(TextParser.parse("<red>你当前不在停战之战的等待房间中"));
            return null;
        }
        return ceasefire;
    }
}
