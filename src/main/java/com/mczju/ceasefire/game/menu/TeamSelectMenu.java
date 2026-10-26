package com.mczju.ceasefire.game.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.utils.ItemBuilder;
import com.mczju.ceasefire.game.CeasefireGame;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 等待阶段「选择队伍」菜单：4 支队伍各占一格，显示当前成员，点击加入。
 *
 * <p>队伍数由本局人数决定，超出当前队伍数的格子显示为灰色不可选。
 */
public final class TeamSelectMenu extends Menu {

    /** 4 支队伍对应的槽位（1 行 9 格）。 */
    private static final int[] SLOTS = {1, 3, 5, 7};

    /** 与 CeasefireGame#teamName 顺序一致的队伍图标。 */
    private static final Material[] ICONS = {
            Material.RED_WOOL,
            Material.BLUE_WOOL,
            Material.YELLOW_WOOL,
            Material.GREEN_WOOL
    };

    private final CeasefireGame game;

    public TeamSelectMenu(Player player, CeasefireGame game) {
        super(player);
        this.game = game;
    }

    @Override
    protected String getTitle() {
        return "选择队伍";
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
        int active = game.getActiveTeamCount();
        if (active <= 0) {
            setSlot(4, ItemBuilder.of(Material.BARRIER)
                    .customName("<red>暂时无法选择队伍")
                    .lore(List.of(
                            "<gray>当前人数还不足以确定队伍数",
                            "<gray>等待更多玩家加入后再试"))
                    .build());
            return;
        }

        for (int team = 0; team < SLOTS.length; team++) {
            if (team >= active) {
                setSlot(SLOTS[team], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                        .customName("<dark_gray>未启用的队伍")
                        .lore(List.of("<gray>本局只有 <white>%d</white> 支队伍".formatted(active)))
                        .build());
                continue;
            }
            final int selected = team;
            setSlot(SLOTS[team], buildTeamIcon(team), (p, event) -> {
                game.selectTeam(p, selected);
                p.player().closeInventory();
            });
        }
    }

    private ItemStack buildTeamIcon(int team) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>当前成员：");
        List<PlayerExt> members = game.getTeamPlayers(team);
        if (members.isEmpty()) {
            lore.add("<dark_gray>  （暂无）");
        } else {
            for (PlayerExt member : members) {
                lore.add("<white>  " + member.getName());
            }
        }
        lore.add("");
        lore.add("<gray>人数：<white>%d</white>/<white>%d</white>".formatted(
                members.size(), game.teamCapacity()));
        if (game.getTeam(player) == team) {
            lore.add("<green>你已在这支队伍");
        } else if (members.size() >= game.teamCapacity()) {
            lore.add("<red>该队已满");
        } else {
            lore.add("<yellow>点击加入该队");
        }

        return ItemBuilder.of(ICONS[team])
                .customName("<white><b>" + CeasefireGame.teamName(team) + "</b>")
                .lore(lore)
                .glint(game.getTeam(player) == team)
                .build();
    }
}
