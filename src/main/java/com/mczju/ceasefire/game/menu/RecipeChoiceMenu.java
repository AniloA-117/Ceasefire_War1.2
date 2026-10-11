package com.mczju.ceasefire.game.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.utils.ItemBuilder;
import com.mczju.ceasefire.game.CeasefireGame;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 知识之书「三选一」菜单：从随机 3 个未解锁配方中自选 1 个。
 * 选中后才消耗知识之书（直接关闭不消耗）。
 */
public final class RecipeChoiceMenu extends Menu {

    /** 三个选项的展示槽位。 */
    private static final int[] OPTION_SLOTS = {2, 4, 6};

    private final CeasefireGame game;
    private final List<Material> options;

    public RecipeChoiceMenu(Player player, CeasefireGame game, List<Material> options) {
        super(player);
        this.game = game;
        this.options = options;
    }

    @Override
    protected String getTitle() {
        return "选择合成配方";
    }

    @Override
    protected int getRows() {
        return 1;
    }

    @Override
    protected String getPermission() {
        return "mgc.mgc";
    }

    @Override
    public void setup() {
        for (int i = 0; i < options.size() && i < OPTION_SLOTS.length; i++) {
            Material material = options.get(i);
            setSlot(OPTION_SLOTS[i], ItemBuilder.of(material)
                    .lore(List.of("<gray>点击解锁该配方", "<dark_gray>（解锁后本局永久有效）"))
                    .build(), (p, e) -> choose(p, material));
        }
    }

    private void choose(PlayerExt player, Material material) {
        game.chooseRecipe(player, material);
        player.player().closeInventory();
    }
}
