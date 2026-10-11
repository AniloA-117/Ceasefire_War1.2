package com.mczju.ceasefire.block;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.mczju.ceasefire.game.CeasefireGame;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;

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
        if (game instanceof CeasefireGame arena) {
            arena.getBlockTracker().markPlaced(event.getBlock());
        }
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
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        CeasefireGame game = CeasefireGame.gameInWorld(event.getBlock().getWorld());
        if (game != null) {
            game.getBlockTracker().markPlaced(event.getBlock());
        }
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
