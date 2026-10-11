package com.mczju.ceasefire.player;

import com.github.mczjuops.mczjugamecore.player.data.JsonPlayerData;

/**
 * 停战之战玩家持久化数据（历史击杀/死亡，用于 KD 选队长与排行榜）。
 * public 字段会被 MGC 自动序列化保存；private 字段不保存。
 */
public class CeasefirePlayerData extends JsonPlayerData {
    /** 历史总击杀数。 */
    public int kills = 0;
    /** 历史总死亡数。 */
    public int deaths = 0;
}
