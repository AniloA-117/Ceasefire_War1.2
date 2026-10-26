package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.GameMeta;
import com.github.mczjuops.mczjugamecore.game.GameState;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.GameWaitStrategy;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.party.DisbandResult;
import com.github.mczjuops.mczjugamecore.player.party.Party;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.PlayerQuitReason;
import com.github.mczjuops.mczjugamecore.player.tab.TabManager;
import com.github.mczjuops.mczjugamecore.utils.ItemBuilder;
import com.github.mczjuops.mczjugamecore.utils.TextParser;
import com.github.mczjuops.mczjugamecore.utils.VoiceGroupUtil;
import com.mczju.ceasefire.CeasefireWar;
import com.mczju.ceasefire.block.BlockChangeTracker;
import com.mczju.ceasefire.game.menu.ProfessionMenu;
import com.mczju.ceasefire.game.menu.RecipeChoiceMenu;
import com.mczju.ceasefire.item.TeamSelectItem;
import com.mczju.ceasefire.profession.Profession;
import com.mczju.ceasefire.room.CeasefireRoom;
import com.mczju.ceasefire.utils.SoundUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 停战之战（The War of Ceasefire）：2~4 队资源收集与团队对抗（最少 2 人，可 1v1 / 1v1v1 / 2v2）。
 *
 * <p>已实现：方块记录与还原（含桶生成物）、缩圈与时间上限（决战时刻）、等待阶段自选队伍、
 * 开局物品+指南针、多资源点矿物/知识之书生成（含仅 4 队时叠加的第二类生成点）、
 * 职业/知识之书双模式/职业配方、复活与团灭、断线判负。
 *
 * <p>1.1 起：移除持久化 KD 统计与「按 KD 选队长」；移除实时击杀榜侧边栏；
 * 每局击败/死亡只保存在内存，并在对局结束时于聊天框播报。
 */
public final class CeasefireGame extends AbstractGame {

    /** 运行中的对局（按世界匹配方块事件归属），onGameStart 加入，cleanup 移除。 */
    private static final Set<CeasefireGame> ACTIVE_GAMES = ConcurrentHashMap.newKeySet();

    private final BlockChangeTracker blockTracker = new BlockChangeTracker();

    /** 本局所在世界（运行时字段，不序列化）。 */
    private World gameWorld;

    /** 开局前该世界边界的状态，用于对局结束（cleanup）时还原；未改动过边界时为 false。 */
    private boolean borderTouched;
    private World borderWorld;
    private double originalBorderSize;
    private Location originalBorderCenter;
    private int originalBorderWarningDistance;
    private int originalBorderWarningTicks;

    /** 每队独立语音组 ID（安装 Simple Voice Chat 时创建），终局统一移除。 */
    private final List<UUID> voiceGroupIds = new ArrayList<>();

    private BukkitTask borderTask;
    private BukkitTask compassTask;
    private BukkitTask mineralTask;
    private BukkitTask bookTask;
    private BukkitTask timeLimitTask;
    private BukkitTask finalGlowTask;

    /** 时间上限 BossBar（时间上限关闭时为 null）。 */
    private BossBar timeBossBar;

    /** 玩家 UUID -> 队伍编号（0 ~ teamCount-1）。 */
    private final Map<UUID, Integer> teamMap = new HashMap<>();

    /** 队伍编号 -> 该队开局出生点。 */
    private final Map<Integer, Location> teamSpawnPoints = new HashMap<>();

    /** 玩家 UUID -> 职业。 */
    private final Map<UUID, Profession> professions = new HashMap<>();

    /** 玩家 UUID -> 已解锁配方（单局，死亡不掉落）。 */
    private final Map<UUID, Set<Material>> unlockedRecipes = new HashMap<>();

    /** 玩家 UUID -> 复活时间点（毫秒）。 */
    private final Map<UUID, Long> revivingPlayers = new HashMap<>();

    /** 永久出局（决战阵亡 / 团灭 / 退出）的玩家 UUID，不再复活。 */
    private final Set<UUID> eliminated = new HashSet<>();

    /** 死亡事件被取消（对局正好被这一死终结）的玩家 UUID —— 他们已经活着，不能再调 respawn。 */
    private final Set<UUID> cancelledDeaths = new HashSet<>();

    /** 是否处于决战时刻（时间上限到达后：关闭复活，死亡即淘汰）。 */
    private boolean suddenDeath = false;

    /** 是否已触发终局结算（防止重复结算）。 */
    private boolean finished = false;

    /** 已团灭的队伍编号集合。 */
    private final Set<Integer> wipedTeams = new HashSet<>();

    /** 复活倒计时任务句柄。 */
    private BukkitTask reviveTask;

    /** 单局击败数（玩家 UUID -> 击败数），仅存内存，对局结束时播报。 */
    private final Map<UUID, Integer> roundKills = new HashMap<>();

    /** 单局死亡数（玩家 UUID -> 死亡数），仅存内存，对局结束时播报。 */
    private final Map<UUID, Integer> roundDeaths = new HashMap<>();

    /** 开局时间戳（复活时间随对局延长）。 */
    private long startTime;

    private static final String[] TEAM_PREFIXES = {
            "<red>[红队]</red> ",
            "<blue>[蓝队]</blue> ",
            "<yellow>[黄队]</yellow> ",
            "<green>[绿队]</green> "
    };

    /** 人数 -> 队伍数：2=1v1, 3=1v1v1, 4=2v2, 6=3v3, 8=2v2v2v2, 9=3v3v3, 10=5v5, 12=3v3v3v3；5/7/11 沿用上一次有效队伍数，其余人数禁止开局。 */
    private static final Map<Integer, Integer> PLAYER_COUNT_TO_TEAMS = Map.of(
            2, 2,
            3, 3,
            4, 2,
            6, 2,
            8, 4,
            9, 3,
            10, 2,
            12, 4
    );

    /** 本局实际队伍数（等待阶段随人数变化重算）。 */
    private int activeTeamCount = 0;

    /** 上一次由「有效人数」算出的队伍数；5/7/11 人时沿用该值。 */
    private int lastValidTeamCount = 0;

    /** 本局是否已发放过开局物品（金苹果每局仅可获得一次）。 */
    private boolean startItemsGranted = false;

    /** 准备阶段是否进行中（禁锢玩家 + 选职业）。 */
    private boolean prepActive = false;

    /** 准备阶段开始时间戳。 */
    private long prepStartTime;

    /** 准备阶段检查任务。 */
    private BukkitTask prepTask;

    /** 5 秒倒计时任务。 */
    private BukkitTask countdownTask;

    public CeasefireGame() {
    }

    @Override
    public @NotNull String getId() {
        return "ceasefire";
    }

    @Override
    public @NotNull GameMeta getGameMeta() {
        return GameMeta.builder()
                .displayName("<gold>止戈之战</gold>")
                .icon(Material.IRON_SPEAR)
                .author("<green>AniloA && LRY314</green>")
                .description(List.of(
                        "<aqua>2~4 队资源收集与团队对抗（最少 2 人）",
                        "<yellow>收集资源、解锁配方、合成装备",
                        "<red>缩圈决战，直至最后一方胜出"
                ))
                .build();
    }

    @Override
    public @NotNull GameWaitStrategy getGameWaitStrategy() {
        return new CeasefireWaitStrategy(this);
    }

    /** 该人数能否开局。 */
    public static boolean isValidPlayerCount(int count) {
        return PLAYER_COUNT_TO_TEAMS.containsKey(count);
    }

    /** 该人数对应的队伍数，不能开局返回 -1。 */
    public static int getTeamCountForPlayers(int count) {
        return PLAYER_COUNT_TO_TEAMS.getOrDefault(count, -1);
    }

    @Override
    public @NotNull AbstractPlayerDeathStrategy getPlayerDeathStrategy() {
        return new CeasefireDeathStrategy(this);
    }

    /** 中途退出：判负出局、比赛继续（MGC 默认策略会中止整局，必须覆盖）。 */
    @Override
    public @NotNull AbstractPlayerQuitStrategy getPlayerQuitStrategy() {
        return new CeasefireQuitStrategy(this);
    }

    @Override
    protected boolean onGameInit() {
        // 房间准备校验：分配到的房间必须是本游戏房间类型，且配置了出生点（否则无法传送/复活）。
        // 缩圈中心、矿物生成点、知识之书生成点均为可选，缺失时对应玩法自动跳过。
        if (!(getGameRoom() instanceof CeasefireRoom room)) {
            logger.warn("初始化失败：分配到的房间不是 CeasefireRoom");
            return false;
        }
        if (room.spawnPoints == null || room.spawnPoints.isEmpty()) {
            logger.warn("初始化失败：房间未配置出生点 spawnPoints");
            return false;
        }
        return true;
    }

