package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.utils.TextParser;
import com.mczju.ceasefire.CeasefireWar;
import com.mczju.ceasefire.profession.Profession;
import com.mczju.ceasefire.room.CeasefireRoom;
import com.mczju.ceasefire.utils.SoundUtil;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 游戏玩法监听：知识之书解锁 + 合成校验 + 原版附魔 + 咬人斧（金斧）+ 末影珍珠。
 */
public final class GamePlayListener implements Listener {

    private static final String BITE_AXE_USES_KEY = "bite_axe_uses";

    private static NamespacedKey biteAxeKey() {
        return new NamespacedKey(CeasefireWar.getInstance(), BITE_AXE_USES_KEY);
    }

    /** 右键知识之书：按房间配置随机解锁或三选一；是否消耗一本由使用结果决定。 */
    @EventHandler
    public void onUseBook(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.KNOWLEDGE_BOOK) {
            return;
        }
        AbstractGame game = new PlayerExt(player).getGame();
        if (!(game instanceof CeasefireGame arena)) {
            return;
        }
        event.setCancelled(true);
        // 三选一模式下由菜单选择后再消耗（chooseRecipe 内部扣除）。
        CeasefireGame.BookUseResult result = arena.useKnowledgeBook(new PlayerExt(player));
        if (result == CeasefireGame.BookUseResult.CONSUMED) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    /** 合成校验 + 原版附魔 + 咬人斧 + 末影珍珠。 */
    @EventHandler
    public void onCraft(CraftItemEvent event) {
        Player player = (Player) event.getWhoClicked();
        PlayerExt ext = new PlayerExt(player);
        AbstractGame game = ext.getGame();
        if (!(game instanceof CeasefireGame arena)) {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        if (result == null) {
            return;
        }
        Material type = result.getType();
        Profession prof = arena.getProfession(ext);

        // 配方校验
        if (Profession.isProfessionRecipe(type) && !arena.hasRecipe(ext, type)) {
            event.setCancelled(true);
            player.sendMessage(TextParser.parse("<red>你尚未解锁该配方</red>"));
            return;
        }
        if (prof == null || !Profession.isProfessionRecipe(type)) {
            return; // 非职业配方，不处理。
        }

        boolean madeBiteAxe = false;
        CeasefireRoom room = (CeasefireRoom) arena.getGameRoom();
        // 金职业合成金斧：按房间概率变咬人斧（该队未达上限时）。
        if (prof == Profession.GOLD && type == Material.GOLDEN_AXE
                && ThreadLocalRandom.current().nextInt(100) < room.biteAxeChancePercent) {
            if (countTeamBiteAxes(arena, arena.getTeam(ext)) < room.biteAxeLimit) {
                event.getInventory().setResult(makeBiteAxe());
                madeBiteAxe = true;
                player.sendMessage(TextParser.parse("<red><b>咬人斧诞生了！</b></red> <gray>攻击时消耗次数</gray>"));
                SoundUtil.biteAxeCreated(player);
            }
        }

        // 原版附魔库随机附魔（咬人斧已锋利99，不再随机附魔）。
        if (!madeBiteAxe) {
            ItemStack enchanted = event.getInventory().getResult();
            enchantItem(enchanted, prof.getEnchantLevel());
            event.getInventory().setResult(enchanted);
        }

        // 钻石职业：合成职业配方后概率额外获得末影珍珠。
        if (prof == Profession.DIAMOND && ThreadLocalRandom.current().nextInt(100) < room.enderPearlChancePercent) {
            Bukkit.getScheduler().runTask(CeasefireWar.getInstance(), () -> {
                if (player.isOnline()) {
                    player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
                }
            });
        }
    }

    /** 原版附魔库随机附魔：从该物品可附且互不冲突的原版附魔中随机选 1~3 个，等级 1~max。 */
    private void enchantItem(ItemStack item, int enchantLevel) {
        List<Enchantment> available = new ArrayList<>();
        for (Enchantment ench : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            if (ench.canEnchantItem(item) && !item.containsEnchantment(ench)) {
                available.add(ench);
            }
        }
        if (available.isEmpty()) {
            return;
        }
        Collections.shuffle(available);
        int count = 1 + ThreadLocalRandom.current().nextInt(3); // 1~3 个
        // 附魔台等级影响附魔等级上限：铁10/钻石15/金30 → 上限约 2/3/6。
        int cap = Math.max(1, enchantLevel / 5);
        List<Enchantment> chosen = new ArrayList<>();
        for (Enchantment ench : available) {
            if (chosen.size() >= count) {
                break;
            }
            boolean conflict = false;
            for (Enchantment picked : chosen) {
                if (ench.conflictsWith(picked)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                continue;
            }
            int max = ench.getMaxLevel();
            int lvl = Math.max(1, Math.min(max, 1 + ThreadLocalRandom.current().nextInt(cap)));
            item.addEnchantment(ench, lvl);
            chosen.add(ench);
        }
    }

    private ItemStack makeBiteAxe() {
        ItemStack axe = new ItemStack(Material.GOLDEN_AXE);
        // 锋利 99 超出原版等级上限，必须用 unsafe 附魔（addEnchantment 会抛异常）。
        axe.addUnsafeEnchantment(Enchantment.SHARPNESS, 99);
        int uses = ThreadLocalRandom.current().nextInt(1, 4); // 1~3
        ItemMeta meta = axe.getItemMeta();
        meta.displayName(TextParser.parse("<red><b>咬人斧</b></red>"));
        meta.getPersistentDataContainer().set(biteAxeKey(), PersistentDataType.INTEGER, uses);
        meta.lore(List.of(TextParser.parse("<gray>剩余使用次数：<yellow>%d</yellow>".formatted(uses))));
        axe.setItemMeta(meta);
        return axe;
    }

    private boolean isBiteAxe(ItemStack item) {
        if (item == null || item.getType() != Material.GOLDEN_AXE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(biteAxeKey(), PersistentDataType.INTEGER);
    }

    /** 统计某队玩家背包里已有的咬人斧数量。 */
    private int countTeamBiteAxes(CeasefireGame arena, int team) {
        int count = 0;
        for (PlayerExt p : arena.getTeamPlayers(team)) {
            for (ItemStack item : p.player().getInventory().getContents()) {
                if (isBiteAxe(item)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 咬人斧命中后消耗 1 次使用次数，归零删除。 */
    @EventHandler
    public void onBiteAxeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isBiteAxe(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        int uses = meta.getPersistentDataContainer().getOrDefault(biteAxeKey(), PersistentDataType.INTEGER, 0);
        uses--;
        if (uses <= 0) {
            item.setAmount(0);
            SoundUtil.biteAxeBroken(player);
        } else {
            meta.getPersistentDataContainer().set(biteAxeKey(), PersistentDataType.INTEGER, uses);
            meta.lore(List.of(TextParser.parse("<gray>剩余使用次数：<yellow>%d</yellow>".formatted(uses))));
            item.setItemMeta(meta);
            SoundUtil.biteAxeHit(player);
        }
    }

    /** 咬人斧不能挖方块。 */
    @EventHandler
    public void onBiteAxeBreak(BlockBreakEvent event) {
        if (isBiteAxe(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    /** 准备阶段禁锢玩家（禁止移动，可转头）。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        AbstractGame game = new PlayerExt(player).getGame();
        if (!(game instanceof CeasefireGame arena) || !arena.isPrepPhase()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            event.setTo(from);
        }
    }
}
