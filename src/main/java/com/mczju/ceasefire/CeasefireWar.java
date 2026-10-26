package com.mczju.ceasefire;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.mczju.ceasefire.block.BlockProtectionListener;
import com.mczju.ceasefire.game.CeasefireGame;
import com.mczju.ceasefire.game.GamePlayListener;
import com.mczju.ceasefire.game.TeamCommand;
import com.mczju.ceasefire.game.TeamSelectListener;
import com.mczju.ceasefire.item.TeamSelectItem;
import com.mczju.ceasefire.room.CeasefireRoom;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * The War of Ceasefire 插件主类：注册监听器、选队物品、命令与游戏。
 *
 * <p>1.1 起不再注册持久化玩家数据：历史 KD 统计与「按 KD 选队长」已移除，
 * 每局的击败/死亡统计只保存在内存中，并在对局结束时于聊天框播报。
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

        // 等待阶段「选择队伍」物品的右键入口。
        getServer().getPluginManager().registerEvents(new TeamSelectListener(), this);

        // 注册选队物品，供 PlayerExt#giveItem / giveItemIfDontHave 按 ID 发放与辨识。
        MCZJUGameCore.getItemManager().register(new TeamSelectItem());

        // 注册游戏（游戏类 + 房间类）。
        MCZJUGameCore.getGameManager().registerGame(CeasefireGame.class, CeasefireRoom.class);

        // 注册 /ceasefire（别名 /cf）命令。
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        TeamCommand.node(),
                        "停战之战：选择队伍",
                        List.of("cf")));

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
