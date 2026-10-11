package com.mczju.ceasefire.game;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.GameMeta;
import com.github.mczjuops.mczjugamecore.game.GameState;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.GameWaitStrategy;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
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
import com.mczju.ceasefire.player.CeasefirePlayerData;
import com.mczju.ceasefire.profession.Profession;
import com.mczju.ceasefire.room.CeasefireRoom;
import com.mczju.ceasefire.utils.SoundUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 停战之战（The War of Ceasefire）：2~4 队资源收集与团队对抗（最少 2 人，可 1v1 / 1v1v1 / 2v2）。
 * 已实现：方块记录与还原（含桶生成物）、缩圈与时间上限（决战时刻）、分队/队长、开局物品+指南针、
 * 多资源点矿物/知识之书生成、职业/知识之书双模式/职业配方、复活与团灭、击杀榜、断线判负。
 */
public final class CeasefireGame extends AbstractGame {

    /** 运行中的对局（按世界匹配方块事件归属），onGameStart 加入，cleanup 移除。 */
    private static final Set<CeasefireGame> ACTIVE_GAMES = ConcurrentHashMap.newKeySet();

    private final BlockChangeTracker blockTracker = new BlockChangeTracker();

    /** 本局所在世界（运行时字段，不序列化）。 */
    private World gameWorld;

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

    /** 是否处于决战时刻（时间上限到达后：关闭复活，死亡即淘汰）。 */
    private boolean suddenDeath = false;

    /** 是否已触发终局结算（防止重复结算）。 */
    private boolean finished = false;

    /** 已团灭的队伍编号集合。 */
    private final Set<Integer> wipedTeams = new HashSet<>();

    /** 复活倒计时任务句柄。 */
    private BukkitTask reviveTask;

    /** 单局击杀数（玩家 UUID -> 击杀数），用于局内排行榜。 */
    private final Map<UUID, Integer> roundKills = new HashMap<>();

    /** 排行榜侧边栏更新任务句柄。 */
    private BukkitTask scoreboardTask;

    /** 开局时间戳（复活时间随对局延长）。 */
    private long startTime;

    private static final String[] TEAM_PREFIXES = {
            "<red>[红队]</red> ",
            "<blue>[蓝队]</blue> ",
            "<yellow>[黄队]</yellow> ",
            "<green>[绿队]</green> "
    };

    /** 人数 -> 队伍数（自动适配）：2=1v1, 3=1v1v1, 4=2v2, 6=3v3, 8=2v2v2v2, 9=3v3v3, 10=5v5, 12=3v3v3v3；5/7/11 等其余人数禁止开局。 */
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

    /** 本局实际队伍数。 */
    private int activeTeamCount = 0;

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
        assignTeams();
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

    private void assignTeams() {
        teamMap.clear();
        List<PlayerExt> players = new ArrayList<>(getPlayers());
        if (players.isEmpty()) {
            return;
        }
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        int teamCount = getTeamCountForPlayers(players.size());
        if (teamCount < 0) {
            return; // 不该发生（tryStart 已检查）。
        }
        activeTeamCount = teamCount;

        players.sort((a, b) -> Double.compare(kd(b), kd(a)));

        List<PlayerExt> rest = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            if (i < teamCount) {
                teamMap.put(players.get(i).getUniqueId(), i);
            } else {
                rest.add(players.get(i));
            }
        }
        Collections.shuffle(rest);
        for (int i = 0; i < rest.size(); i++) {
            teamMap.put(rest.get(i).getUniqueId(), i % teamCount);
        }

        TabManager tab = MCZJUGameCore.getTabManager();
        for (PlayerExt p : players) {
            int team = teamMap.get(p.getUniqueId());
            tab.setPrefix(this, p, TEAM_PREFIXES[team % TEAM_PREFIXES.length]);
        }
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

    private double kd(PlayerExt player) {
        CeasefirePlayerData data = player.getData(CeasefirePlayerData.class);
        if (data.deaths <= 0) {
            return data.kills;
        }
        return (double) data.kills / data.deaths;
    }

    private void giveStartItems() {
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
        return points.stream().filter(loc -> loc != null && loc.getWorld() != null).distinct().toList();
    }

