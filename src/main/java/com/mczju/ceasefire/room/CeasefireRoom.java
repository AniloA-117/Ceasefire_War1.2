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

    /** 矿物生成间隔（秒）。 */
    @FieldDescription("矿物生成间隔（秒）")
    public int mineralIntervalSeconds = 10;

    /** 铁锭生成权重。 */
    @FieldDescription("铁锭生成权重")
    public int ironWeight = 70;

    /** 金锭生成权重。 */
    @FieldDescription("金锭生成权重")
    public int goldWeight = 25;

    /** 钻石生成权重。 */
    @FieldDescription("钻石生成权重")
    public int diamondWeight = 5;

    /** 知识之书生成点（旧版单点字段；建议改用 bookSpawnPoints）。 */
    @FieldDescription({"知识之书生成点（旧）", "单个知识之书生成点（兼容旧配置）", "新配置请使用 bookSpawnPoints"})
    public Location bookSpawnPoint;

    /** 知识之书生成点（可多个，每个点每个间隔掉落 1 本）。 */
    @FieldDescription({"知识之书生成点（多个）", "每个点每个间隔掉落1本", "可与旧字段 bookSpawnPoint 同时使用"})
    public List<Location> bookSpawnPoints = new ArrayList<>();

    /** 知识之书生成间隔（秒）。 */
    @FieldDescription("知识之书生成间隔（秒）")
    public int bookIntervalSeconds = 20;

    /** 出生点（4 个；队伍数不足 4 时随机取其中 3 个）。 */
    @FieldDescription({"出生点", "4 个出生点", "队伍数不足 4 时随机取其中 3 个"})
    public List<Location> spawnPoints = new ArrayList<>();
}
