package com.mczju.ceasefire.profession;

import org.bukkit.Material;

import java.util.List;

/**
 * 三种职业。每个职业只能合成自己对应材质的装备；原料由原版配方天然限定。
 * 镐类已加入配方池：镐用于拆除玩家建造的防御（自然方块仍受保护）。
 */
public enum Profession {

    /** 钻石/重甲/后期：钻石斧/剑/镐 + 钻石甲一套（共 7 种），附魔 15 级；合成概率获得末影珍珠。 */
    DIAMOND("钻石", 15, List.of(
            Material.DIAMOND_AXE, Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS
    )),

    /** 金/颗秒/前期：金斧/剑/镐 + 金甲一套（共 7 种），附魔 30 级；斧子有概率变咬人斧。 */
    GOLD("金", 30, List.of(
            Material.GOLDEN_AXE, Material.GOLDEN_SWORD, Material.GOLDEN_PICKAXE,
            Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE,
            Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS
    )),

    /** 铁/功能性/中后期：铁斧/剑/镐 + 铁甲一套 + 铁桶、盾、剪刀（共 10 种），附魔 10 级。 */
    IRON("铁", 10, List.of(
            Material.IRON_AXE, Material.IRON_SWORD, Material.IRON_PICKAXE,
            Material.IRON_HELMET, Material.IRON_CHESTPLATE,
            Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.BUCKET, Material.SHIELD, Material.SHEARS
    ));

    private final String displayName;
    private final int enchantLevel;
    private final List<Material> recipes;

    Profession(String displayName, int enchantLevel, List<Material> recipes) {
        this.displayName = displayName;
        this.enchantLevel = enchantLevel;
        this.recipes = recipes;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getEnchantLevel() {
        return enchantLevel;
    }

    /** 该职业的配方池。 */
    public List<Material> getRecipes() {
        return recipes;
    }

    /** 该 Material 是否属于任一职业配方（合成校验用）。 */
    public static boolean isProfessionRecipe(Material material) {
        for (Profession p : values()) {
            if (p.recipes.contains(material)) {
                return true;
            }
        }
        return false;
    }
}
