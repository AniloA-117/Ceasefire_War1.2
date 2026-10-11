package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;

/** 死亡策略：交给 CeasefireGame 处理（掉落、复活倒计时、团灭判定）。 */
public final class CeasefireDeathStrategy extends AbstractPlayerDeathStrategy {

    public CeasefireDeathStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerDeath(@NotNull PlayerExt player, @NotNull PlayerDeathEvent event) {
        if (game instanceof CeasefireGame arena) {
            arena.handlePlayerDeath(player, event);
        }
    }
}