    @Override
    protected void onGameStart() {
        startTime = System.currentTimeMillis();
        finalizeTeams();
        clearInventoriesOnStart();
        assignTeamSpawns();
        teleportTeamsToSpawns();
        gameWorld = resolveGameWorld();
        ACTIVE_GAMES.add(this);
        startVoiceGroups();
        startPrepPhase();
    }

    /** 本局所在世界：优先缩圈中心，其次首个出生点。 */
    private World resolveGameWorld() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (room.borderCenter != null && room.borderCenter.getWorld() != null) {
            return room.borderCenter.getWorld();
        }
        if (!room.spawnPoints.isEmpty()) {
            return room.spawnPoints.get(0).getWorld();
        }
        return null;
    }

    /** 按世界查找运行中的对局（用于方块事件归属；同世界多局时返回先注册的一局）。 */
    public static CeasefireGame gameInWorld(World world) {
        if (world == null) {
            return null;
        }
        for (CeasefireGame game : ACTIVE_GAMES) {
            if (world.equals(game.gameWorld)) {
                return game;
            }
        }
        return null;
    }

    @Override
    protected void onGameCancel() {
        cleanup();
    }

    @Override
    protected void onGameAbort() {
        cleanup();
    }

    @Override
    protected void onGameEnd() {
        cleanup();
    }

    public BlockChangeTracker getBlockTracker() {
        return blockTracker;
    }

    public int getTeam(PlayerExt player) {
        return teamMap.getOrDefault(player.getUniqueId(), -1);
    }

    public List<PlayerExt> getTeamPlayers(int team) {
        return getPlayers().stream()
                .filter(p -> teamMap.getOrDefault(p.getUniqueId(), -1) == team)
                .toList();
    }

    /** 玩家职业，未选返回 null。 */
    public Profession getProfession(PlayerExt player) {
        return professions.get(player.getUniqueId());
    }

    public void setProfession(PlayerExt player, Profession profession) {
        professions.put(player.getUniqueId(), profession);
        player.sender().info("<green>你选择了职业：%s".formatted(profession.getDisplayName()));
        SoundUtil.professionChosen(player.player());
    }

    /** 玩家是否已解锁该配方。 */
    public boolean hasRecipe(PlayerExt player, Material material) {
        Set<Material> unlocked = unlockedRecipes.get(player.getUniqueId());
        return unlocked != null && unlocked.contains(material);
    }

    /** 知识之书使用结果。 */
    public enum BookUseResult {
        /** 已随机解锁一个配方，需要消耗知识之书。 */
        CONSUMED,
        /** 已打开三选一菜单，选中后才消耗知识之书。 */
        CHOICE_OPENED,
        /** 未消耗（未选职业 / 配方已全解锁）。 */
        REFUSED
    }

    /**
     * 知识之书：按房间配置（bookChooseThree）随机解锁或三选一；金职业第一本必为斧子（两种模式均直接解锁）。
     *
     * @return 使用结果，决定是否消耗手中的知识之书。
     */
    public BookUseResult useKnowledgeBook(PlayerExt player) {
        Profession profession = professions.get(player.getUniqueId());
        if (profession == null) {
            player.sender().warn("<red>请先选择职业再使用知识之书");
            return BookUseResult.REFUSED;
        }
        Set<Material> unlocked = unlockedRecipes.computeIfAbsent(
                player.getUniqueId(), k -> new HashSet<>());

        if (profession == Profession.GOLD && unlocked.isEmpty()) {
            grantRecipe(player, Material.GOLDEN_AXE);
            return BookUseResult.CONSUMED;
        }

        List<Material> candidates = profession.getRecipes().stream()
                .filter(m -> !unlocked.contains(m))
                .toList();
        if (candidates.isEmpty()) {
            player.sender().warn("<yellow>你已经解锁了本职业的全部配方");
            return BookUseResult.REFUSED;
        }

        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (room.bookChooseThree && candidates.size() > 1) {
            List<Material> shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled);
            List<Material> options = List.copyOf(shuffled.subList(0, Math.min(3, shuffled.size())));
            SoundUtil.recipeChoiceOpen(player.player());
            new RecipeChoiceMenu(player.player(), this, options).open();
            return BookUseResult.CHOICE_OPENED;
        }

        grantRecipe(player, candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
        return BookUseResult.CONSUMED;
    }

    /** 三选一菜单的选择回调：消耗一本知识之书并解锁所选配方。 */
    public void chooseRecipe(PlayerExt player, Material material) {
        consumeKnowledgeBook(player.player());
        grantRecipe(player, material);
    }

    /** 解锁配方并反馈（配方绑定到个人，死亡不掉落）。 */
    private void grantRecipe(PlayerExt player, Material material) {
        unlockedRecipes.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(material);
        player.sender().info("<green>解锁配方：<white>%s</white>".formatted(recipeName(material)));
        SoundUtil.recipeUnlocked(player.player());
    }

    /** 从背包中扣除一本知识之书（选中配方时调用；打开菜单后直接关闭不消耗）。 */
    private void consumeKnowledgeBook(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.getType() == Material.KNOWLEDGE_BOOK) {
                if (stack.getAmount() > 1) {
                    stack.setAmount(stack.getAmount() - 1);
                } else {
                    player.getInventory().setItem(i, null);
                }
                return;
            }
        }
    }

    /** 配方材料的中文名。 */
    private static String recipeName(Material material) {
        return switch (material) {
            case DIAMOND_AXE -> "钻石斧";
            case DIAMOND_SWORD -> "钻石剑";
            case DIAMOND_PICKAXE -> "钻石镐";
            case DIAMOND_HELMET -> "钻石头盔";
            case DIAMOND_CHESTPLATE -> "钻石胸甲";
            case DIAMOND_LEGGINGS -> "钻石护腿";
            case DIAMOND_BOOTS -> "钻石靴子";
            case GOLDEN_AXE -> "金斧";
            case GOLDEN_SWORD -> "金剑";
            case GOLDEN_PICKAXE -> "金镐";
            case GOLDEN_HELMET -> "金头盔";
            case GOLDEN_CHESTPLATE -> "金胸甲";
            case GOLDEN_LEGGINGS -> "金护腿";
            case GOLDEN_BOOTS -> "金靴子";
            case IRON_AXE -> "铁斧";
            case IRON_SWORD -> "铁剑";
            case IRON_PICKAXE -> "铁镐";
            case IRON_HELMET -> "铁头盔";
            case IRON_CHESTPLATE -> "铁胸甲";
            case IRON_LEGGINGS -> "铁护腿";
            case IRON_BOOTS -> "铁靴子";
            case BUCKET -> "铁桶";
            case SHIELD -> "盾牌";
            case SHEARS -> "剪刀";
            default -> material.name().toLowerCase();
        };
    }

    // ==================== 等待阶段：自选队伍与动态队伍数 ====================

    /**
     * 等待阶段重算队伍数。
     *
     * <p>队伍数由当前人数决定；5/7/11 人不在映射表内，沿用上一次有效队伍数。
     * 队伍数变少时，超出范围的已选玩家会被移出队伍，并向全体提示重新选队。
     */
    public void refreshWaitingTeams() {
        int playerCount = getPlayers().size();
        Integer mapped = PLAYER_COUNT_TO_TEAMS.get(playerCount);
        if (mapped != null) {
            lastValidTeamCount = mapped;
        } else if (lastValidTeamCount <= 0) {
            return; // 还没有出现过有效人数，暂不分队。
        } else {
            mapped = lastValidTeamCount;
        }

        if (mapped == activeTeamCount) {
            return;
        }
        boolean shrunk = activeTeamCount > 0 && mapped < activeTeamCount;
        activeTeamCount = mapped;

        if (shrunk) {
            // 队伍数下降：把已经选在「被裁掉的那些队」里的玩家移出队伍。
            teamMap.entrySet().removeIf(entry -> entry.getValue() >= activeTeamCount);
            sender().warn("<yellow>队伍数发生变化，请重新选择队伍。");
            giveTeamSelectItems();
        }
    }

    /** 开局：未选队的玩家随机补齐（优先补进人数最少的队，保持均衡），并刷新 Tab 前缀。 */
    private void finalizeTeams() {
        refreshWaitingTeams();
        if (activeTeamCount <= 0) {
            activeTeamCount = 2; // 兜底：正常不会走到（tryStart 已校验人数）。
        }

        List<PlayerExt> unassigned = new ArrayList<>();
        for (PlayerExt p : getPlayers()) {
            if (getTeam(p) < 0) {
                unassigned.add(p);
            }
        }
        Collections.shuffle(unassigned);
        for (PlayerExt p : unassigned) {
            teamMap.put(p.getUniqueId(), smallestTeam());
        }
        ensureNoEmptyTeams();

        TabManager tab = MCZJUGameCore.getTabManager();
        for (PlayerExt p : getPlayers()) {
            int team = getTeam(p);
            removeTeamSelectItems(p.player());
            if (team < 0) {
                continue;
            }
            tab.setPrefix(this, p, TEAM_PREFIXES[team % TEAM_PREFIXES.length]);
        }
    }

    /**
     * 兜底：确保开局时没有空队伍。
     *
     * <p>每队上限是「向上取整(人数 / 队伍数)」，空队伍在数学上不会出现；
     * 这里仍做一次检查，从人数最多的队调一人过去。
     */
    private void ensureNoEmptyTeams() {
        for (int team = 0; team < activeTeamCount; team++) {
            if (!getTeamPlayers(team).isEmpty()) {
                continue;
            }
            int largestTeam = -1;
            int largestSize = 1; // 只在「多于一人的队」里挑，避免把仅剩的队也搬空。
            for (int t = 0; t < activeTeamCount; t++) {
                int size = getTeamPlayers(t).size();
                if (size > largestSize) {
                    largestSize = size;
                    largestTeam = t;
                }
            }
            if (largestTeam < 0) {
                continue;
            }
            PlayerExt moved = getTeamPlayers(largestTeam).get(0);
            teamMap.put(moved.getUniqueId(), team);
            sender().info("<yellow>%s</yellow> <gray>被调整到</gray> <white>%s</white>".formatted(
                    moved.getDisplayName(), teamName(team)));
        }
    }

    /** 开局后清除背包里的选队物品（该物品仅在等待阶段有用）。 */
    private static void removeTeamSelectItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && MCZJUGameCore.getItemManager().is(stack, TeamSelectItem.ID)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    /** 当前人数最少的队伍编号（并列时随机），用于随机补位时保持均衡。 */
    private int smallestTeam() {
        int capacity = teamCapacity();
        int[] counts = new int[activeTeamCount];
        for (PlayerExt p : getPlayers()) {
            int team = getTeam(p);
            if (team >= 0 && team < activeTeamCount) {
                counts[team]++;
            }
        }
        int min = Integer.MAX_VALUE;
        List<Integer> ties = new ArrayList<>();
        for (int team = 0; team < activeTeamCount; team++) {
            if (capacity > 0 && counts[team] >= capacity) {
                continue; // 该队已满，跳过。
            }
            if (counts[team] < min) {
                min = counts[team];
                ties.clear();
                ties.add(team);
            } else if (counts[team] == min) {
                ties.add(team);
            }
        }
        if (ties.isEmpty()) {
            return 0; // 理论上不会发生：每队上限 × 队伍数 >= 总人数。
        }
        return ties.get(ThreadLocalRandom.current().nextInt(ties.size()));
    }

    /**
     * 当前每队人数上限 = 向上取整(人数 / 队伍数)。
     *
     * <p>向上取整保证「上限 × 队伍数 ≥ 总人数」，因此既不会有人无处可去，
     * 也顺带保证开局时不会出现空队伍。例：9 人 3 队 → 每队 3 人。
     */
    public int teamCapacity() {
        if (activeTeamCount <= 0) {
            return 0;
        }
        int players = getPlayers().size();
        return (players + activeTeamCount - 1) / activeTeamCount;
    }

    /**
     * 是否存在某支队伍的人数超过当前上限。
     *
     * <p>上限只在「选队那一刻」强制；人数减少会使上限变小，此时已在队里的玩家不会被自动移出，
     * 于是可能出现短暂的超员。这种情况下不允许开局。
     */
    public boolean hasTeamOverCapacity() {
        if (activeTeamCount <= 0) {
            return false;
        }
        int capacity = teamCapacity();
        for (int team = 0; team < activeTeamCount; team++) {
            if (getTeamPlayers(team).size() > capacity) {
                return true;
            }
        }
        return false;
    }

    /** 本局队伍数（等待阶段随人数变化）。 */
    public int getActiveTeamCount() {
        return activeTeamCount;
    }

    /** 等待阶段玩家自选队伍；成功返回 true。 */
    public boolean selectTeam(PlayerExt player, int team) {
        if (getState() != GameState.WAITING) {
            player.sender().warn("<red>对局已经开始，无法再更换队伍");
            return false;
        }
        if (activeTeamCount <= 0) {
            player.sender().warn("<red>当前人数还不足以确定队伍数，请等待更多玩家加入");
            return false;
        }
        if (team < 0 || team >= activeTeamCount) {
            player.sender().warn("<red>本局只有 %d 支队伍，无法加入该队".formatted(activeTeamCount));
            return false;
        }

        int previous = getTeam(player);
        if (previous == team) {
            player.sender().info("<gray>你已经在 <white>%s</white> 了".formatted(teamName(team)));
            return true;
        }
        // 队伍人数上限：9 人 3 队时每队 3 人，第 4 人申请入队会被驳回。
        int capacity = teamCapacity();
        if (capacity > 0 && getTeamPlayers(team).size() >= capacity) {
            player.sender().warn("<red>队伍人数已达上限。");
            return false;
        }
        if (previous >= 0) {
            sender().info("<yellow>%s</yellow> <gray>离开了</gray> %s".formatted(
                    player.getDisplayName(), teamName(previous)));
        }
        teamMap.put(player.getUniqueId(), team);
        sender().info("<yellow>%s</yellow> <gray>加入了</gray> <white>%s</white>".formatted(
                player.getDisplayName(), teamName(team)));
        return true;
    }

    /** 把等待中所有玩家的选队物品归位到快捷栏第 5 格。 */
    public void giveTeamSelectItems() {
        for (PlayerExt p : getPlayers()) {
            giveTeamSelectItem(p);
        }
    }

    /** 把单个玩家的选队物品归位到快捷栏第 5 格（重复副本会被清除）。 */
    public void giveTeamSelectItem(PlayerExt player) {
        TeamSelectItem.ensureInFixedSlot(player.player());
    }

    /**
     * 整支 MGC 队伍（party）加入等待房：按含全队的新人数重算队伍数与每队上限、补发选队物品，
     * 然后**立刻解散该队伍**，让所有人回到「待选队」状态各自选队。
     *
     * <p>本局每队人数由总人数刚性决定（{@code ceil(人数 / 队伍数)}），大队伍无法保证同队，
     * 因此这里不做同队分配，直接解散交给玩家自选。
     * 解散后 {@code teamMap} 中没有这些玩家，{@code getTeam()} 返回 {@code -1}，
     * 天然就是「待选队」状态，无需额外代码。
     */
    public void acceptParty(Party party) {
        int size = party.size();
        // 先按「含全队」的人数重算：队伍数与每队上限都会随之变化。
        refreshWaitingTeams();
        // refreshWaitingTeams() 只在「队伍数减少」的分支里补发选队物品，
        // 而整队加入多半是增长（例如 12 人：2 队 → 4 队），所以这里显式补一次，
        // 保证刚进来的整队也能拿到「选择队伍」物品（否则播报让右键下界之星，玩家手里却没有）。
        giveTeamSelectItems();

        // MGC 的 disbandPartyInternal 不通知成员，这里由插件播报。
        DisbandResult result = MCZJUGameCore.getPartymanager().disband(party.getLeader());
        if (result == DisbandResult.SUCCESS) {
            sender().info("<gray>一支 <white>%d 人</white> 的队伍加入了等待房，<yellow>队伍已自动解散</yellow>。".formatted(size));
        } else {
            // 理论上不会发生（队长必然在队伍中）；兜底记录，避免玩家停在旧的组队状态。
            logger.warn("解散队伍失败：%s（队长 %s）".formatted(result, party.getLeader().getName()));
            sender().info("<gray>一支 <white>%d 人</white> 的队伍加入了等待房。".formatted(size));
        }
        sender().info("<gray>请各自选择队伍：右键手中的 <white>下界之星</white>，或使用 <white>/ceasefire team <颜色></white>");
    }

    /** 第二类生成点是否生效：仅当本局队伍数为 4。 */
    private boolean secondPointsActive() {
        return activeTeamCount == 4;
    }

    /** 分配各队出生点：出生点随机洗牌，取前 teamCount 个，每队一个。 */
    private void assignTeamSpawns() {
        teamSpawnPoints.clear();
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        List<Location> spawns = room.spawnPoints;
        if (spawns == null || spawns.isEmpty()) {
            return;
        }
        int teamCount = activeTeamCount > 0 ? activeTeamCount : Math.clamp(room.teamCount, 2, 4);
        List<Location> shuffled = new ArrayList<>(spawns);
        Collections.shuffle(shuffled);
        for (int i = 0; i < Math.min(teamCount, shuffled.size()); i++) {
            teamSpawnPoints.put(i, shuffled.get(i));
        }
    }

    /** 开局把各队从大厅传送到各自出生点。 */
    private void teleportTeamsToSpawns() {
        for (PlayerExt p : getPlayers()) {
            Location spawn = teamSpawnPoints.get(getTeam(p));
            if (spawn != null) {
                p.player().teleport(spawn);
            }
        }
    }

    /**
     * 正式开局时把玩家切到房间配置的游戏模式。
     *
     * <p>大厅档案默认把玩家设为 ADVENTURE，而 MGC 切到游戏档案时**不会**改模式，
     * 不切的话玩家放不下开局发的木板 / 羊毛，也拆不掉自己搭的防御。
     * 房间字段 {@code gameMode} 留空或非法时不干预。
     */
    private void applyGameMode() {
        GameMode mode = parseGameMode(((CeasefireRoom) getGameRoom()).gameMode);
        if (mode == null) {
            return;
        }
        for (PlayerExt p : getPlayers()) {
            if (p.player().getGameMode() != mode) {
                p.player().setGameMode(mode);
            }
        }
    }

    /** 房间是否开启「结束清空物品栏」。 */
    private boolean isInventoryClearEnabled() {
        return getGameRoom() instanceof CeasefireRoom room && room.clearInventoryOnEnd;
    }

    /**
     * 清空单个玩家的物品栏（主栏 + 4 格护甲 + 副手，共 41 格）与末影箱。
     *
     * <p>MGC 会在玩家离开对局时（`switchProfile(null)`）把**当前**物品栏存进该游戏的档案快照，
     * 且该快照从不删除。只有在那之前清空，快照才是空的，下一局才不会被上一局的装备污染
     * （也堵住了中途 `/mgc leave` 把装备「存」进下一局的漏洞）。
     */
    private void clearInventory(Player player) {
        if (player == null || !isInventoryClearEnabled()) {
            return;
        }
        player.getInventory().clear();
        player.getEnderChest().clear();
    }

    /** 解析房间配置的游戏模式；留空或非法返回 null（表示不干预，沿用当前模式）。 */
    private GameMode parseGameMode(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            return GameMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("房间 %s 的 gameMode 配置无效：%s（应为 SURVIVAL / ADVENTURE / CREATIVE），本次不干预"
                    .formatted(getGameRoom().getRoomName(), configured));
            return null;
        }
    }

    /** 开局清空所有玩家的背包与末影箱（房间开关 clearInventoryOnStart，默认开）。 */
    private void clearInventoriesOnStart() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (!room.clearInventoryOnStart) {
            return;
        }
        for (PlayerExt p : getPlayers()) {
            p.player().getInventory().clear();
            p.player().getEnderChest().clear();
        }
    }

    /** 让单个玩家隐藏「时间上限 / 决战时刻」BossBar（中途退出必须单独处理，否则他会一直看着那条倒计时）。 */
    private void hideTimeBossBarFor(Player player) {
        if (timeBossBar != null && player != null && player.isOnline()) {
            player.hideBossBar(timeBossBar);
        }
    }

    private void giveStartItems() {
        if (startItemsGranted) {
            return; // 开局物品每局仅发放一次（金苹果依赖此保证）。
        }
        startItemsGranted = true;

        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        for (PlayerExt p : getPlayers()) {
            Player player = p.player();
            player.getInventory().addItem(
                    new ItemStack(Material.OAK_PLANKS, room.startPlanks),
                    new ItemStack(Material.STICK, room.startSticks),
                    new ItemStack(Material.WHITE_WOOL, room.startWool),
                    new ItemStack(Material.WOODEN_SWORD)
            );
            if (room.startCraftingTables > 0) {
                player.getInventory().addItem(new ItemStack(Material.CRAFTING_TABLE, room.startCraftingTables));
            }
            // 金苹果：只在开局发放，复活走 giveRespawnItems()（那里不发金苹果）。
            if (room.startGoldenApples > 0) {
                player.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, room.startGoldenApples));
            }
            // 金胡萝卜：开局与每次复活都发放（同一个配置项）。
            if (room.goldenCarrots > 0) {
                player.getInventory().addItem(new ItemStack(Material.GOLDEN_CARROT, room.goldenCarrots));
            }
            player.getInventory().addItem(
                    ItemBuilder.of(Material.COMPASS)
                            .customName("<gold>敌人追踪器</gold>")
                            .build()
            );
        }
    }

    /** 开局给每个玩家打开职业选择菜单。 */
    private void openProfessionMenus() {
        for (PlayerExt p : getPlayers()) {
            new ProfessionMenu(p.player(), this).open();
        }
    }

    /** 为每个存活队伍创建独立语音组（ISOLATED：队内可听、跨队隔离）；未安装语音插件时静默跳过。 */
    private void startVoiceGroups() {
        if (!VoiceGroupUtil.isAvailable()) {
            return;
        }
        try {
            for (int team = 0; team < activeTeamCount; team++) {
                List<PlayerExt> members = getTeamPlayers(team);
                if (members.isEmpty()) {
                    continue;
                }
                VoiceGroupUtil.createGroup(
                                "ceasefire-team-" + team,
                                members,
                                VoiceGroupUtil.Options.defaults().withType(VoiceGroupUtil.GroupType.ISOLATED))
                        .ifPresent(voiceGroupIds::add);
            }
        } catch (IllegalStateException e) {
            logger.warn("创建语音组失败：%s".formatted(e.getMessage()));
        }
    }

    private void startCompassTask() {
        compassTask = Bukkit.getScheduler().runTaskTimer(
                CeasefireWar.getInstance(), this::updateCompassTargets, 0L, 20L);
    }

    private void updateCompassTargets() {
        for (PlayerExt p : getPlayers()) {
            Location target = findNearestEnemy(p);
            if (target != null) {
                p.player().setCompassTarget(target);
            }
        }
    }

    private Location findNearestEnemy(PlayerExt self) {
        int myTeam = getTeam(self);
        Location myLoc = self.player().getLocation();
        PlayerExt nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (PlayerExt other : getPlayers()) {
            if (other.equals(self) || getTeam(other) == myTeam) {
                continue;
            }
            Player op = other.player();
            if (!op.isOnline()) {
                continue;
            }
            double d = op.getLocation().distanceSquared(myLoc);
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = other;
            }
        }
        return nearest == null ? null : nearest.player().getLocation();
    }

    private void startBorder() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (room.borderCenter == null) {
            return;
        }
        World world = room.borderCenter.getWorld();
        if (world == null) {
            return;
        }
        WorldBorder border = world.getWorldBorder();
        // 先记录开局前的边界，终局在 cleanup() 里按 restoreBorderOnEnd 还原。
        snapshotBorder(room, world, border);
        border.setCenter(room.borderCenter.getX(), room.borderCenter.getZ());
        border.setSize(room.borderInitialSize);
        border.setWarningDistance(10);
        border.setWarningTimeTicks(200);

        long delayTicks = room.borderStartDelaySeconds * 20L;
        borderTask = Bukkit.getScheduler().runTaskLater(CeasefireWar.getInstance(), () -> {
            border.changeSize(room.borderFinalSize, room.borderShrinkSeconds * 20L);
        }, delayTicks);
    }

    /** 实际生效的矿物生成点：合并新旧字段并去重。 */
    private List<Location> effectiveMineralPoints() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        List<Location> points = new ArrayList<>(room.mineralSpawnPoints);
        if (room.mineralSpawnPoint != null) {
            points.add(room.mineralSpawnPoint);
        }
        // 第二类生成点仅在 4 队局时叠加生效。
        if (secondPointsActive()) {
            points.addAll(room.mineralSpawnPoints2);
        }
        return points.stream().filter(loc -> loc != null && loc.getWorld() != null).distinct().toList();
    }

    /** 实际生效的知识之书生成点：合并新旧字段并去重。 */
    private List<Location> effectiveBookPoints() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        List<Location> points = new ArrayList<>(room.bookSpawnPoints);
        if (room.bookSpawnPoint != null) {
            points.add(room.bookSpawnPoint);
        }
        // 第二类生成点仅在 4 队局时叠加生效。
        if (secondPointsActive()) {
            points.addAll(room.bookSpawnPoints2);
        }
        return points.stream().filter(loc -> loc != null && loc.getWorld() != null).distinct().toList();
    }

    private void startMineralSpawn() {
        if (mineralTask != null) {
            return; // 已启动过：防止任务句柄被覆盖后旧任务泄漏、对局结束后仍继续掉落
        }
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (effectiveMineralPoints().isEmpty()) {
            return;
        }
        long interval = Math.max(1, room.mineralIntervalSeconds) * 20L;
        mineralTask = Bukkit.getScheduler().runTaskTimer(
                CeasefireWar.getInstance(), this::spawnMinerals, interval, interval);
    }

    /** 每个生成点投放 1 个矿物（三种矿物按权重出现，仅投放已加载区块）。 */
    private void spawnMinerals() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        for (Location point : effectiveMineralPoints()) {
            World world = point.getWorld();
            if (world == null || !world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)) {
                continue;
            }
            world.dropItem(point, new ItemStack(rollMineral(room)));
        }
    }

    private Material rollMineral(CeasefireRoom room) {
        int total = room.ironWeight + room.goldWeight + room.diamondWeight;
        if (total <= 0) {
            return Material.IRON_INGOT;
        }
        int r = ThreadLocalRandom.current().nextInt(total);
        if (r < room.ironWeight) {
            return Material.IRON_INGOT;
        }
        r -= room.ironWeight;
        if (r < room.goldWeight) {
            return Material.GOLD_INGOT;
        }
        return Material.DIAMOND;
    }

    /** 定时在每个知识之书生成点掉落知识之书。 */
    private void startBookSpawn() {
        if (bookTask != null) {
            return; // 已启动过：防止任务句柄被覆盖后旧任务泄漏、对局结束后仍继续掉落
        }
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (effectiveBookPoints().isEmpty()) {
            return;
        }
        long interval = Math.max(1, room.bookIntervalSeconds) * 20L;
        bookTask = Bukkit.getScheduler().runTaskTimer(
                CeasefireWar.getInstance(), this::spawnBooks, interval, interval);
    }

    /** 每个生成点投放 1 本知识之书（仅投放已加载区块）。 */
    private void spawnBooks() {
        for (Location point : effectiveBookPoints()) {
            World world = point.getWorld();
            if (world == null || !world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)) {
                continue;
            }
            world.dropItem(point, new ItemStack(Material.KNOWLEDGE_BOOK));
        }
    }

    /** 玩家死亡处理：本局击败/死亡统计、复活等待或决战淘汰、团灭判定。 */
    public void handlePlayerDeath(PlayerExt player, PlayerDeathEvent event) {
        roundDeaths.merge(player.getUniqueId(), 1, Integer::sum);

        Player killer = player.player().getKiller();
        if (killer != null) {
            PlayerExt killerExt = new PlayerExt(killer);
            if (killerExt.getGame() == this) {
                roundKills.merge(killer.getUniqueId(), 1, Integer::sum);
                SoundUtil.killed(killer);
            }
            event.deathMessage(TextParser.parse(
                    "<red>%s</red> <gray>被</gray> <yellow>%s</yellow> <gray>击倒</gray>"
                            .formatted(player.player().getName(), killer.getName())));
        } else {
            event.deathMessage(TextParser.parse("<red>%s</red> <gray>阵亡</gray>"
                    .formatted(player.player().getName())));
        }

        // 决战时刻：死亡即淘汰，不再复活。
        if (suddenDeath) {
            eliminated.add(player.getUniqueId());
            player.sender().info("<red>你在决战中阵亡，已被淘汰");
            SoundUtil.eliminated(player.player());
            respawnAsSpectator(player, randomSpawnPoint());
            checkTeamWipe(getTeam(player));
            checkWin();
            cancelDeathIfGameEnded(player, event);
            return;
        }

        int delay = computeReviveDelaySeconds();
        revivingPlayers.put(player.getUniqueId(), System.currentTimeMillis() + delay * 1000L);
        player.sender().info("<red>你被击倒了，<yellow>%d</yellow> 秒后复活".formatted(delay));
        SoundUtil.knockedDown(player.player());
        respawnAsSpectator(player, randomSpawnPoint());

        checkTeamWipe(getTeam(player));
        cancelDeathIfGameEnded(player, event);
    }

    /**
     * 如果这一死直接把对局打完了，就**取消这次死亡**。
     *
     * <p>原因：`checkTeamWipe → checkWin → endGame → cleanup` 全都在**死亡事件的调用栈内**同步跑完，
     * 也就是"对局结束"发生在原版生成死亡掉落、把玩家推上死亡界面**之前**。于是：
     * <ul>
     *   <li>死亡界面会一直留着 —— 1 tick 后的补救时常因为血量已被大厅档案复原而判断失效；</li>
     *   <li>掉落物在 cleanup 之后才生成，`removeDroppedItems()` 扫不到，留在地上。</li>
     * </ul>
     * 直接取消死亡可以一次性解决这两件事：玩家不死 → 没有死亡界面、也没有掉落物。
     */
    private void cancelDeathIfGameEnded(PlayerExt player, PlayerDeathEvent event) {
        if (!finished || event.isCancelled()) {
            return;
        }
        event.setCancelled(true);
        cancelledDeaths.add(player.getUniqueId());
        if (player.player().isOnline()) {
            player.resetState(); // 回满血、清火焰/药水，避免取消死亡后停在残状态
        }
    }

    /**
     * 1 tick 后自动脱离死亡界面：重生后切旁观并传送到目标位置。
     * 对局已结束时仅补一次重生（框架已接管档案恢复，不再传送/改模式），避免卡在死亡界面。
     */
    private void respawnAsSpectator(PlayerExt player, Location target) {
        Bukkit.getScheduler().runTaskLater(CeasefireWar.getInstance(), () -> {
            Player p = player.player();
            if (!p.isOnline()) {
                return;
            }
            // 本方法只由「刚死亡」的玩家触发，但**不能**再用 health<=0 判断是否在死亡界面：
            // 对局结束这条路径上，框架的 switchProfile(null) 会先把血量复原成 20，判断就失效了，
            // 于是最后一名死者会一直停在死亡界面（Bug：最后一个被杀的卡重生界面）。
            if (getState() == GameState.END) {
                // 这次死亡如果已经被 cancelDeathIfGameEnded 取消，玩家根本没死 —— 不能对他调 respawn
                //（对活人调用会触发重生点传送，可能把人从大厅拽回世界出生点）。
                if (!cancelledDeaths.remove(p.getUniqueId())) {
                    p.spigot().respawn();
                }
                return;
            }
            p.spigot().respawn();   // 无条件：本任务只由「刚死亡」的玩家触发，他一定在死亡界面
            p.setGameMode(GameMode.SPECTATOR);
            if (target != null) {
                p.teleport(target);
            }
        }, 1L);
    }

    /** 复活时间 10~30 秒，随对局时间延长（每 30 秒 +1 秒）。 */
    private int computeReviveDelaySeconds() {
        long elapsed = System.currentTimeMillis() - startTime;
        int delay = 10 + (int) (elapsed / 30_000L);
        return Math.min(30, delay);
    }

    private void startReviveTask() {
        reviveTask = Bukkit.getScheduler().runTaskTimer(
                CeasefireWar.getInstance(), this::tickRevive, 0L, 20L);
    }

    private void tickRevive() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = revivingPlayers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (now < e.getValue()) {
                continue;
            }
            it.remove();
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null && player.isOnline()) {
                revive(new PlayerExt(player));
            }
        }
    }

    private void revive(PlayerExt player) {
        Player p = player.player();
        p.setGameMode(GameMode.SURVIVAL);   // 复活必须回到生存，否则复活后不能挖/放方块
        Location loc = findReviveLocation(player);
        if (loc != null) {
            p.teleport(loc);
        }
        giveRespawnItems(player);
        player.sender().info("<green>你复活了");
        SoundUtil.revived(player.player());
    }

    /** 复活位置：优先活着的队友附近 5 格范围内的完整方块上；否则出生点。 */
    private Location findReviveLocation(PlayerExt player) {
        int team = getTeam(player);
        for (PlayerExt mate : getTeamPlayers(team)) {
            if (mate.equals(player)) {
                continue;
            }
            if (revivingPlayers.containsKey(mate.getUniqueId())) {
                continue;
            }
            if (!mate.player().isOnline()) {
                continue;
            }
            return findSafeLocationNear(mate.player().getLocation());
        }
        return randomSpawnPoint();
    }

    /** 在给定位置附近 5 格范围内随机找一个可站立的完整方块上方。 */
    private Location findSafeLocationNear(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return center;
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            int dx = ThreadLocalRandom.current().nextInt(-5, 6);
            int dz = ThreadLocalRandom.current().nextInt(-5, 6);
            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            for (int dy = 3; dy >= -3; dy--) {
                int y = center.getBlockY() + dy;
                Block below = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (below.getType().isOccluding()
                        && !below.isLiquid()
                        && feet.getType().isAir()
                        && head.getType().isAir()) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return center;
    }

    private Location randomSpawnPoint() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (room.spawnPoints == null || room.spawnPoints.isEmpty()) {
            return null;
        }
        return room.spawnPoints.get(ThreadLocalRandom.current().nextInt(room.spawnPoints.size()));
    }

    /** 复活物品：木板、木棍、羊毛、木剑、工作台、金胡萝卜（不含指南针与金苹果，二者每局仅一次）。 */
    private void giveRespawnItems(PlayerExt p) {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        p.player().getInventory().addItem(
                new ItemStack(Material.OAK_PLANKS, room.startPlanks),
                new ItemStack(Material.STICK, room.startSticks),
                new ItemStack(Material.WHITE_WOOL, room.startWool),
                new ItemStack(Material.WOODEN_SWORD)
        );
        if (room.startCraftingTables > 0) {
            p.player().getInventory().addItem(new ItemStack(Material.CRAFTING_TABLE, room.startCraftingTables));
        }
        if (room.goldenCarrots > 0) {
            p.player().getInventory().addItem(new ItemStack(Material.GOLDEN_CARROT, room.goldenCarrots));
        }
    }

    /** 团灭判定：某队全员（已淘汰 / 复活等待中 / 已退出）则团灭，并取消该队剩余待复活者。 */
    private void checkTeamWipe(int team) {
        if (team < 0 || wipedTeams.contains(team) || finished) {
            return;
        }
        for (PlayerExt p : getTeamPlayers(team)) {
            UUID id = p.getUniqueId();
            if (!eliminated.contains(id) && !revivingPlayers.containsKey(id)) {
                return; // 还有可战斗的队员。
            }
        }
        wipedTeams.add(team);
        // 团灭后不再复活该队待复活者；对仍在死亡界面者（如刚阵亡的最后一人）补一次重生，避免卡死。
        for (PlayerExt p : getTeamPlayers(team)) {
            if (revivingPlayers.remove(p.getUniqueId()) != null) {
                eliminated.add(p.getUniqueId());
                p.sender().info("<red>你的队伍已团灭，你被淘汰");
                SoundUtil.eliminated(p.player());
                respawnAsSpectator(p, randomSpawnPoint());
            }
        }
        sender().info("<red>%s 团灭！".formatted(teamName(team)));
        SoundUtil.teamWiped(getPlayers());
        checkWin();
    }

    /** 胜利判定：只剩一队未团灭则该队胜利。 */
    private void checkWin() {
        if (finished || activeTeamCount <= 0) {
            return;
        }
        int aliveTeams = 0;
        int aliveTeam = -1;
        for (int t = 0; t < activeTeamCount; t++) {
            // 自选队伍可能出现空队；空队不参与胜负判定，否则空队会「获胜」。
            if (wipedTeams.contains(t) || getTeamPlayers(t).isEmpty()) {
                continue;
            }
            aliveTeams++;
            aliveTeam = t;
        }
        if (aliveTeams == 1) {
            finished = true;
            sender().info("<gold>%s 获得胜利！".formatted(teamName(aliveTeam)));
            broadcastRoundStats();
            SoundUtil.victory(getPlayers());
            for (PlayerExt p : getPlayers()) {
                p.player().showTitle(Title.title(
                        TextParser.parse("<gold><b>%s 胜利</b>".formatted(teamName(aliveTeam))),
                        TextParser.parse("<gray>对局结束"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
            }
            MCZJUGameCore.getGameManager().endGame(this);
        }
    }

    /** 对局结束：在聊天框播报本局每名参与者的击败/死亡数，形式为 玩家名/K击败/D死亡。 */
    private void broadcastRoundStats() {
        List<UUID> participants = new ArrayList<>(teamMap.keySet());
        participants.sort((a, b) -> {
            int teamA = teamMap.getOrDefault(a, Integer.MAX_VALUE);
            int teamB = teamMap.getOrDefault(b, Integer.MAX_VALUE);
            if (teamA != teamB) {
                return Integer.compare(teamA, teamB);
            }
            int killsA = roundKills.getOrDefault(a, 0);
            int killsB = roundKills.getOrDefault(b, 0);
            if (killsA != killsB) {
                return Integer.compare(killsB, killsA);
            }
            return nameOf(a).compareToIgnoreCase(nameOf(b));
        });

        sender().info("<gold><b>本局战绩</b>");
        for (UUID id : participants) {
            sender().info("<white>%s<gray>/K%s/D%s".formatted(
                    nameOf(id),
                    roundKills.getOrDefault(id, 0),
                    roundDeaths.getOrDefault(id, 0)));
        }
    }

    /** 解析玩家名；离线且无记录时退化为 UUID 前 8 位。 */
    private static String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    /** 中途退出：判负出局、比赛继续；场上无人时终止对局。 */
    public void handlePlayerQuit(PlayerExt player, PlayerQuitReason reason) {
        // 中途退出：先收掉他身上的 BossBar，否则他会一直看着那条倒计时
        //（cleanup() 只处理还留在局内的玩家，退出者此时已被移出 getPlayers()）。
        hideTimeBossBarFor(player.player());
        // 中途退出也要清空背包：框架是在退出策略**之后**才 switchProfile(null) 捕获物品栏的，
        // 不清的话整套装备会被存进本局档案、下一局开局直接带出来（刷装备漏洞）。
        clearInventory(player.player());
        GameState state = getState();
        if (state == GameState.WAITING || state == GameState.END || finished) {
            return; // 等待阶段退出不影响对局；终局后忽略。
        }
        int team = getTeam(player);
        if (team < 0) {
            return; // 从未参与分队（如中途加入失败），不算判负。
        }
        eliminated.add(player.getUniqueId());
        revivingPlayers.remove(player.getUniqueId());
        String cause = switch (reason) {
            case DISCONNECT -> "掉线";
            case COMMAND_QUIT -> "主动退出";
            case JOIN_FAIL -> "加入失败";
        };
        sender().info("<yellow>%s</yellow> <gray>%s，其队伍失去一名队员</gray>"
                .formatted(player.player().getName(), cause));
        checkTeamWipe(team);
        checkWin();
        if (getPlayers().isEmpty()) {
            MCZJUGameCore.getGameManager().abortGame(this);
        }
    }

    /** 队伍编号对应的显示名（0=红 1=蓝 2=黄 3=绿）。 */
    public static String teamName(int team) {
        String[] names = {"红队", "蓝队", "黄队", "绿队"};
        return names[Math.floorMod(team, names.length)];
    }

    /** 启动对局时间上限计时；到时进入决战时刻（timeLimitSeconds <= 0 时不启用）。 */
    private void startTimeLimit() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (room.timeLimitSeconds <= 0) {
            return;
        }
        final int limit = room.timeLimitSeconds;
        timeBossBar = BossBar.bossBar(
                TextParser.parse("<gold>剩余时间：<white>%s</white>".formatted(formatTime(limit))),
                1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (PlayerExt p : getPlayers()) {
            p.player().showBossBar(timeBossBar);
        }
        final long start = System.currentTimeMillis();
        timeLimitTask = Bukkit.getScheduler().runTaskTimer(CeasefireWar.getInstance(), () -> {
            long remaining = limit - (System.currentTimeMillis() - start) / 1000L;
            if (remaining <= 0) {
                cancelTimeLimitTask();
                if (timeBossBar != null) {
                    timeBossBar.name(TextParser.parse("<red><b>决战时刻</b>"));
                    timeBossBar.color(BossBar.Color.RED);
                    timeBossBar.progress(1.0f);
                }
                enterSuddenDeath();
                return;
            }
            if (timeBossBar != null) {
                timeBossBar.name(TextParser.parse(
                        "<gold>剩余时间：<white>%s</white>".formatted(formatTime((int) remaining))));
                timeBossBar.progress(Math.max(0f, Math.min(1f, remaining / (float) limit)));
            }
            if (remaining == 300 || remaining == 120 || remaining == 60 || remaining == 30 || remaining == 10) {
                sender().info("<yellow>距离决战时刻还有 <white>%s</white>".formatted(formatTime((int) remaining)));
                SoundUtil.timeWarning(getPlayers(), remaining <= 10);
            }
            if (remaining <= 5) {
                for (PlayerExt p : getPlayers()) {
                    p.player().sendActionBar(TextParser.parse("<red>决战倒计时 %d</red>".formatted(remaining)));
                }
                SoundUtil.finalCountdown(getPlayers(), (int) remaining);
            }
        }, 20L, 20L);
    }

    /** 取消时间上限计时任务（进入决战或清理时调用）。 */
    private void cancelTimeLimitTask() {
        if (timeLimitTask != null) {
            timeLimitTask.cancel();
            timeLimitTask = null;
        }
    }

    /** 决战时刻：关闭复活、淘汰待复活者、强制加速缩圈、发光标记存活者。 */
    private void enterSuddenDeath() {
        if (suddenDeath || finished) {
            return;
        }
        suddenDeath = true;
        // 待复活者直接淘汰。
        Iterator<Map.Entry<UUID, Long>> it = revivingPlayers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            it.remove();
            eliminated.add(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                new PlayerExt(p).sender().info("<red>决战时刻来临，你未能复活，已被淘汰");
                SoundUtil.eliminated(p);
            }
        }
        // 取消原缩圈任务，强制加速缩到最终大小。
        if (borderTask != null) {
            borderTask.cancel();
            borderTask = null;
        }
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        if (room.borderCenter != null && room.borderCenter.getWorld() != null) {
            WorldBorder border = room.borderCenter.getWorld().getWorldBorder();
            border.setWarningTimeTicks(0);
            border.setWarningDistance(5);
            border.changeSize(room.borderFinalSize, Math.max(5, room.suddenDeathShrinkSeconds) * 20L);
        }
        sender().info("<red><b>决战时刻！</b></red> <gray>复活已关闭，边界加速收缩，死亡即淘汰</gray>");
        for (PlayerExt p : getPlayers()) {
            p.player().showTitle(Title.title(
                    TextParser.parse("<red><b>决战时刻</b>"),
                    TextParser.parse("<gray>复活关闭 · 死亡即淘汰"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(400))));
        }
        SoundUtil.suddenDeath(getPlayers());
        startFinalGlow();
        // 待复活者被淘汰后可能已有队伍全员阵亡，统一做团灭/胜利兜底。
        for (int t = 0; t < activeTeamCount; t++) {
            checkTeamWipe(t);
        }
        checkWin();
    }

    /** 决战期间每 2 秒给存活者发光轮廓，便于交火。 */
    private void startFinalGlow() {
        finalGlowTask = Bukkit.getScheduler().runTaskTimer(CeasefireWar.getInstance(), () -> {
            for (PlayerExt p : getPlayers()) {
                UUID id = p.getUniqueId();
                if (eliminated.contains(id) || revivingPlayers.containsKey(id)) {
                    continue;
                }
                p.player().addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false));
            }
        }, 0L, 40L);
    }

    /** 秒数格式化为 m:ss。 */
    private static String formatTime(int seconds) {
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /** 是否处于准备阶段（用于禁锢玩家）。 */
    public boolean isPrepPhase() {
        return prepActive;
    }

    /** 准备阶段：禁锢玩家在出生点 + 打开选职业界面 + 30 秒倒计时。 */
    private void startPrepPhase() {
        prepActive = true;
        prepStartTime = System.currentTimeMillis();
        openProfessionMenus();
        sender().info("<yellow>你有 30 秒选择职业，随后游戏开始");
        SoundUtil.prepStart(getPlayers());
        prepTask = Bukkit.getScheduler().runTaskTimer(
                CeasefireWar.getInstance(), this::tickPrep, 0L, 20L);
    }

    private void tickPrep() {
        long remaining = 30_000L - (System.currentTimeMillis() - prepStartTime);
        if (remaining <= 0) {
            assignRandomProfessions();
            startGameplay();
            return;
        }
        // 所有人选完且剩余 >= 5 秒才进入 5 秒倒计时；不足 5 秒则等 30 秒倒计时自然结束。
        if (allPlayersChoseProfession() && remaining >= 5_000L) {
            prepTask.cancel();
            prepTask = null;
            startCountdown(5);
        }
    }

    private boolean allPlayersChoseProfession() {
        for (PlayerExt p : getPlayers()) {
            if (professions.get(p.getUniqueId()) == null) {
                return false;
            }
        }
        return true;
    }

    private void assignRandomProfessions() {
        for (PlayerExt p : getPlayers()) {
            if (professions.get(p.getUniqueId()) == null) {
                setProfession(p, randomProfessionAvoidTeammates(p));
            }
        }
    }

    /** 随机职业，优先与队友不同。 */
    private Profession randomProfessionAvoidTeammates(PlayerExt player) {
        Set<Profession> teammateProfs = new HashSet<>();
        for (PlayerExt mate : getTeamPlayers(getTeam(player))) {
            Profession p = professions.get(mate.getUniqueId());
            if (p != null) {
                teammateProfs.add(p);
            }
        }
        List<Profession> all = List.of(Profession.DIAMOND, Profession.IRON, Profession.GOLD);
        List<Profession> different = all.stream().filter(p -> !teammateProfs.contains(p)).toList();
        List<Profession> pool = different.isEmpty() ? all : different;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private void startCountdown(int seconds) {
        final int[] left = {seconds};
        sender().info("<yellow>所有人已选完职业，%d 秒后开始".formatted(seconds));
        countdownTask = Bukkit.getScheduler().runTaskTimer(CeasefireWar.getInstance(), () -> {
            if (left[0] <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                startGameplay();
                return;
            }
            SoundUtil.countdownTick(getPlayers(), left[0]);
            for (PlayerExt p : getPlayers()) {
                p.player().sendActionBar(TextParser.parse("<yellow>%d 秒后开始</yellow>".formatted(left[0])));
            }
            left[0]--;
        }, 20L, 20L);
    }

    /** 正式开始：解除禁锢，发物品，启动缩圈/生成/复活/时间上限。 */
    private void startGameplay() {
        prepActive = false;
        if (prepTask != null) {
            prepTask.cancel();
            prepTask = null;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        applyGameMode();
        giveStartItems();
        startBorder();
        startCompassTask();
        startMineralSpawn();
        startBookSpawn();
        startReviveTask();
        startTimeLimit();
        SoundUtil.gameBegin(getPlayers());
        for (PlayerExt p : getPlayers()) {
            p.player().showTitle(Title.title(
                    TextParser.parse("<gold><b>游戏开始</b>"),
                    TextParser.parse("<yellow>收集资源，解锁配方，合成装备"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        }
    }

    /** 执行一个清理步骤：任何异常只影响这一步，不会连锁跳过后续步骤（一个 bug 不能拖垮整条收尾流程）。 */
    private void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            logger.error("[Ceasefire] 清理步骤「" + name + "」失败：" + t);
            for (StackTraceElement el : t.getStackTrace()) {
                logger.error("    at " + el);
            }
        }
    }

    private void cleanup() {
        // 第一步就清空玩家背包：框架在随后的 removeAllPlayer() 里才会 switchProfile(null)，
        // 把「当前」物品栏存进本局档案快照。此刻清空 → 快照为空 → 下一局不会带上一局的装备。
        step("清空背包", () -> {
            for (PlayerExt p : getPlayers()) {
                clearInventory(p.player());
            }
        });
        // 旁观者回到冒险模式（与大厅一致）：框架只在「大厅档案功能开启」时才设 ADVENTURE，
        // 关掉时死亡玩家会一直停在旁观，表现为「复活后和谁都交互不了」。
        step("恢复旁观者模式", () -> {
            for (PlayerExt p : getPlayers()) {
                Player pl = p.player();
                if (pl.isOnline() && pl.getGameMode() == GameMode.SPECTATOR) {
                    pl.setGameMode(GameMode.ADVENTURE);
                }
            }
        });
        if (borderTask != null) {
            borderTask.cancel();
            borderTask = null;
        }
        if (compassTask != null) {
            compassTask.cancel();
            compassTask = null;
        }
        if (mineralTask != null) {
            mineralTask.cancel();
            mineralTask = null;
        }
        if (bookTask != null) {
            bookTask.cancel();
            bookTask = null;
        }
        if (reviveTask != null) {
            reviveTask.cancel();
            reviveTask = null;
        }
        if (prepTask != null) {
            prepTask.cancel();
            prepTask = null;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        cancelTimeLimitTask();
        if (finalGlowTask != null) {
            finalGlowTask.cancel();
            finalGlowTask = null;
        }
        step("收起 BossBar", () -> {
            if (timeBossBar != null) {
                for (PlayerExt p : getPlayers()) {
                    p.player().hideBossBar(timeBossBar);
                }
                timeBossBar = null;
            }
        });
        ACTIVE_GAMES.remove(this);
        for (UUID groupId : voiceGroupIds) {
            VoiceGroupUtil.removeGroup(groupId);
        }
        voiceGroupIds.clear();
        step("还原世界边界", this::restoreBorder);
        step("清理掉落物", this::removeDroppedItems);
        // 原版的死亡掉落是在 PlayerDeathEvent 处理完之后才生成的；如果对局正是被这一死终结，
        // cleanup 会跑在掉落物生成之前。这里补扫一次（2 tick 后），把这类残留也清掉。
        if (CeasefireWar.getInstance() != null) {
            Bukkit.getScheduler().runTaskLater(CeasefireWar.getInstance(), this::removeDroppedItems, 2L);
            // 按需求：20 tick 后再补检一次，确认没有残留掉落物（死亡掉落可能晚几 tick 才落地）
            Bukkit.getScheduler().runTaskLater(CeasefireWar.getInstance(), this::removeDroppedItems, 20L);
        }
        step("还原方块", () -> blockTracker.restore(CeasefireWar.getInstance()));
        step("传送回主城", this::schedulePostGameTeleports);
    }

    /** 记录开局前该世界边界的状态，供对局结束时还原。 */
    private void snapshotBorder(CeasefireRoom room, World world, WorldBorder border) {
        if (!room.restoreBorderOnEnd) {
            return;
        }
        borderWorld = world;
        originalBorderSize = border.getSize();
        originalBorderCenter = border.getCenter();
        originalBorderWarningDistance = border.getWarningDistance();
        originalBorderWarningTicks = border.getWarningTimeTicks();
        borderTouched = true;
    }

    /**
     * 还原开局前的世界边界（大小 / 中心 / 警告距离与警告时间）。
     *
     * <p>Minecraft 会把世界边界写进 level.dat，不还原的话该世界会一直停在本局最后缩到的大小，
     * 影响同世界的其他对局、大厅，甚至服务器重启后的状态。
     * cancel / abort / end 三条清理路径共用本方法，{@code borderTouched} 保证只还原一次、
     * 且只在真的改动过边界时才还原（没缩圈的房间不会去盖掉服主原本的设置）。
     */
    private void restoreBorder() {
        if (!borderTouched) {
            return;
        }
        borderTouched = false;
        if (borderWorld == null) {
            return;
        }
        try {
            WorldBorder border = borderWorld.getWorldBorder();
            double before = border.getSize();
            // 按需求：结束一律回到「世界自带」的边界（level.dat 里的默认值，超级远），
            // 而不是回到开局前的快照 —— 快照可能本来就已经被上一局缩小过了。
            border.reset();
            logger.info("[Ceasefire] 已重置世界边界：" + before + " -> " + border.getSize());
            logger.info("已还原世界 %s 的边界（%s 格）".formatted(borderWorld.getName(), originalBorderSize));
        } catch (Exception e) {
            logger.warn("还原世界边界失败：%s".formatted(e.getMessage()));
        }
    }

    /**
     * 把局内玩家传送回主大厅。
     *
     * <p>框架结束对局时**不会移动玩家**（`solveGameEnd` 只做 `removeAllPlayer` + 房间置 READY），
     * 不补这一步玩家打完会原地站在竞技场里。
     *
     * <p>延迟 1 tick 执行：此刻框架还没执行 `removeAllPlayer()` 里的 `switchProfile(null)`，
     * 等大厅档案（物品栏 / 游戏模式 / 页脚）换好之后再传送，玩家就不会带着游戏状态落地。
     */
    private void teleportPlayersToLobby() {
        if (!(getGameRoom() instanceof CeasefireRoom room) || !room.teleportToLobbyOnEnd) {
            return;
        }
        Location lobby = MCZJUGameCore.getLobbyManager().getMainLobby();
        if (lobby == null || lobby.getWorld() == null) {
            logger.warn("主大厅尚未配置或世界未加载，本次不传送回大厅");
            return;
        }
        CeasefireWar plugin = CeasefireWar.getInstance();
        if (plugin == null) {
            return; // 关服过程中触发的中止：调度器已不可用，直接跳过
        }
        for (PlayerExt p : getPlayers()) {
            Player player = p.player();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.teleport(lobby)) {
                    p.sender().success("已传送回大厅");
                }
            }, 1L);
        }
    }

    /**
     * 清除场地内的掉落物（矿物、知识之书、玩家死亡/丢弃的物品等）。
     *
     * <p>生成任务本身会在 {@link #cleanup()} 里被取消，所以对局结束后**不会**再产生新的矿物/书；
     * 本方法处理的是「已经掉在地上的那些」——不清理的话它们会留在上一局的竞技场里，
     * 影响下一局的资源平衡（原版也要等 5 分钟才自行消失）。
     *
     * <p>安全范围：若房间配置了缩圈中心（{@code borderCenter}），只清理以该点为中心、
     * 初始边界半径（{@code borderInitialSize / 2}）以内的掉落物，
     * 避免误删同一世界里其他小游戏或大厅的物品；未配置缩圈中心时退回清理整个世界。
     */
    /**
     * 对局结束后把「所有人」送回主城：+1 / +5 / +20 tick 各跑一次。
     *
     * <p>为什么要重试：死亡与对局结束可能不在同一 tick（服务器卡顿时尤其明显），
     * 而**死亡界面里的玩家 {@code teleport()} 会失败** —— 只试一次就会出现
     * 「别人回主城了，他还卡在原地，谁都交互不了」。每次尝试都先 respawn() 再传送。
     */
    private void schedulePostGameTeleports() {
        if (!(getGameRoom() instanceof CeasefireRoom room) || !room.teleportToLobbyOnEnd) {
            return;
        }
        CeasefireWar plugin = CeasefireWar.getInstance();
        if (plugin == null) {
            return;
        }
        // 先抓名单：cleanup 之后框架会 removeAllPlayer()，那时 getPlayers() 已经是空的
        List<UUID> ids = getPlayers().stream().map(PlayerExt::getUniqueId).toList();
        for (long delay : new long[]{1L, 5L, 20L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendAllToLobby(ids), delay);
        }
    }

    /** 把名单里的在线玩家复活并传送到主大厅（支持跨维度）。 */
    private void sendAllToLobby(List<UUID> ids) {
        Location lobby = MCZJUGameCore.getLobbyManager().getMainLobby();
        if (lobby == null || lobby.getWorld() == null) {
            logger.error("[Ceasefire] 找不到主大厅（lobbies.json 缺少 main，或世界未加载）→ 玩家不会被传送");
            return;
        }
        int moved = 0;
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (p.getHealth() <= 0.0D) {
                p.spigot().respawn();   // 死亡界面里传送会失败，先复活
            }
            if (p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.ADVENTURE);
            }
            if (p.getWorld() == lobby.getWorld() && p.getLocation().distanceSquared(lobby) < 4.0D) {
                continue;               // 已经到位
            }
            if (p.teleport(lobby)) {
                moved++;
            }
        }
        logger.info("[Ceasefire] 收尾传送：本次 %d 人 / 名单 %d 人".formatted(moved, ids.size()));
    }

    private void removeDroppedItems() {
        if (!(getGameRoom() instanceof CeasefireRoom room) || !room.clearDroppedItemsOnEnd) {
            return;
        }
        World world = gameWorld;
        if (world == null) {
            return;
        }
        // 该世界里已经有新对局时不清理，避免误删新对局的矿物 / 知识之书。
        if (CeasefireGame.gameInWorld(world) != null) {
            logger.info("[Ceasefire] 跳过掉落物清理：该世界已有新对局");
            return;
        }
        Location center = room.borderCenter;
        // 0 = 清理整个世界（默认，竞技场为独立维度时推荐）；>0 才按缩圈中心做距离过滤。
        double radius = room.clearDroppedItemsRadius;
        boolean useRadius = radius > 0.0D && center != null && center.getWorld() == world;
        double radiusSq = radius * radius;

        int removed = 0;
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (useRadius) {
                Location loc = item.getLocation();
                double dx = loc.getX() - center.getX();
                double dz = loc.getZ() - center.getZ();
                if (dx * dx + dz * dz > radiusSq) {
                    continue; // 场外（可能是别的小游戏或大厅），不动
                }
            }
            item.remove();
            removed++;
        }
        if (removed >= 0) {
            logger.info("已清除场地内 %d 个掉落物".formatted(removed));
        }
    }
}
