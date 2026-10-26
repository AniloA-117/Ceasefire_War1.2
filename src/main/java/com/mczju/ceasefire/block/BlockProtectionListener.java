package com.mczju.ceasefire.block;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.mczju.ceasefire.CeasefireWar;
import com.mczju.ceasefire.game.CeasefireGame;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * 方块保护（策略2）：
 * <ol>
 *   <li>玩家只能拆除【玩家放置的】方块；破坏自然方块会被取消。</li>
 *   <li>水/岩浆反应生成的方块（圆石/石头/黑曜石等）也记为本局生成物，可被拆除、终局统一清理。</li>
 *   <li>火焰不允许烧毁自然方块，也不向自然方块蔓延（玩家建筑不受此限）。</li>
 * </ol>
 *
 * <p>镐类（金/铁/钻石镐）已在各职业配方池中，用途是快速拆除玩家建造的防御，
 * 而不是挖掘自然地形。</p>
 */
public final class BlockProtectionListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        AbstractGame game = new PlayerExt(event.getPlayer()).getGame();
        if (!(game instanceof CeasefireGame arena)) {
            return;
        }
        Block block = event.getBlock();
        // 把方块放进已有的水/岩浆里会把原液体「顶掉」——和装桶一样，得记下原液体，
        // 否则终局只把它清成空气，天然水/岩浆就永久消失了（留下一个空气坑）。
        // 只处理：① 自然液体（玩家自己倒的本来就该消失）② 源方块（流动液体不算）。
        BlockState replaced = event.getBlockReplacedState();
        Material previous = replaced.getType();
        if ((previous == Material.WATER || previous == Material.LAVA)
                && isLiquidSource(replaced.getBlockData())
                && !arena.getBlockTracker().isPlayerPlaced(block)) {
            arena.getBlockTracker().markRemovedLiquid(block, previous);
        }
        arena.getBlockTracker().markPlaced(block);
    }

    /** 是否为液体**源**方块（液面等级等于最小值）；流动的液体不算。 */
    private static boolean isLiquidSource(BlockData data) {
        return data instanceof Levelled levelled && levelled.getLevel() == levelled.getMinimumLevel();
    }

    /**
     * 倒桶产生的水/岩浆**源方块**：记为本局生成物，使其在终局被
     * {@link BlockChangeTracker#restore} 一并清除，不在地图上留下永久积水/岩浆。
     *
     * <p>注意坐标：{@code getBlock()} 返回的是**被点击的方块**，液体实际生成在它的相邻面，
     * 必须取 {@code getRelative(getBlockFace())}，否则记录到错的位置、清理无效。
     *
     * <p>只需记录源方块：{@code restore()} 用的是带物理更新的 {@code setType(AIR, true)}，
     * 源头一删，流动出去的水/岩浆下一 tick 会自行消失。
     * 水 + 岩浆**反应生成**的圆石/石头/黑曜石由 {@link #onForm} 记录。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        // 归属判定：与 onPlace / onBucketFill 保持一致 —— 记到「玩家所在的对局」。
        AbstractGame game = new PlayerExt(event.getPlayer()).getGame();
        if (!(game instanceof CeasefireGame arena)) {
            return;
        }
        CeasefireWar plugin = CeasefireWar.getInstance();
        if (plugin == null) {
            return;
        }
        Block clicked = event.getBlock();
        Block expected = clicked.getRelative(event.getBlockFace());
        // 倒桶前先看「预期落点」原本是什么：只有原本是液体源时才需要终局还原。
        Material previous = expected.getType();
        boolean previousLiquid = (previous == Material.WATER || previous == Material.LAVA)
                && isLiquidSource(expected.getBlockData());
        // 不再猜液体最终落在哪一格：延迟 1 tick 后看**实际**哪一格变成了液体源，再记录。
        // 固定用 getBlock().getRelative(face) 一旦遇到服务端语义变化就会记错坐标，终局清不掉。
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block actual = isLiquidSource(expected.getBlockData()) ? expected
                    : (isLiquidSource(clicked.getBlockData()) ? clicked : null);
            if (actual == null) {
                return; // 这次没有生成新的液体源（例如倒进已有的水里）
            }
            if (previousLiquid && !arena.getBlockTracker().isPlayerPlaced(expected)) {
                arena.getBlockTracker().markRemovedLiquid(expected, previous);
            }
            arena.getBlockTracker().markPlaced(actual);
            plugin.getLogger().info("[Ceasefire] 倒桶记录: %d,%d,%d %s"
                    .formatted(actual.getX(), actual.getY(), actual.getZ(), actual.getType()));
        });
    }

    /**
     * 用桶**装走**地图上的水 / 岩浆：不拦截（保留铁桶玩法），但把「原液体 + 位置」记下来，
     * 对局结束时由 {@link BlockChangeTracker#restore} 放回原处，避免地图被逐局抽干。
     *
     * <p>装桶不会触发 {@code BlockBreakEvent}，所以这条记录必须单独保存。
     * 装的是本局玩家自己倒出的液体（已在 placedBlocks 里）时无需还原 —— 那本来就该被清掉。
     *
     * <p>已知未覆盖：与含水方块（waterlogged）交互取水不会走到这里，那种情况取走的是方块属性而非液体源方块。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        // 与另外两条路径同规则：只记源方块（桶只能从源方块装，这里显式校验一次以防万一）。
        if ((type != Material.WATER && type != Material.LAVA) || !isLiquidSource(block.getBlockData())) {
            return; // 不是液体源（如挤奶、装雪球等），不管
        }
        // 归属判定：与 onPlace 保持一致 —— 记到「玩家所在的对局」。
        AbstractGame game = new PlayerExt(event.getPlayer()).getGame();
        if (!(game instanceof CeasefireGame arena)) {
            return;
        }
        if (arena.getBlockTracker().isPlayerPlaced(block)) {
            return; // 玩家自己倒的，本来就该被清掉，不用还原
        }
        arena.getBlockTracker().markRemovedLiquid(block, type);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        AbstractGame game = new PlayerExt(player).getGame();
        if (!(game instanceof CeasefireGame arena)) {
            return; // 不在游戏中，不干预。
        }
        if (arena.getBlockTracker().isPlayerPlaced(event.getBlock())) {
            // 拆除玩家放置的方块：允许，并取消记录（该位置已空，无需还原）。
            arena.getBlockTracker().unmark(event.getBlock());
        } else {
            // 破坏自然方块：取消。
            event.setCancelled(true);
        }
    }

    /**
     * 水/岩浆接触生成方块（圆石/石头/黑曜石等）：记为本局生成物，
     * 使其可被镐拆除，并在对局结束时统一清理，不污染地图。
     *
     * <p>同时把**被反应掉的原方块**（通常是岩浆源）记进 removedLiquids：
     * 否则终局只把它清成空气，地图上会永久少一格岩浆（留下空气坑）。
     * 这样终局的顺序正好是「先清空、再还原成岩浆」。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        CeasefireGame game = CeasefireGame.gameInWorld(event.getBlock().getWorld());
        if (game == null) {
            return;
        }
        Block block = event.getBlock();
        Material previous = block.getType(); // 方块尚未改变：此时仍是岩浆/水
        // 与「装走 / 顶掉」两条路径保持同一规则：只记**源**方块，流动液体不还原。
        if ((previous == Material.LAVA || previous == Material.WATER)
                && isLiquidSource(block.getBlockData())) {
            game.getBlockTracker().markRemovedLiquid(block, previous);
        }
        game.getBlockTracker().markPlaced(block);
    }

    /** 火焰烧毁方块：自然方块禁止烧毁；玩家放置/生成的方块允许（终局会清理）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        CeasefireGame game = CeasefireGame.gameInWorld(event.getBlock().getWorld());
        if (game != null && !game.getBlockTracker().isPlayerPlaced(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** 火焰蔓延：只允许在玩家放置/生成的方块上蔓延，保护自然地形。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE) {
            return;
        }
        CeasefireGame game = CeasefireGame.gameInWorld(event.getBlock().getWorld());
        if (game != null && !game.getBlockTracker().isPlayerPlaced(event.getBlock())) {
            event.setCancelled(true);
        }
    }
}
