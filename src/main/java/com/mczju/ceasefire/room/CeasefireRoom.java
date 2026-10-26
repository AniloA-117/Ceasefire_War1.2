package com.mczju.ceasefire.room;

import com.github.mczjuops.mczjugamecore.game.room.FieldDescription;
import com.github.mczjuops.mczjugamecore.game.room.JsonGameRoom;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * 停战之战房间。public 字段会被 MGC 的房间编辑菜单(/mgcop room edit)直接编辑并保存到 JSON。
 */
public class CeasefireRoom extends JsonGameRoom {

    /** 缩圈中心（世界 + 坐标）。null = 不缩圈。 */
    @FieldDescription({"缩圈中心", "世界边界收缩到的中心点", "未设置则不缩圈"})
    public Location borderCenter;

    /** 初始边界直径（方块）。 */
    @FieldDescription("初始边界直径（方块）")
    public double borderInitialSize = 200;

    /** 最终边界直径（方块）。 */
    @FieldDescription("最终边界直径（方块）")
    public double borderFinalSize = 40;

    /** 缩圈持续秒数（从初始直径平滑缩到最终直径）。 */
    @FieldDescription("缩圈持续秒数")
    public int borderShrinkSeconds = 180;

    /** 开局后延迟多少秒开始缩圈。 */
    @FieldDescription("开局后延迟多少秒开始缩圈")
    public int borderStartDelaySeconds = 30;

    /** 对局时间上限（秒）；0 = 不限制。到时进入决战时刻。 */
    @FieldDescription({"对局时间上限（秒）", "0 = 不限制；到时进入决战时刻：关闭复活+强制缩圈"})
    public int timeLimitSeconds = 900;

    /** 决战时刻：边界从当前大小缩到最终大小所用的秒数。 */
    @FieldDescription({"决战缩圈秒数", "决战时刻边界缩到最终大小所用时间"})
    public int suddenDeathShrinkSeconds = 60;

    /** 队伍数（2~4 队）。 */
    @FieldDescription({"队伍数", "2~4 队，默认 3"})
    public int teamCount = 3;

    /** 每队咬人斧数量上限（1~2）。 */
    @FieldDescription({"咬人斧上限", "每队同时最多存在多少把咬人斧", "1~2"})
    public int biteAxeLimit = 1;

    /** 开局木板数量。 */
    @FieldDescription("开局木板数量")
    public int startPlanks = 16;

    /** 开局木棍数量。 */
    @FieldDescription("开局木棍数量")
    public int startSticks = 16;

    /** 开局羊毛数量。 */
    @FieldDescription("开局羊毛数量")
    public int startWool = 32;

    /** 开局发放的工作台数量。 */
    @FieldDescription("开局工作台数量")
    public int startCraftingTables = 1;

    /** 开局发放的金苹果数量（每局仅此一次，复活不再发放）。 */
    @FieldDescription({"开局金苹果数量", "每局仅发放一次，复活不再发放", "0 = 不发放"})
    public int startGoldenApples = 1;

    /** 金胡萝卜数量：开局与每次复活都会发放。 */
    @FieldDescription({"金胡萝卜数量", "开局与每次复活都会发放", "0 = 不发放"})
    public int goldenCarrots = 3;

    /** 知识之书是否三选一（true=随机3个未解锁配方中自选1个；false=完全随机1个）。 */
    @FieldDescription({"知识之书三选一", "true=随机3个未解锁配方中自选1个", "false=完全随机解锁1个"})
    public boolean bookChooseThree = true;

    /** 钻石职业合成职业配方时获得末影珍珠的概率（%）。 */
    @FieldDescription("钻石职业合成获得末影珍珠概率（%）")
    public int enderPearlChancePercent = 15;

    /** 金职业合成金斧变咬人斧的概率（%）。 */
    @FieldDescription("金斧变咬人斧概率（%）")
    public int biteAxeChancePercent = 10;

    /** 矿物生成点（旧版单点字段；建议改用 mineralSpawnPoints）。 */
    @FieldDescription({"矿物生成点（旧）", "单个矿物生成点（兼容旧配置）", "新配置请使用 mineralSpawnPoints"})
    public Location mineralSpawnPoint;

    /** 矿物生成点（可多个，每个点每个间隔掉落 1 个矿物）。 */
    @FieldDescription({"矿物生成点（多个）", "每个点每个间隔掉落1个矿物", "可与旧字段 mineralSpawnPoint 同时使用"})
    public List<Location> mineralSpawnPoints = new ArrayList<>();

    /** 第二类矿物生成点（仅在队伍数为 4 时叠加生效，第一类照常生效）。 */
    @FieldDescription({"第二类矿物生成点", "仅在本局队伍数为 4 时额外生效（叠加）", "每个点每个间隔掉落1个矿物"})
    public List<Location> mineralSpawnPoints2 = new ArrayList<>();

    /** 矿物生成间隔（秒）。 */
    @FieldDescription("矿物生成间隔（秒）")
    public int mineralIntervalSeconds = 4;

