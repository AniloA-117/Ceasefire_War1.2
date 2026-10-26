package com.mczju.ceasefire.item;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.item.MGCItem;
import com.github.mczjuops.mczjugamecore.utils.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 等待阶段发给玩家的「选择队伍」物品：右键打开选队菜单，并**固定在快捷栏第 5 格**。
 *
 * <p>通过 MGC 的 {@code ItemManager} 以 PDC 打标记辨识，不依赖材质比对，
 * 因此不会与职业配方里的同材质物品混淆。
 *
 * <p>固定槽位的做法参照 MGC 自身的 {@code LobbyMenuClock}（把菜单钟固定在快捷栏第 9 格）。
 */
public final class TeamSelectItem extends MGCItem {

    /** 注册 ID，用于 ItemManager#register、PlayerExt#giveItem 与辨识。 */
    public static final String ID = "ceasefire:team_select";

    /** 固定槽位（0 基下标）：4 = 快捷栏第 5 格。 */
    public static final int FIXED_SLOT = 4;

    @Override
    protected @NotNull ItemStack createRawItem() {
        return ItemBuilder.of(Material.NETHER_STAR)
                .customName("<gold><b>选择队伍</b>")
                .lore(List.of(
                        "<gray>右键打开选队菜单",
                        "<gray>或使用 <white>/ceasefire team <颜色>",
                        "",
                        "<dark_gray>未选择时，开局将随机分配"))
                .maxStackSize(1)
                .build();
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    /** 该物品是否为选队物品。 */
    public static boolean isTeamItem(@Nullable ItemStack item) {
        return MCZJUGameCore.getItemManager().is(item, ID);
    }

    /**
     * 把选队物品归位到快捷栏第 5 格。
     *
     * <p>先清除背包中所有副本（遍历 {@link PlayerInventory#getSize()}，含 4 个护甲格与副手），
     * 再在固定槽位放一个新的；该槽位原有物品转到空位，放不下则掉落到玩家脚下。
     */
    public static void ensureInFixedSlot(@NotNull Player player) {
        ItemStack fresh = MCZJUGameCore.getItemManager().getItem(ID);
        if (fresh == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack displaced = inventory.getItem(FIXED_SLOT);

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isTeamItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }

        inventory.setItem(FIXED_SLOT, fresh);

        if (displaced == null || displaced.getType().isAir() || isTeamItem(displaced)) {
            return;
        }
        inventory.addItem(displaced).values().forEach(item
                -> player.getWorld().dropItem(player.getLocation(), item));
    }
}
