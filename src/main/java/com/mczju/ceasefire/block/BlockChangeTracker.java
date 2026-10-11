package com.mczju.ceasefire.block;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块记录器（策略2）。
 *
 * <p>追踪玩家【放置】的方块与水/岩浆反应生成的方块（圆石/石头/黑曜石等）；
 * 不记录对自然方块的破坏（策略2 禁止破坏自然方块）。游戏结束时批量、
 * 分批清除这些方块，避免卡顿。</p>
 *
 * <p>每个 {@link com.mczju.ceasefire.game.CeasefireGame} 实例持有一个本类实例，
 * 使多房间、多局游戏的方块变化互相隔离。</p>
 */
public final class BlockChangeTracker {

    /** 本局玩家放置过的方块位置（去重，yaw/pitch 恒为 0）。 */
    private final Set<Location> placedBlocks = ConcurrentHashMap.newKeySet();

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

    /** 记录过的方块数量。 */
    public int size() {
        return placedBlocks.size();
    }

    /**
     * 分批、在主线程还原（清除）本局所有玩家放置的方块。
     * 每 tick 处理一批，避免一次性大量方块操作造成卡顿。
     *
     * @param plugin 用于调度任务。
     */
    public void restore(JavaPlugin plugin) {
        List<Location> blocks = new ArrayList<>(placedBlocks);
        placedBlocks.clear();
        if (blocks.isEmpty()) {
            return;
        }

        final int[] index = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(index[0] + BATCH_SIZE, blocks.size());
            for (int i = index[0]; i < end; i++) {
                Block block = blocks.get(i).getBlock();
                Material type = block.getType();
                if (type == Material.AIR) {
                    continue;
                }
                // 液体（水/岩浆源）删除时触发流体更新，避免遗留悬空的流动方块。
                boolean liquid = type == Material.WATER || type == Material.LAVA;
                block.setType(Material.AIR, liquid);
            }
            index[0] = end;
            if (index[0] >= blocks.size()) {
                task.cancel();
            }
        }, 1L, 1L);
    }
}