    /** 实际生效的知识之书生成点：合并新旧字段并去重。 */
    private List<Location> effectiveBookPoints() {
        CeasefireRoom room = (CeasefireRoom) getGameRoom();
        List<Location> points = new ArrayList<>(room.bookSpawnPoints);
        if (room.bookSpawnPoint != null) {
            points.add(room.bookSpawnPoint);
        }
        return points.stream().filter(loc -> loc != null && loc.getWorld() != null).distinct().toList();
    }

    private void startMineralSpawn() {
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

    /** 玩家死亡处理：KD 统计、复活等待或决战淘汰、团灭判定。 */
    public void handlePlayerDeath(PlayerExt player, PlayerDeathEvent event) {
        CeasefirePlayerData data = player.getData(CeasefirePlayerData.class);
        data.deaths++;
        data.setModified(true);

        Player killer = player.player().getKiller();
        if (killer != null) {
            PlayerExt killerExt = new PlayerExt(killer);
            if (killerExt.getGame() == this) {
                CeasefirePlayerData kd = killerExt.getData(CeasefirePlayerData.class);
                kd.kills++;
                kd.setModified(true);
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
            return;
        }

        int delay = computeReviveDelaySeconds();
        revivingPlayers.put(player.getUniqueId(), System.currentTimeMillis() + delay * 1000L);
        player.sender().info("<red>你被击倒了，<yellow>%d</yellow> 秒后复活".formatted(delay));
        SoundUtil.knockedDown(player.player());
        respawnAsSpectator(player, randomSpawnPoint());

        checkTeamWipe(getTeam(player));
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
            // 死亡屏幕中 health = 0；存活玩家不调用 respawn（对活人调用会触发重生点传送）。
            boolean onDeathScreen = p.getHealth() <= 0.0D;
            if (getState() == GameState.END) {
                if (onDeathScreen) {
                    p.spigot().respawn();
                }
                return;
            }
            if (onDeathScreen) {
                p.spigot().respawn();
            }
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
        p.setGameMode(GameMode.SURVIVAL);
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

    /** 复活物品：木板、木棍、羊毛、木剑、工作台（不含指南针，指南针每局仅一次）。 */
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
            if (!wipedTeams.contains(t)) {
                aliveTeams++;
                aliveTeam = t;
            }
        }
        if (aliveTeams == 1) {
            finished = true;
            sender().info("<gold>%s 获得胜利！".formatted(teamName(aliveTeam)));
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

    /** 中途退出：判负出局、比赛继续；场上无人时终止对局。 */
    public void handlePlayerQuit(PlayerExt player, PlayerQuitReason reason) {
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

    private String teamName(int team) {
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

    /** 局内击杀榜侧边栏，每秒刷新。 */
    private void startScoreboard() {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("kills", Criteria.DUMMY, Component.text("击杀榜"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (PlayerExt p : getPlayers()) {
            p.player().setScoreboard(board);
        }
        scoreboardTask = Bukkit.getScheduler().runTaskTimer(
                CeasefireWar.getInstance(), () -> updateScoreboard(board, obj), 0L, 20L);
    }

    private void updateScoreboard(Scoreboard board, Objective obj) {
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }
        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(roundKills.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int rank = 0;
        for (Map.Entry<UUID, Integer> e : sorted) {
            if (rank++ >= 15) {
                break;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                continue;
            }
            obj.getScore(p.getName()).setScore(e.getValue());
        }
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

    /** 正式开始：解除禁锢，发物品，启动缩圈/生成/复活/排行榜/时间上限。 */
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
        giveStartItems();
        startBorder();
        startCompassTask();
        startMineralSpawn();
        startBookSpawn();
        startReviveTask();
        startScoreboard();
        startTimeLimit();
        SoundUtil.gameBegin(getPlayers());
        for (PlayerExt p : getPlayers()) {
            p.player().showTitle(Title.title(
                    TextParser.parse("<gold><b>游戏开始</b>"),
                    TextParser.parse("<yellow>收集资源，解锁配方，合成装备"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        }
    }

    private void cleanup() {
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
        if (scoreboardTask != null) {
            scoreboardTask.cancel();
            scoreboardTask = null;
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
        if (timeBossBar != null) {
            for (PlayerExt p : getPlayers()) {
                p.player().hideBossBar(timeBossBar);
            }
            timeBossBar = null;
        }
        ACTIVE_GAMES.remove(this);
        for (UUID groupId : voiceGroupIds) {
            VoiceGroupUtil.removeGroup(groupId);
        }
        voiceGroupIds.clear();
        blockTracker.restore(CeasefireWar.getInstance());
    }
}
