package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.mczju.ceasefire.game.menu.TeamSelectMenu;
import com.mczju.ceasefire.item.TeamSelectItem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 「选择队伍」物品的行为：右键打开选队菜单，并把它**固定在快捷栏第 5 格**。
 *
 * <p>固定逻辑完全参照 MGC 自身的 {@code LobbyMenuClock}（把菜单钟固定在快捷栏第 9 格）：
 * 同一套事件拦截，只是槽位不同。
 */
public final class TeamSelectListener implements Listener {

    @EventHandler
    public void onUseTeamItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() == Action.PHYSICAL
                || !TeamSelectItem.isTeamItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        TeamSelectItem.ensureInFixedSlot(event.getPlayer());
        openMenu(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !TeamSelectItem.isTeamItem(event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        TeamSelectItem.ensureInFixedSlot(event.getPlayer());
        openMenu(event.getPlayer());
    }

    /** 固定：不允许点击移动、数字键换位、换到副手。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean clicked = TeamSelectItem.isTeamItem(event.getCurrentItem())
                || TeamSelectItem.isTeamItem(event.getCursor());
        boolean numberKeySwap = event.getClick() == ClickType.NUMBER_KEY
                && TeamSelectItem.isTeamItem(player.getInventory().getItem(event.getHotbarButton()));
        boolean offHandSwap = event.getClick() == ClickType.SWAP_OFFHAND
                && TeamSelectItem.isTeamItem(player.getInventory().getItemInOffHand());

        if (!clicked && !numberKeySwap && !offHandSwap) {
            return;
        }
        event.setCancelled(true);
    }

    /** 固定：不允许拖拽。 */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (TeamSelectItem.isTeamItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    /** 固定：不允许丢弃。 */
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (TeamSelectItem.isTeamItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** 固定：不允许 F 键换手。 */
    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (TeamSelectItem.isTeamItem(event.getMainHandItem())
                || TeamSelectItem.isTeamItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    /** 固定：不允许从创造模式物品栏取出副本。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreativeInventoryClick(InventoryCreativeEvent event) {
        if (TeamSelectItem.isTeamItem(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    /** 只能在等待房间内打开选队菜单。 */
    private static void openMenu(Player player) {
        AbstractGame game = new PlayerExt(player).getGame();
        if (game instanceof CeasefireGame ceasefire) {
            new TeamSelectMenu(player, ceasefire).open();
        }
    }
}
