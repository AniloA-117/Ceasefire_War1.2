package com.mczju.ceasefire.block;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块记录器（策略2）。
 *
 * <p>追踪两类内容：
 * <ol>
 *   <li><b>玩家放置的方块</b>（含倒桶产生的液体源）—— 允许拆除，终局<b>清除</b>；</li>
 *   <li><b>被玩家用桶装走的自然液体源</b>（水 / 岩浆）—— 终局<b>放回原处</b>，不让地图被逐局抽干。</li>
 * </ol>
 *
 * <p>不追踪"由水派生出的源方块"（无限水刷出来的那格）：那种清理需要向外扩散扫描，
 * 有误删天然水体的风险，已按需求移除。代价是刷出来的源方块会留在地图上。
 *
 * <p>每个 {@link com.mczju.ceasefire.game.CeasefireGame} 实例持有一个本类实例，
 * 使多房间、多局游戏的方块变化互相隔离。</p>
 */
public final class BlockChangeTracker {

    /** 本局玩家放置过的方块位置（去重，yaw/pitch 恒为 0）。 */
    private final Set<Location> placedBlocks = ConcurrentHashMap.newKeySet();

    /** 被玩家用桶装走的自然液体源：位置 → 原液体类型，对局结束时放回去。 */
    private final Map<Location, Material> removedLiquids = new ConcurrentHashMap<>();

    /** 每 tick 清除的方块数量（分批，避免主线程卡顿）。 */
    private static final int BATCH_SIZE = 50;

    /** 记录一个玩家放置的方块。 */
    public void markPlaced(Block block) {
        placedBlocks.add(block.getLocation().clone());
    }

    /** 该方块是否为玩家放置（允许拆除的判断依据）。 */
    public boolean isPlayerPlaced(Block block) {
        return placedBlocks.contains(block.getLocation());
    }

    /** 玩家拆除了自己放置的方块后，从记录中移除（该位置已空，无需还原）。 */
    public void unmark(Block block) {
        placedBlocks.remove(block.getLocation());
    }

    /**
     * 记录被玩家用桶**装走**的自然液体源（水 / 岩浆），对局结束时放回原处。
     *
     * <p>装桶不会触发 {@code BlockBreakEvent}，所以不进 {@link #placedBlocks}；
     * 这里单独记一份，避免地图上的水域 / 岩浆被逐局抽干。
     */
    public void markRemovedLiquid(Block block, Material liquidType) {
        removedLiquids.put(block.getLocation().clone(), liquidType);
    }

    /** 记录过的方块数量。 */
    public int size() {
        return placedBlocks.size();
    }

    /**
     * 分批、在主线程还原本局对地图造成的改动。每 tick 处理一批，避免一次性大量方块操作造成卡顿。
     *
     * <p>顺序（顺序很重要）：
     * <ol>
     *   <li>先清除玩家放置的方块 / 液体；</li>
     *   <li>全部清空之后，再把被玩家用桶装走的自然水 / 岩浆<b>放回原处</b>。</li>
     * </ol>
     *
     * <p>"先清后还"这个顺序顺带解决了「玩家用方块把被装走的水坑填上」的情况：
     * 他填的那块本身也在 {@link #placedBlocks} 里，会先被清成空气，
     * 于是还原时该位置正好是空气 → 原来的水被放回去，玩家的方块也不残留。
     *
     * @param plugin 用于调度任务
     */
    public void restore(JavaPlugin plugin) {
        List<Location> clearList = new ArrayList<>(placedBlocks);
        placedBlocks.clear();

        Map<Location, Material> restoreList = new HashMap<>(removedLiquids);
        removedLiquids.clear();

        if (clearList.isEmpty() && restoreList.isEmpty()) {
            plugin.getLogger().info("[Ceasefire] 清理：本局没有记录到任何方块改动");
            return;
        }
        plugin.getLogger().info("[Ceasefire] 清理：玩家方块 %d 个，待还原液体 %d 个"
                .formatted(clearList.size(), restoreList.size()));

        final int[] index = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(index[0] + BATCH_SIZE, clearList.size());
            for (int i = index[0]; i < end; i++) {
                clearBlock(clearList.get(i));
            }
            index[0] = end;
            if (index[0] < clearList.size()) {
                return;
            }
            // 全部清空之后，才把被装走的自然液体源放回去。
            restoreRemovedLiquids(restoreList);
            task.cancel();
        }, 1L, 1L);
    }

    /** 清除单个位置：液体用带物理更新的删除，避免遗留悬空的流动方块。 */
    private static void clearBlock(Location location) {
        Block block = location.getBlock();
        Material type = block.getType();
        if (type == Material.AIR) {
            return;
        }
        boolean liquid = type == Material.WATER || type == Material.LAVA;
        block.setType(Material.AIR, liquid);
    }

    /**
     * 把被玩家装走的自然水 / 岩浆放回原处。
     *
     * <p>只填**空气**格子：
     * <ul>
     *   <li>玩家用方块填了这个坑 → 那块在清除阶段已被清成空气 → 水照样还原；</li>
     *   <li>坑里已经是别的东西（例如非本局玩家放的方块、或邻居流过来的水）→ <b>不覆盖</b>，保持现状。</li>
     * </ul>
     */
    private void restoreRemovedLiquids(Map<Location, Material> restoreList) {
        restoreList.forEach((location, type) -> {
            if (location.getWorld() == null) {
                return;
            }
            Block block = location.getBlock();
            if (block.getType() == Material.AIR) {
                block.setType(type, true);
            }
        });
    }
}