    /** 铁锭生成权重。 */
    @FieldDescription("铁锭生成权重")
    public int ironWeight = 50;

    /** 金锭生成权重。 */
    @FieldDescription("金锭生成权重")
    public int goldWeight = 30;

    /** 钻石生成权重。 */
    @FieldDescription("钻石生成权重")
    public int diamondWeight = 20;

    /** 知识之书生成点（旧版单点字段；建议改用 bookSpawnPoints）。 */
    @FieldDescription({"知识之书生成点（旧）", "单个知识之书生成点（兼容旧配置）", "新配置请使用 bookSpawnPoints"})
    public Location bookSpawnPoint;

    /** 知识之书生成点（可多个，每个点每个间隔掉落 1 本）。 */
    @FieldDescription({"知识之书生成点（多个）", "每个点每个间隔掉落1本", "可与旧字段 bookSpawnPoint 同时使用"})
    public List<Location> bookSpawnPoints = new ArrayList<>();

    /** 第二类知识之书生成点（仅在队伍数为 4 时叠加生效，第一类照常生效）。 */
    @FieldDescription({"第二类知识之书生成点", "仅在本局队伍数为 4 时额外生效（叠加）", "每个点每个间隔掉落1本"})
    public List<Location> bookSpawnPoints2 = new ArrayList<>();

    /** 知识之书生成间隔（秒）。 */
    @FieldDescription("知识之书生成间隔（秒）")
    public int bookIntervalSeconds = 20;

    /** 出生点（4 个；队伍数不足 4 时随机取其中 3 个）。 */
    @FieldDescription({"出生点", "4 个出生点", "队伍数不足 4 时随机取其中 3 个"})
    public List<Location> spawnPoints = new ArrayList<>();

    /**
     * 正式开局（准备阶段结束、发放开局物品前）设置的玩家游戏模式。
     *
     * <p>大厅档案默认把玩家设为 ADVENTURE，MGC 切到游戏档案时**不会**改游戏模式，
     * 因此必须由本局显式设置，否则玩家无法放置/拆除方块。留空 = 不干预。
     */
    @FieldDescription({"开局游戏模式", "SURVIVAL / ADVENTURE / CREATIVE", "留空 = 不干预（沿用当前模式）"})
    public String gameMode = "SURVIVAL";

    /**
     * 对局结束（含中途退出）时是否清空物品栏 / 护甲 / 副手与末影箱。
     *
     * <p>MGC 会在玩家离开对局时把当前物品栏存进该游戏的档案快照，且该快照从不删除；
     * 在**结束时**清空，这份快照就是空的，下一局才不会被上一局的装备污染。
     */
    @FieldDescription({"结束清空物品栏", "true = 对局结束或中途退出时清空物品栏/护甲/副手/末影箱",
            "防止装备被存进游戏档案、带进下一局"})
    public boolean clearInventoryOnEnd = true;

    /**
     * 开局（`/mgc start`）时是否清空物品栏与末影箱。
     *
     * <p>与 {@link #clearInventoryOnEnd} 配合使用：结束清一次，防止本局装备被带进下一局；
     * 开局再清一次，兜住**升级前遗留的游戏档案快照**（那份快照里可能还存着旧装备）。
     */
    @FieldDescription({"开局清空物品栏", "true = 开局时清空物品栏与末影箱", "兜住升级前遗留的游戏档案快照"})
    public boolean clearInventoryOnStart = true;

    /** 对局结束时是否把世界边界还原成开局前的状态（否则该世界会一直停在本局的边界）。 */
    @FieldDescription({"结束还原世界边界", "true = 结束后还原开局前的边界大小/中心", "false = 保留本局最后缩到的边界"})
    public boolean restoreBorderOnEnd = true;

    /** 结束清理掉落物时的半径：0 = 清理整个世界（竞技场为独立维度时推荐）；>0 = 只清理缩圈中心周围该半径内。 */
    @FieldDescription({"掉落物清理半径", "0 = 清理整个世界（竞技场独立维度时推荐）", ">0 = 只清理缩圈中心周围该半径内"})
    public double clearDroppedItemsRadius = 0.0D;

    /** 对局结束/中止时是否把局内玩家传送回主大厅（框架结束对局时不会移动玩家）。 */
    @FieldDescription({"结束传送回主大厅", "true = 结束/中止时把局内玩家传送到主大厅", "false = 留在原地"})
    public boolean teleportToLobbyOnEnd = true;

    /**
     * 对局结束/中止时是否清除场地内的掉落物（矿物、知识之书、玩家死亡掉落等）。
     *
     * <p>配置了缩圈中心（{@code borderCenter}）时，只清理以它为中心、初始边界半径范围内的掉落物，
     * 避免误删同一世界里其他小游戏或大厅的物品；未配置缩圈中心时清理整个世界。
     */
    @FieldDescription({"结束清理掉落物", "true = 结束/中止时清除场地内的掉落物",
            "配置缩圈中心时只清理边界范围内，否则清理整个世界"})
    public boolean clearDroppedItemsOnEnd = true;
}
