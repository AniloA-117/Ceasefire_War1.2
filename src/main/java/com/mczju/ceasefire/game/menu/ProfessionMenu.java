package com.mczju.ceasefire.game.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.utils.ItemBuilder;
import com.mczju.ceasefire.game.CeasefireGame;
import com.mczju.ceasefire.profession.Profession;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** 开局职业选择菜单（钻石/铁/金三选一）。 */
public final class ProfessionMenu extends Menu {

    private final CeasefireGame game;

    public ProfessionMenu(Player player, CeasefireGame game) {
        super(player);
        this.game = game;
    }

    @Override
    protected String getTitle() {
        return "选择职业";
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
        setSlot(2, ItemBuilder.of(Material.DIAMOND)
                .customName("<aqua>钻石（重甲）")
                .lore(List.of("<gray>15级附魔，合成有概率获得末影珍珠"))
                .build(), (p, e) -> choose(p, Profession.DIAMOND));
        setSlot(4, ItemBuilder.of(Material.IRON_INGOT)
                .customName("<white>铁（功能）")
                .lore(List.of("<gray>可合成盾、铁桶、剪刀"))
                .build(), (p, e) -> choose(p, Profession.IRON));
        setSlot(6, ItemBuilder.of(Material.GOLD_INGOT)
                .customName("<gold>金（颗秒）")
                .lore(List.of("<gray>30级附魔，斧子有概率变咬人斧"))
                .build(), (p, e) -> choose(p, Profession.GOLD));
    }

    private void choose(PlayerExt player, Profession profession) {
        game.setProfession(player, profession);
        player.player().closeInventory();
    }
}
