package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.PlayerQuitReason;
import org.jetbrains.annotations.NotNull;

/**
 * 退出策略：掉线 / 指令退出时不再中止整局（默认策略会 abort），
 * 而是把退出者判负出局，比赛继续；场上无人时由游戏侧终止对局。
 */
public final class CeasefireQuitStrategy extends AbstractPlayerQuitStrategy {

    public CeasefireQuitStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerQuit(@NotNull PlayerExt player, @NotNull PlayerQuitReason reason) {
        if (game instanceof CeasefireGame arena) {
            arena.handlePlayerQuit(player, reason);
        }
    }
}
