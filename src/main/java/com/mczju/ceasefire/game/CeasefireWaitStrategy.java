package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.DefaultGameWaitStrategy;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.party.Party;

/**
 * 等待策略：不自动开始，等待 /mgc start 指令；指令触发时按人数自动适配队伍数。
 * 最少 2 人（1v1），最多 12 人；具体可开局人数由映射表决定（5/7/11 不支持）。
 */
public final class CeasefireWaitStrategy extends DefaultGameWaitStrategy {

    /** 最大人数（4 队 x 3 人）。 */
    private static final int MAX_PLAYERS = 12;

    public CeasefireWaitStrategy(AbstractGame game) {
        super(game, MAX_PLAYERS, 2); // 最多 12 人，最少 2 人（实际可开局人数由映射表决定）。
    }

    @Override
    public boolean onPlayerJoin(PlayerExt player) {
        // 满员拦截（此时玩家已预注册，人数包含自己）。
        if (game.getPlayers().size() > MAX_PLAYERS) {
            player.sender().warn("该游戏人数已满（最多 %d 人）".formatted(MAX_PLAYERS));
            return false;
        }
        return true; // 允许加入，不自动开始，等 /mgc start。
    }

    @Override
    public boolean onPartyJoin(Party party) {
        party.getLeader().sender().warn("该游戏请单独加入，开局会自动分队");
        return false;
    }

    @Override
    public void onPlayerLeave(PlayerExt player) {
        game.sender().warn("玩家 %s 退出了等待".formatted(player.getDisplayName()));
        if (game.getPlayers().isEmpty()) {
            cancelGame();
        }
    }

    @Override
    public void tryStart() {
        int count = game.getPlayers().size();
        if (CeasefireGame.isValidPlayerCount(count)) {
            startGame();
        } else {
            game.sender().warn("当前 %d 人无法开始，可开局人数：2/3/4/6/8/9/10/12".formatted(count));
        }
    }
}
