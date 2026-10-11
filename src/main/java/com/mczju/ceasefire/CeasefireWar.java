package com.mczju.ceasefire;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.mczju.ceasefire.block.BlockProtectionListener;
import com.mczju.ceasefire.game.CeasefireGame;
import com.mczju.ceasefire.game.GamePlayListener;
import com.mczju.ceasefire.player.CeasefirePlayerData;
import com.mczju.ceasefire.room.CeasefireRoom;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The War of Ceasefire 插件主类：注册监听器、玩家数据与游戏。
 */
public final class CeasefireWar extends JavaPlugin {

    private static CeasefireWar instance;

    @Override
    public void onEnable() {
        instance = this;

        // 方块保护策略2：只允许拆除玩家放置的方块（含桶生成物）；镐用于破防，见 BlockProtectionListener。
        getServer().getPluginManager().registerEvents(new BlockProtectionListener(), this);

        // 游戏玩法：知识之书使用 + 合成配方校验。
        getServer().getPluginManager().registerEvents(new GamePlayListener(), this);

        // 注册玩家数据（历史 KD，用于选队长/排行榜）。
        MCZJUGameCore.getPlayerDataManager().registerPlayerData("the_war_of_ceasefire", CeasefirePlayerData.class);

        // 注册游戏（游戏类 + 房间类）。
        MCZJUGameCore.getGameManager().registerGame(CeasefireGame.class, CeasefireRoom.class);

        getLogger().info("the-war-of-ceasefire enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("the-war-of-ceasefire disabled");
        instance = null;
    }

    public static CeasefireWar getInstance() {
        return instance;
    }
}
