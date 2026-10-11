package com.mczju.ceasefire.utils;

import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * 音效中心：本插件全部游戏音效的统一定义与播放入口。
 * 事件方法按游戏节点命名，音量/音调集中维护，避免音效散落在各处。
 */
public final class SoundUtil {

    private SoundUtil() {
    }

    /** 向单个玩家播放（以玩家当前位置为声源）。 */
    public static void play(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** 向一组玩家逐一播放（各自位置）。 */
    public static void playAll(Collection<PlayerExt> players, Sound sound, float volume, float pitch) {
        for (PlayerExt player : players) {
            play(player.player(), sound, volume, pitch);
        }
    }

    // ================= 准备阶段 =================

    /** 准备阶段开始（弹出选职业界面）。 */
    public static void prepStart(Collection<PlayerExt> players) {
        playAll(players, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
    }

    /** 确认选择职业。 */
    public static void professionChosen(Player player) {
        play(player, Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
    }

    // ================= 倒计时与开局 =================

    /** 开局倒计时每秒滴答（音调随剩余秒数升高）。 */
    public static void countdownTick(Collection<PlayerExt> players, int remaining) {
        playAll(players, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f + (5 - remaining) * 0.15f);
    }

    /** 游戏正式开始（发放开局物资）。 */
    public static void gameBegin(Collection<PlayerExt> players) {
        playAll(players, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
    }

    // ================= 资源与合成 =================

    /** 解锁一个配方（知识之书 / 三选一确认）。 */
    public static void recipeUnlocked(Player player) {
        play(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
    }

    /** 打开配方三选一菜单（翻书）。 */
    public static void recipeChoiceOpen(Player player) {
        play(player, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
    }

    /** 合成出咬人斧。 */
    public static void biteAxeCreated(Player player) {
        play(player, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.4f);
    }

    /** 咬人斧命中，消耗一次使用次数。 */
    public static void biteAxeHit(Player player) {
        play(player, Sound.ENTITY_RAVAGER_ATTACK, 1.0f, 1.0f);
    }

    /** 咬人斧次数耗尽碎裂消失。 */
    public static void biteAxeBroken(Player player) {
        play(player, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
    }

    // ================= 战斗与生死 =================

    /** 击杀一名敌人（对击杀者播放）。 */
    public static void killed(Player killer) {
        play(killer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    /** 被击倒，进入复活等待。 */
    public static void knockedDown(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
    }

    /** 复活成功。 */
    public static void revived(Player player) {
        play(player, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.5f);
    }

    /** 被淘汰出局（决战阵亡 / 团灭 / 退出）。 */
    public static void eliminated(Player player) {
        play(player, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.8f);
    }

    /** 某队团灭（向局内全体播放）。 */
    public static void teamWiped(Collection<PlayerExt> players) {
        playAll(players, Sound.ENTITY_WITHER_DEATH, 0.6f, 0.8f);
    }

    /** 获得胜利（向局内全体播放）。 */
    public static void victory(Collection<PlayerExt> players) {
        playAll(players, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    // ================= 时间上限与决战时刻 =================

    /** 距离决战时刻的时间警告（urgent=最后 10 秒内，音调更高）。 */
    public static void timeWarning(Collection<PlayerExt> players, boolean urgent) {
        playAll(players, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, urgent ? 1.6f : 1.0f);
    }

    /** 决战前最后 5 秒倒计（音调随秒数升高）。 */
    public static void finalCountdown(Collection<PlayerExt> players, int remaining) {
        playAll(players, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.0f + (5 - remaining) * 0.2f);
    }

    /** 决战时刻开始。 */
    public static void suddenDeath(Collection<PlayerExt> players) {
        playAll(players, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.8f);
    }
}
