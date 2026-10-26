package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.DefaultGameWaitStrategy;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.party.Party;

/**
 * 等待策略：不自动开始，等待 /mgc start 指令。
 * 最少 2 人（1v1），最多 12 人；可开局人数由映射表决定，5/7/11 人沿用上一次有效队伍数。
 * 等待阶段玩家可自行选队（GUI 或 /ceasefire team），未选者在开局时随机补齐。
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
            // 拒绝后必须自己把玩家退回大厅档案，否则会卡在游戏档案上（物品栏看起来被清空）。
            rejectPlayer(player, "该游戏人数已满（最多 %d 人）".formatted(MAX_PLAYERS));
            return false;
        }
        // 加入提示：玩家进出都播报，避免房间里的人不知道人数/队伍上限已经变了。
        game.sender().info("玩家 %s 加入了等待（%d/%d）".formatted(
                player.getDisplayName(), game.getPlayers().size(), MAX_PLAYERS));
        // 等待阶段：按最新人数重算队伍数（5/7/11 沿用上一次有效队伍数），并补发选队物品。
        if (game instanceof CeasefireGame ceasefire) {
            ceasefire.refreshWaitingTeams();
            ceasefire.giveTeamSelectItem(player);
        }
        return true; // 允许加入，不自动开始，等 /mgc start。
    }

    /**
     * 队伍（MGC party）整队加入等待房：接受后**立刻解散该队伍**，
     * 所有人回到「待选队」状态各自选队（本局每队人数由总人数刚性决定，无法保证大队伍同队）。
     * 全队加入会让人数超过上限时，整队拒绝。
     *
     * <p>刻意**不调用 {@code super.onPartyJoin()}**：框架的 {@code onJoin()} 在
     * {@code size == playerLimit} 时会自动开局，而本局设计要求等 {@code /mgc start}。
     */
    @Override
    public boolean onPartyJoin(Party party) {
        if (!(game instanceof CeasefireGame ceasefire)) {
            return false;
        }
        // 整队拒绝。此时全队已被 tryJoin 预注册，getPlayers() 已包含全队。
        if (game.getPlayers().size() > MAX_PLAYERS) {
            rejectParty(party, "该游戏人数已满（最多 %d 人），队伍无法加入".formatted(MAX_PLAYERS));
            return false;
        }
        announcePartyJoin(party);
        ceasefire.acceptParty(party);
        return true; // 允许加入，不自动开始，等 /mgc start。
    }

    /**
     * 整队加入的提示：**一人一条**，人数按加入顺序递增，
     * 与单人加入的播报格式完全一致（全队已被预注册，故从「总人数 - 队伍人数」开始累加）。
     */
    private void announcePartyJoin(Party party) {
        int count = game.getPlayers().size() - party.size();
        for (PlayerExt member : party.getAllPlayer()) {
            count++;
            game.sender().info("玩家 %s 加入了等待（%d/%d）".formatted(
                    member.getDisplayName(), count, MAX_PLAYERS));
        }
    }

    /**
     * 单人加入被拒：通知本人，并把档案切回大厅。
     *
     * <p>必须自己切档案——MGC 的 JOIN_FAIL 回滚在 {@code switchProfile(null)} **之前**
     * 就 {@code return} 了；不补这一步玩家会停在游戏档案上（物品栏看起来被清空，且再点加入同一游戏无法恢复）。
     */
    private static void rejectPlayer(PlayerExt player, String message) {
        player.sender().warn(message);
        backToLobby(player);
    }

    /**
     * 整队拒绝：通知全队，并把每个人的档案切回大厅（与 {@link #rejectPlayer} 同一套处理）。
     */
    private static void rejectParty(Party party, String message) {
        party.sender().warn(message);
        party.getAllPlayer().forEach(CeasefireWaitStrategy::backToLobby);
    }

    /**
     * 退回大厅档案。
     *
     * <p>{@code switchProfile(null)} 一步就够了：档案复原物品栏/末影箱/经验，
     * {@code applyConfiguredProfileState} 会把 Tab 页脚改回大厅欢迎语、并把游戏模式设回大厅的 ADVENTURE；
     * Tab 名称由框架随后的 {@code leaveGame(JOIN_FAIL)} → {@code TabManager#onPlayerLeave} 清理。
     *
     * <p>注意：这里**不要**再调 {@code sendPlayerListFooter(Component.empty())}，
     * 那会把刚恢复的大厅欢迎页脚一起清空。
     */
    private static void backToLobby(PlayerExt player) {
        player.switchProfile(null);
    }

    @Override
    public void onPlayerLeave(PlayerExt player) {
        game.sender().warn("玩家 %s 退出了等待".formatted(player.getDisplayName()));
        if (game.getPlayers().isEmpty()) {
            cancelGame();
            return;
        }
        // 人数变少可能让队伍数下降；此时会移除超出队伍的已选玩家并提示重选。
        if (game instanceof CeasefireGame ceasefire) {
            ceasefire.refreshWaitingTeams();
        }
    }

    @Override
    public void tryStart() {
        // 人数减少会让每队上限变小，已有队伍可能超员；此时不允许开局，并通知局内全体。
        if (game instanceof CeasefireGame ceasefire && ceasefire.hasTeamOverCapacity()) {
            game.sender().warn("队伍人数不正确，无法开始");
            return;
        }
        int count = game.getPlayers().size();
        if (CeasefireGame.isValidPlayerCount(count)) {
            startGame();
        } else {
            game.sender().warn("当前 %d 人无法开始，可开局人数：2/3/4/6/8/9/10/12".formatted(count));
        }
    }
}
