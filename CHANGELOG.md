# CHANGELOG — Ceasefire_War 1.1

本文件记录 `Ceasefire_War1.1` 相对上游 `Ceasefire_War` 的全部差异。
由于副本不含新的 git 提交记录（`.git` 是从上游整份复制的），本文件是回顾改动的唯一依据。

| 项 | 值 |
|---|---|
| 基线 | `mczju-ops/Ceasefire_War`，分支 `master`，commit `ce96fb78` |
| 本副本 | `Ceasefire_War1.1`（artifactId `ceasefire-war`，version `0.1.0`） |
| 目标平台 | Paper 26.2 / Java 25 |
| 依赖 | `io.papermc.paper:paper-api:26.2.build.133-stable`（provided）、`com.github.mczju-ops:MCZJUGameCore:1.1.2`（provided） |
| 构建 | `mvn -B package`（Maven 工程，无 Gradle） |
| 源码规模 | 改动前 14 个 Java 文件 / 1243 行主类；改动后 17 个 Java 文件 |

---

## 变更总览

| # | 需求 | 状态 |
|---|---|---|
| R1 | 删除持久化 KD 统计与「按 KD 选队长」 | ✅ |
| R2 | 分队改为等待阶段自选队伍，未选者开局随机补齐 | ✅ |
| R3 | 保留每局击败/死亡统计；删除实时击杀榜；结束在聊天框播报 | ✅ |
| R4 | 在职业菜单里显示队友已选职业 | ⏭️ 用户明确要求跳过 |
| R5 | 新增第二类矿物/知识之书生成点，仅 4 队局叠加生效 | ✅ |
| R6 | 矿物间隔改 4 秒，铁/金/钻权重改 50/30/20 | ✅ |
| R7 | 追加：队伍人数上限、禁止空队伍、7→6 裁队规则确认 | ✅ |
| R8 | 追加：`.git` 的 origin 改指向 `Ceasefire_War1.1` | ✅ |
| R9 | 追加：队伍超员时禁止开局，并向局内全体播报 | ✅ |
| R10 | 追加：审计所有对话框提示的发送范围 | ✅（见「对话框提示归属审计」） |
| R11 | 追加：选队物品固定在快捷栏第 5 格 | ✅ |
| R12 | 追加：开局与复活发金胡萝卜、开局发金苹果（均可配置数量） | ✅ |
| R13 | 追加：合成改为白名单制（只有职业配方池可合成），A+B 双保险 | ✅ |
| R14 | 追加：组队（MGC party）整队进入等待房后自动解散，超员整队拒绝 | ✅ |

---

## R1 — 删除持久化 KD 统计与按 KD 选队长

**删除**

- `src/main/java/com/mczju/ceasefire/player/CeasefirePlayerData.java` —— 整个文件删除
- `CeasefireWar.onEnable()` 里对 `MCZJUGameCore.getPlayerDataManager().registerPlayerData(...)` 的调用与相关 import
- `CeasefireGame.kd(PlayerExt)` 方法
- `CeasefireGame.assignTeams()` 中的 `players.sort((a, b) -> Double.compare(kd(b), kd(a)))`

**影响**

- 分队不再参考历史战绩，改为纯自选 + 随机补齐（见 R2）
- 服务器上遗留的 `plugins/MCZJUGameCore/player_data/the_war_of_ceasefire/*.json` **不会自动删除**，也不会再被读取。如需清理请手动删除该目录。

---

## R2 — 等待阶段自选队伍 + 动态队伍数

**架构变化**：分队逻辑从 `onGameStart()` 提前到**等待阶段**。

- 原 `assignTeams()` 被替换为：
  - `refreshWaitingTeams()` —— 等待阶段重算队伍数，处理队伍数下降
  - `finalizeTeams()` —— 开局时随机补齐未选队者并刷新 Tab 前缀
  - `smallestTeam()` —— 选出人数最少的队（并列随机），保持均衡

**队伍数规则**

| 人数 | 队伍数 |
|---|---|
| 2 / 3 / 4 / 6 / 8 / 9 / 10 / 12 | 2 / 3 / 2 / 2 / 4 / 3 / 2 / 4（与原表一致，未改动） |
| 5 / 7 / 11 | **沿用上一次有效队伍数** |

**队伍数下降时的处理**（`refreshWaitingTeams()`）

```
teamMap.removeIf(entry -> entry.getValue() >= activeTeamCount);
sender().warn("<yellow>队伍数发生变化，请重新选择队伍。");
giveTeamSelectItems();
```

即：被裁掉的那些队里的已选玩家会被移出队伍（回到未选状态），并重新发放选队物品。

**已确认的规则实例**

- `9 人(3 队) → 10 人(2 队)`：裁掉 第三队，第三队已选玩家被移出
- `8 人(4 队) → 7 人(沿用 4 队) → 6 人(2 队)`：裁掉 第三队 与 第四队，两队已选玩家被移出

**选队入口（两种都提供）**

| 方式 | 说明 |
|---|---|
| GUI | 右键手里的 **「选择队伍」** 物品（`NETHER_STAR`） |
| 命令 | `/ceasefire team`（打开菜单）/ `/ceasefire team red\|blue\|yellow\|green`（直接入队），别名 `/cf` |

**未选队伍者**：`finalizeTeams()` 中先 `Collections.shuffle` 打乱，再逐个放入人数最少的队。

---

## R3 — 每局击败/死亡统计；删除实时击杀榜；结束播报

**删除**

- `CeasefireGame.startScoreboard()` 与 `updateScoreboard()` 两个方法
- 字段 `scoreboardTask` 及其在 `cleanup()` 中的取消逻辑
- `startGameplay()` 中的 `startScoreboard()` 调用
- 导入：`org.bukkit.scoreboard.{Criteria,DisplaySlot,Objective,Scoreboard}`、`net.kyori.adventure.text.Component`

**新增**

- 字段 `roundDeaths`（`Map<UUID,Integer>`，仅内存）；`roundKills` 保留
- `handlePlayerDeath()` 中改为 `roundDeaths.merge(...)` / `roundKills.merge(...)`，不再读写持久化数据
- `broadcastRoundStats()` —— 在 `checkWin()` 判定出胜者后、`endGame()` 之前调用
- `nameOf(UUID)` —— 取玩家名，离线且无记录时退化为 UUID 前 8 位

**播报格式**

```
本局战绩
AniloA/K3/D1
Someone/K0/D2
```

按队伍编号分组，同队内按击败数降序，再按名称排序。参与者取 `teamMap.keySet()`（含中途退出者）。

> **注意**：MGC 里**没有**现成的每局 K/D 播报代码。`score/ScoreManager.java` 是空类（`public class ScoreManager {}`），`LeaderboardManager` 是持久化文本展示实体排行榜，均不适用。以上为自写实现。

**附带修复**：原「计分板不还原」问题（对局结束后玩家侧边栏仍显示旧击杀榜）随计分板一并消失。

---

## R4 — 跳过

用户明确要求本轮不实现「在职业菜单里显示队友已选职业」。
`ProfessionMenu` 与 `RecipeChoiceMenu` 未做任何改动。

（技术备注：Minecraft 的箱子 GUI 没有「光标悬停」事件，只能靠定时刷新或事件驱动刷新 lore 来近似。）

---

## R5 — 第二类生成点（叠加生效）

**`CeasefireRoom` 新增两个 op 可编辑字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `mineralSpawnPoints2` | `List<Location>` | 第二类矿物生成点 |
| `bookSpawnPoints2` | `List<Location>` | 第二类知识之书生成点 |

**生效条件**：`CeasefireGame.secondPointsActive()` 返回 `activeTeamCount == 4`。

`effectiveMineralPoints()` / `effectiveBookPoints()` 在条件成立时把第二类点**追加**到第一类之后（叠加，不是替代），再统一去重。

---

## R6 — 矿物参数

`CeasefireRoom` 默认值变更：

| 字段 | 原值 | 新值 |
|---|---|---|
| `mineralIntervalSeconds` | 10 | **4** |
| `ironWeight` | 70 | **50** |
| `goldWeight` | 25 | **30** |
| `diamondWeight` | 5 | **20** |

即实际概率由 70% / 25% / 5% 变为 **50% / 30% / 20%**。
（权重是相对权重，总和不必为 100；实际概率 = 各自权重 ÷ 三者之和。）

---

## R7 — 追加需求

### R7-1 队伍人数上限

新增 `CeasefireGame.teamCapacity()`：

```java
public int teamCapacity() {
    if (activeTeamCount <= 0) return 0;
    int players = getPlayers().size();
    return (players + activeTeamCount - 1) / activeTeamCount;  // 向上取整
}
```

`selectTeam()` 在入队前拦截，超限时提示 **`队伍人数已达上限。`**

| 人数 | 队伍数 | 每队上限 |
|---|---|---|
| 2 | 2 | 1 |
| 3 | 3 | 1 |
| 4 | 2 | 2 |
| 6 | 2 | 3 |
| 8 | 4 | 2 |
| 9 | 3 | 3 |
| 10 | 2 | 5 |
| 12 | 4 | 3 |

**示例**：9 人 3 队，某队已有 3 人时第 4 人申请入队 → 驳回并提示「队伍人数已达上限。」

### R7-2 禁止空队伍

向上取整的上限保证 `上限 × 队伍数 ≥ 总人数`，因此**空队伍在数学上不可能出现**
（若某队为空，其余 `t-1` 队最多容纳 `(t-1)×ceil(n/t) < n`，矛盾）。

加固措施：

- `smallestTeam()` 现在**跳过已满的队**，随机补位不会把人塞进满队
- 新增 `ensureNoEmptyTeams()` 兜底：万一出现空队，从人数最多的队（且该队人数 > 1）调一人过去

### R7-3 7→6 裁队规则确认

经核对，**原实现已符合要求，无需改动**：

```
8 人 → 4 队
7 人 → 沿用 4 队（队伍数未变，不提示）
6 人 → 映射表 6→2 队 → 判定下降 → removeIf(队伍编号 >= 2)
        → 移除 第三队(编号 2) 与 第四队(编号 3) 的已选玩家
        → 提示「队伍数发生变化，请重新选择队伍。」
```

---

## R8 — `.git` origin 改指向

`.git/config` 中：

```diff
 [remote "origin"]
-	url = https://github.com/mczju-ops/Ceasefire_War.git
+	url = https://github.com/mczju-ops/Ceasefire_War1.1.git
```

原因：副本是由整个文件夹复制而来的，`.git` 一并复制，remote 仍指向**上游原库**。
不改的话在副本里 `git push` 会把提交推到 `mczju-ops/Ceasefire_War`。

> ⚠️ 需先在 GitHub 上创建空仓库 `mczju-ops/Ceasefire_War1.1`，否则 push 会报 `repository not found`。

---

## R9 — 队伍超员时禁止开局

**背景**：`teamCapacity()` 只在「选队那一刻」强制。人数减少会让上限变小，已在队里的玩家不会被自动移出，于是可能出现短暂的超员。
例：9 人 3 队（上限 3）→ 走 1 人 → 8 人 4 队（上限 2）→ 原来 3 人的队此时超员。

**实现**

新增 `CeasefireGame.hasTeamOverCapacity()`：

```java
public boolean hasTeamOverCapacity() {
    if (activeTeamCount <= 0) return false;
    int capacity = teamCapacity();
    for (int team = 0; team < activeTeamCount; team++) {
        if (getTeamPlayers(team).size() > capacity) return true;
    }
    return false;
}
```

在 `CeasefireWaitStrategy.tryStart()`（**唯一**的开局入口，`/mgc start` 走这里）最前面拦截：

```java
if (game instanceof CeasefireGame ceasefire && ceasefire.hasTeamOverCapacity()) {
    game.sender().warn("队伍人数不正确，无法开始");   // GameSender → 局内全体
    return;
}
```

**要点**

- 使用 `game.sender()`（`GameSender`），按需求**广播给局内全体玩家**，不只是发起者
- 检查放在人数合法性检查**之前**：只要超员就先报这一条，保证该状态不会被其它提示掩盖
- 超员状态需玩家重新选队才能解除；本次**不做自动再均衡**（符合「不可开始游戏」的语义）
- `tryStart()` 是唯一开局路径：`CeasefireWaitStrategy.onPlayerJoin` 已重写、不再调用 `DefaultGameWaitStrategy.onJoin` 的自动开局

---

## R10 — 对话框提示归属审计

### 发送方式与作用范围

| 发送方式 | 实际范围 | 依据 |
|---|---|---|
| `player.sender()`（`PlayerExt`） | **仅该玩家** + 写控制台日志 | MGC `PlayerSender.send()` → `player.sendMessage(...)` |
| `player.sendMessage(Component)` | **仅该玩家**（不写日志） | Bukkit 原生 |
| `sender()` / `game.sender()`（`AbstractGame`） | **局内全体玩家** | MGC `GameSender.send()` → `for (PlayerExt p : game.getPlayers()) p.player().sendMessage(...)` |
| `party.getLeader().sender()` | **仅队长** | `PlayerSender` |
| `logger.warn(...)`（`ConsoleSender`） | **仅控制台** | MGC `ConsoleSender` |

> 注意：`GameSender` 遍历的是 `game.getPlayers()`（由 `PlayerManager` 反查）。**已退出/已被移出对局的玩家收不到广播**，即使他们仍在服务器上。

### 逐条审计结果

**仅发给单个玩家 —— 全部正确，无泄漏**

| 位置 | 文案 |
|---|---|
| `CeasefireGame:299` | 你选择了职业：X |
| `CeasefireGame:327` | 请先选择职业再使用知识之书 |
| `CeasefireGame:342` | 你已经解锁了本职业的全部配方 |
| `CeasefireGame:369` | 解锁配方：X |
| `CeasefireGame:576` | 对局已经开始，无法再更换队伍 |
| `CeasefireGame:580` | 当前人数还不足以确定队伍数，请等待更多玩家加入 |
| `CeasefireGame:584` | 本局只有 N 支队伍，无法加入该队 |
| `CeasefireGame:590` | 你已经在 X 了 |
| `CeasefireGame:596` | 队伍人数已达上限。 |
| `CeasefireGame:869` | 你在决战中阵亡，已被淘汰 |
| `CeasefireGame:879` | 你被击倒了，N 秒后复活 |
| `CeasefireGame:950` | 你复活了 |
| `CeasefireGame:1037` | 你的队伍已团灭，你被淘汰（循环内逐人发） |
| `CeasefireGame:1207` | 决战时刻来临，你未能复活，已被淘汰（循环内逐人发） |
| `CeasefireWaitStrategy:26` | 该游戏人数已满（最多 N 人） |
| `CeasefireWaitStrategy:39` | 该游戏请单独加入，进入等待后可自行选择队伍（发给队长） |
| `GamePlayListener:88` | 你尚未解锁该配方 |
| `GamePlayListener:103` | 咬人斧诞生了！ |
| `TeamCommand:71` | 未知队伍，可用：red / blue / yellow / green |
| `TeamCommand:85` | 该命令只能由玩家执行 |
| `TeamCommand:92` | 你当前不在停战之战的等待房间中 |

**广播给局内全体 —— 均为设计上有意为之**

| 位置 | 文案 | 说明 |
|---|---|---|
| `CeasefireGame:448` | 队伍数发生变化，请重新选择队伍。 | 需要全体重选 |
| `CeasefireGame:508` | X 被调整到 Y | 空队兜底路径，正常不触发 |
| `CeasefireGame:600` | X 离开了 Y队 | 等待房内公开队伍变化 |
| `CeasefireGame:604` | X 加入了 Y队 | 等待房内公开队伍变化 |
| `CeasefireGame:1042` | X 团灭！ | 全体需知 |
| `CeasefireGame:1064` | X 获得胜利！ | 全体需知 |
| `CeasefireGame:1094/1096` | 本局战绩 / 玩家名/K/D | 全体需知 |
| `CeasefireGame:1126` | X 掉线/主动退出，其队伍失去一名队员 | 全体需知 |
| `CeasefireGame:1173` | 距离决战时刻还有 X | 全体需知 |
| `CeasefireGame:1223` | 决战时刻！… | 全体需知 |
| `CeasefireGame:1267` | 你有 30 秒选择职业，随后游戏开始 | 全体需知 |
| `CeasefireGame:1322` | 所有人已选完职业，N 秒后开始 | 全体需知 |
| `CeasefireWaitStrategy:45` | 玩家 X 退出了等待 | 全体需知 |
| `CeasefireWaitStrategy:62` | 当前 N 人无法开始，可开局人数：… | 全体需知 |
| `CeasefireWaitStrategy`（R9 新增） | 队伍人数不正确，无法开始 | 全体需知 |

**仅写控制台**

| 位置 | 文案 | 评价 |
|---|---|---|
| `CeasefireGame:216` | 初始化失败：分配到的房间不是 CeasefireRoom | ⚠️ 玩家侧无提示 |
| `CeasefireGame:220` | 初始化失败：房间未配置出生点 spawnPoints | ⚠️ 玩家侧无提示 |
| `CeasefireGame:698` | 创建语音组失败：… | ✅ 适当 |

### 审计结论

**没有发现「本应只发给某个玩家、却被广播」的情况。** 所有针对单个玩家的提示都只到达该玩家。

同时发现 3 处**非错误但值得注意**的地方（本次未改动）：

1. **`selectTeam()` 的「加入了 / 离开了」是全体广播**（`CeasefireGame:600/604`）。
   设计上让等待房所有人都能看到队伍变化；如果你希望它只通知本人，需要改成 `player.sender()`。
2. **`onGameInit()` 失败只写控制台，玩家侧无任何提示**。
   房间没配 `spawnPoints` 时，玩家点击加入会静默失败（`createGame` 返回 null，MGC 只会提示「该游戏没有空闲的房间」或什么都不说）。建议后续补一条面向玩家的提示。
3. **`GamePlayListener` 的两条提示用 Bukkit 原生 `player.sendMessage(...)`**，不写控制台日志，与其余都走 `PlayerExt.sender()` 的风格不一致。功能上无差异（两者都只发给该玩家，且 MiniMessage 颜色都已显式指定）。

---

## R11 — 选队物品固定在快捷栏第 5 格

**需求**：选队物品（下界之星）固定放在快捷栏第 5 格，玩家无法移动它。

**实现方式**：完全参照 **MGC 自身的 `LobbyMenuClock`**（把菜单钟固定在快捷栏第 9 格）——同一套事件拦截与同样的 `ensureInFixedSlot` 语义，只是槽位不同。

### `TeamSelectItem` 新增

| 成员 | 说明 |
|---|---|
| `FIXED_SLOT = 4` | 0 基下标，4 = 快捷栏第 5 格 |
| `isTeamItem(ItemStack)` | 辨识封装（转发 `ItemManager#is`） |
| `ensureInFixedSlot(Player)` | 清除背包中所有副本 → 在固定槽位放一个新的 → 该格原有物品转入空位，放不下则掉落到脚下 |
| `createRawItem()` 增加 `.maxStackSize(1)` | 与 MGC 的菜单钟一致，避免堆叠 |

`ensureInFixedSlot` 遍历的是 `PlayerInventory#getSize()`（41 = 36 主背包 + 4 护甲 + 1 副手），
所以**副手里的副本也会被清除**——此前遗留的「副手可能漏清」缺口顺带闭合。

### `TeamSelectListener` 的事件拦截（1 个改写 + 6 个新增）

| 事件 | 处理 |
|---|---|
| `PlayerInteractEvent` | 右键 → 取消原事件 + 归位 + 打开选队菜单 |
| `PlayerInteractAtEntityEvent` | 右键实体 → 同上 |
| `InventoryClickEvent` | 点击到该物品 / `NUMBER_KEY` 换位 / `SWAP_OFFHAND` 换到副手 → 取消 |
| `InventoryDragEvent` | 拖拽该物品 → 取消 |
| `PlayerDropItemEvent` | 丢弃 → 取消 |
| `PlayerSwapHandItemsEvent` | F 键换手 → 取消 |
| `InventoryCreativeEvent`（`EventPriority.LOWEST`） | 创造模式物品栏取出 → 取消 |

### `CeasefireGame` 相应调整

`giveTeamSelectItem(PlayerExt)` 由 `player.giveItemIfDontHave(ID)`（只会塞进第一个空位）
改为 `TeamSelectItem.ensureInFixedSlot(player.player())`。

### 与 MGC 参考实现的三处有意差异

| 项 | MGC `LobbyMenuClock` | 本插件 `TeamSelectItem` |
|---|---|---|
| 固定槽位 | `8`（快捷栏第 9 格） | **`4`（快捷栏第 5 格）** |
| 右键动作 | `player.performCommand("mgc")` | `new TeamSelectMenu(...).open()` |
| 点击背包内的物品 | 取消并**打开菜单**（下一 tick） | **只取消**，不在背包界面里弹出箱子菜单 |

### 说明

- 固定是「事件层拦截 + 每次右键/发放时归位」，不是一个持续运行的守护任务；因此若玩家通过本插件未拦截的途径挪动它（目前未发现此类途径），下次右键会自动归位
- 开局时 `removeTeamSelectItems()` 仍从 36 个主背包格中清除，第 5 格在其中，选队物品照常被收回
- 该物品只在等待阶段有用；开局后即使残留也无法打开菜单（`selectTeam()` 会拒绝非 `WAITING` 状态）

---

## R12 — 食物：开局与复活发金胡萝卜、开局发金苹果

**需求**：开局（出生）与每次复活都额外发金胡萝卜；开局额外发金苹果，且每局仅能获得一次；两者数量都可配置。

> **需求更正记录**：原始表述为「每次复活发金胡萝卜 / 出生发金苹果」，用户随后更正为「**出生也要金胡萝卜**」。
> 因此金胡萝卜改为**开局与复活共用同一个配置项** `goldenCarrots`。

### `CeasefireRoom` 新增两个 op 可编辑字段

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `startGoldenApples` | int | **1** | **开局**发放的金苹果数量（复活不发）；`0` = 不发放 |
| `goldenCarrots` | int | **3** | 金胡萝卜数量，**开局与每次复活都会发放**；`0` = 不发放 |

两个字段都是 `public int` + `@FieldDescription`，可通过 `/mgcop room edit ceasefire <房名>` 直接编辑。

### `CeasefireGame` 改动

| 代码块 | 说明 |
|---|---|
| 新增字段 `startItemsGranted` | 记录本局是否已发放过开局物品 |
| `giveStartItems()` 开头新增幂等守卫 | `if (startItemsGranted) return;` + `startItemsGranted = true;` |
| `giveStartItems()` 内新增金苹果发放 | `Material.GOLDEN_APPLE` × `room.startGoldenApples`（仅当 `> 0`） |
| `giveStartItems()` 内新增金胡萝卜发放 | `Material.GOLDEN_CARROT` × `room.goldenCarrots`（仅当 `> 0`） |
| `giveRespawnItems()` 内新增金胡萝卜发放 | `Material.GOLDEN_CARROT` × `room.goldenCarrots`（仅当 `> 0`） |
| `giveRespawnItems()` 方法注释更新 | 注明「不含指南针与金苹果，二者每局仅一次」 |

### 「每局仅可获得一次」是怎么保证的

**双重保证**：

1. **流程层面**：`giveStartItems()` 全文件**只有 1 个调用点**——`startGameplay()` 内；而 `startGameplay()` 的两个调用者（`tickPrep()` 与 `startCountdown()`）都会先取消对方的定时任务，因此整局只执行一次
2. **代码层面**：新增 `startItemsGranted` 幂等守卫，即使将来流程改动导致重复调用，开局物品（含金苹果）也不会重复发放

### 说明

- 金苹果**没有**放进 `giveRespawnItems()`，所以复活不会补发——这与该方法原本就不发指南针的处理保持一致
- 两种食物都通过 `player.getInventory().addItem(...)` 发放，**背包满时会掉落在脚下**（与其余开局物品行为一致）
- 数量填 `0` 即不发放该物品；没有上限限制

---

## R13 — 合成白名单（方案 A + B）

**需求**：把合成从「黑名单式」改为「**白名单式**」——只有职业配方池里的物品可以被合成，其余一律禁止。按此前商定的 **A + B 双保险**方案实现。

### 方案 A：`onCraft()` 白名单拦截（兜底 + 文字提示）

`GamePlayListener.onCraft()` 的判定由原来的「池内且未解锁才拦」改为三段：

| 情况 | 处理 |
|---|---|
| 产物**不在**职业配方池（24 种）中 | ❌ `setCancelled(true)` + 提示「该物品不在可合成列表中」 |
| 在池内但**未解锁** | ❌ `setCancelled(true)` + 提示「你尚未解锁该配方」 |
| 在池内且已解锁 | ✅ 放行，进入原有的咬人斧 / 附魔 / 末影珍珠流程 |

> 因为 `hasRecipe()` 只可能对**玩家自己职业**的配方返回 `true`，所以该白名单**等价于**「只能合成自己职业已用知识之书解锁的配方」。

### 方案 B：`onPrepareCraft()` 清空合成栏结果（体验）

新增 `PrepareItemCraftEvent` 监听器：

```java
ItemStack result = event.getInventory().getResult();
if (result != null && !Profession.isProfessionRecipe(result.getType())) {
    event.getInventory().setResult(null);   // 合成栏直接不显示产物
}
```

玩家把不在池内的材料放进合成栏时**看不到产物**，从源头点不下去；A 作为兜底。

### 只作用于局内

两个监听器都先判断 `new PlayerExt(player).getGame() instanceof CeasefireGame`，**大厅等场景不受影响**。

### ⚠️ 生效后**不能合成**的物品（重要）

白名单只有 **24 种**（钻石 / 金 / 铁的 斧·剑·镐·四件甲，再加铁桶、盾、剪刀），**其余全部被禁**：

| 被禁的常见物品 | 影响 |
|---|---|
| **金苹果** | 玩家无法自制，只能用开局那 1 个 |
| **金胡萝卜** | 玩家无法自制，只能靠开局 / 复活发放 |
| 木板、木棍、工作台 | 不能合成（开局分别发 16 / 16 / 1 个） |
| 箱子、熔炉、火把、梯子、船、门、告示牌 | 全禁 |
| 铁块 / 金块 / 钻石块 | 全禁 |
| 面包、铁砧、盔甲架、床 | 全禁 |

> **与 R12 存在冲突**：R12 让金苹果 / 金胡萝卜可配置发放，但 R13 之后玩家**再也无法自己合成**它们。
> 若需要保留这两种食物的合成，把白名单改成「职业配方池 ∪ 额外集合」即可（约 3 行）。

### 已知体验取舍

方案 B 生效时，玩家只会看到「合成栏没有产物」，**收不到文字说明**（结果已被清空，`CraftItemEvent` 不再触发）。
方案 A 的提示只在「有产物但仍被拒」的路径（例如其他插件注入、配方书直合）才会出现。

### 顺带修正

`onCraft()` 原有的两条提示由 `player.sendMessage(TextParser.parse(...))` 改为 **`ext.sender().error(...)`**，
与 MGC 框架的 `Sender` 体系保持一致（并因此获得控制台日志）。

---

## R14 — 组队（MGC party）适配：整队进入后自动解散

**需求**：
1. party 加入等待房后**自动解散 party**，所有人处于「待选队」状态
2. 若 party 加入会导致人数**超过**上限，则**整队拒绝**

### `CeasefireWaitStrategy.onPartyJoin()` 改写

原本无条件拒绝组队，现改为：

| 情况 | 处理 |
|---|---|
| 全队预注册后总人数 **> 12** | ❌ **整队拒绝** + 全队提示 + **档案切回大厅** |
| 否则（含正好 12） | ✅ 接受，交给 `acceptParty()` |

**两个必须遵守的点**：

1. **不调用 `super.onPartyJoin()`** —— 框架 `DefaultGameWaitStrategy.onJoin()` 在 `size == playerLimit` 时会**自动开局**，而本局设计要求等 `/mgc start`
2. **拒绝时必须自己调 `switchProfile(null)`** —— MGC 的 `JOIN_FAIL` 回滚在 `switchProfile(null)` **之前**就 `return` 了；不补这一步会让整队卡在游戏档案（物品栏看起来被清空，且再点加入同一游戏无法恢复）

新增私有辅助方法 `rejectParty(party, message)` 封装这两件事。

### `CeasefireGame.acceptParty(Party)` 新增

1. `refreshWaitingTeams()` —— 按**含全队的新人数**重算队伍数与每队上限，并给所有人补发选队物品
2. `MCZJUGameCore.getPartymanager().disband(party.getLeader())` —— **立刻解散队伍**
3. 全体播报：「一支 N 人的队伍加入了等待房，队伍已自动解散」+「请各自选择队伍」

**「待选队」状态不需要额外代码**：解散后这些玩家不在 `teamMap` 中，`getTeam()` 返回 `-1`；
`refreshWaitingTeams()` 只**移除**越界的已选玩家、从不新增归属；开局时 `finalizeTeams()` 会用 `smallestTeam()` 随机补齐仍未选队的人。

### 上限判定用 `>` 而不是 `>=`

| 现有 + party | 合计 | 结果 |
|---|---|---|
| 5 + 8 | 13 | ❌ 拒绝 |
| **4 + 8** | **12** | ✅ **接受**（房间正好满员） |
| 11 + 1 | 12 | ✅ 接受 |

理由：与现有单人加入规则一致（`onPlayerJoin` 就是 `size > MAX_PLAYERS`），且 12 是 `isValidPlayerCount()` 认可的合法开局人数。

### 行为变化

| 行为 | 之前 | 之后 |
|---|---|---|
| party 能否进等待房 | ❌ 一律拒绝 | ✅ 能（合计 ≤ 12 时） |
| 队长等待阶段 `/mgc leave` | 带全队一起退（框架级联） | **只退自己**（party 已散，`isPartyLeader()` 为假） |
| 一局打完 | party 仍在，可继续一起玩 | **party 已散**，需重新邀请 |
| 语音组 | 按队伍建（`ceasefire-team-<n>`） | **不变**（MGC 的 party 代码不碰语音组，已 grep 确认） |
| Tab | 只有「玩家名 + [游戏名]」 | **不变** |

### 已知限制

- **大队伍不可能同队**：8 人 → 4 队 → 每队上限 `ceil(8/4) = 2`，所以 8 人最多 2 人一队，需玩家自行协调分组
- 未做「party 成员锁定同队」，也未做「超大队自动拆分」（框架有 `PartyManager.splitParty()` 可用）
- 解散后原队友关系不再保留，需要重新邀请

---

## 文件清单

### 新增（4）

| 文件 | 作用 |
|---|---|
| `game/TeamSelectListener.java` | 右键「选择队伍」物品打开选队菜单 |
| `game/TeamCommand.java` | `/ceasefire team` Brigadier 命令节点 |
| `game/menu/TeamSelectMenu.java` | 选队 GUI（4 队各一格，显示成员与人数上限） |
| `item/TeamSelectItem.java` | 选队物品，PDC ID `ceasefire:team_select` |

### 删除（1）

| 文件 | 原因 |
|---|---|
| `player/CeasefirePlayerData.java` | 持久化 KD 已移除 |

### 修改（6）

| 文件 | 变更 |
|---|---|
| `CeasefireWar.java` | 移除 `registerPlayerData`；注册选队物品、`TeamSelectListener`、`/ceasefire` 命令 |
| `game/GamePlayListener.java` | 合成改为白名单制（R13）：新增 `onPrepareCraft()`，改写 `onCraft()` 的判定 |
| `game/CeasefireGame.java` | 主要改动：删除 KD/计分板；新增等待阶段分队、队伍人数上限、超员检测、结束播报、第二类生成点；食物发放（R12）；`acceptParty()` 整队加入后解散队伍（R14） |
| `game/CeasefireWaitStrategy.java` | join/leave 时调用 `refreshWaitingTeams()`，入队时发选队物品；`tryStart()` 增加超员开局拦截；`onPartyJoin()` 改为接受组队并自动解散 + 超员整队拒绝（R14） |
| `game/menu/TeamSelectMenu.java` | 显示 `人数：X/Y` 与「该队已满」（本轮追加） |
| `room/CeasefireRoom.java` | 新增第二类生成点字段；改矿物间隔与权重默认值 |
| `resources/plugin.yml` | 新增 `permissions.ceasefire.team`（默认 true） |

---

## 房间配置项（op 可通过 `/mgcop room edit ceasefire <房名>` 编辑）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `mineralIntervalSeconds` | int | **4** | 矿物生成间隔（秒），下限 1 |
| `ironWeight` / `goldWeight` / `diamondWeight` | int | **50 / 30 / 20** | 相对权重 |
| `mineralSpawnPoints` / `mineralSpawnPoint` | List / Location | 空 | 第一类矿物生成点（列表与旧单点字段会合并去重） |
| `mineralSpawnPoints2` | List | 空 | **第二类**矿物生成点，仅 4 队局叠加生效 |
| `bookSpawnPoints` / `bookSpawnPoint` | List / Location | 空 | 第一类知识之书生成点 |
| `bookSpawnPoints2` | List | 空 | **第二类**知识之书生成点，仅 4 队局叠加生效 |
| `bookIntervalSeconds` | int | 20 | 知识之书生成间隔（秒） |
| `spawnPoints` | List | 空 | 出生点，**至少 4 个**（`onGameInit` 会校验非空） |
| `bookChooseThree` | boolean | true | 知识之书三选一开关 |
| `biteAxeLimit` | int | 1 | 每队咬人斧上限 |
| `biteAxeChancePercent` | int | 10 | 金斧变咬人斧概率 |
| `enderPearlChancePercent` | int | 15 | 钻石职业合成返末影珍珠概率 |
| `startGoldenApples` | int | **1** | **开局**发放的金苹果数量，每局仅一次（复活不发）；`0` = 不发放 |
| `goldenCarrots` | int | **3** | 金胡萝卜数量，开局与每次复活都发放；`0` = 不发放 |

---

## 命令与权限

| 命令 | 权限 | 默认 | 说明 |
|---|---|---|---|
| `/ceasefire team`（别名 `/cf`） | `ceasefire.team` | true | 打开选队菜单 |
| `/ceasefire team <red\|blue\|yellow\|green>` | `ceasefire.team` | true | 直接入队 |

`/mgc start` 仍由 MGC 提供，需由**等待中的玩家**执行才能开局（MGC 的既有约束，非本次改动）。

---

## 行为细节与边界

- **分队时机**：等待阶段随人数变化实时重算；开局时 `finalizeTeams()` 做最后一次补齐
- **选队已在开局后禁用**：`selectTeam()` 在非 `WAITING` 状态直接拒绝
- **选队物品在开局时被收回**：`removeTeamSelectItems()` 清除背包中的该物品
- **空队不参与胜负判定**：`checkWin()` 跳过 `getTeamPlayers(t).isEmpty()` 的队（否则自选队伍导致的空队会「获胜」）
- **播报时机**：仅在 `checkWin()` 判定出胜者时播报。若对局因玩家全部退出而中止（`abortGame`），无人可播报，不输出
- **矿物生成只投放到已加载区块**，未加载的那一次跳过、不补投

---

## 部署注意

1. **需先执行 `mvn -B package`**。本次交付只做了 `javac` 编译验证（本机无 Maven），未实际打包。请自行确认构建通过。
2. **需在 GitHub 创建 `mczju-ops/Ceasefire_War1.1` 空仓库**，否则 push 失败。
3. 服务端需安装 **MCZJUGameCore**（1.1.2 或 1.1.3 均可；1.1.3 是 1.1.2 的严格超集，无类被移除）。
4. 服务器上的 `plugins/MCZJUGameCore/player_data/the_war_of_ceasefire/` 为历史遗留，可手动清理。
5. `plugin.yml` 使用 `${project.version}` 占位符，**必须经 Maven 资源过滤打包**，不要在 IDE 里直接导出 jar。

---

## 已知限制 / 未处理

- **R4 未实现**（用户要求跳过）
- **队伍人数上限只在「选队那一刻」强制**。若人数减少导致上限变小，已经在队里的玩家不会被自动移出——只有**队伍数减少**时才会裁队（见 R7-3）。
  例：9 人 3 队（上限 3）→ 走 1 人 → 8 人 4 队（上限 2）→ 原来 3 人的队此时超员。
  **该状态下无法开局**，会向局内全体播报「队伍人数不正确，无法开始」（见 R9），需要玩家重新选队才能解除。
  若需要「人数减少后自动再均衡」，需要额外实现，当前未做。
- 在**人数不变**的前提下，上限规则会把队伍分布强制压到「最多相差 1 人」，因此不会出现不均衡
  （例：7 人 4 队上限 2 → 只能是 2/2/2/1；8 人 4 队上限 2 → 只能是 2/2/2/2）
- `CeasefireGame.getTeamCountForPlayers(int)` 现已无调用点（保留为公开 API）
- `GamePlayListener` 中「打任何实体都消耗咬人斧次数」等问题为**上游既有行为**，本次未改动

---
---

# 1.1 补充修复（R15 / R16）

> 以下为**追加**内容：上文（变更总览表 R1~R14、文件清单、房间配置项、命令与权限等）**保持原样未改动**。
> 涉及文件：仅 `game/CeasefireWaitStrategy.java`（R15 + R16）。

## 变更总览（追加）

| # | 需求 | 状态 |
|---|---|---|
| R15 | 满员时第 13 人加入被拒后，必须退回大厅状态（物品栏/手中物品与进房前一致） | ✅ |
| R16 | 等待房补加入播报：单人 1 条，整队按人逐条 | ✅ |

---

## R15 — 满员拒绝后回滚到大厅档案

### 问题

`CeasefireWaitStrategy.onPlayerJoin()` 在 `getPlayers().size() > 12` 时直接 `return false`，
但 MGC 的 `DefaultPlayerManager.leaveGame()` 对 `JOIN_FAIL` 的处理是：

```java
playerGameMap.remove(player);
MCZJUGameCore.getTabManager().onPlayerLeave(game, player);
if (reason == PlayerQuitReason.JOIN_FAIL) return;   // ← 这里就返回了
...
player.switchProfile(null);                          // ← 永远执行不到
if (reason != PlayerQuitReason.DISCONNECT) player.player().playerListName(null);
```

而 `joinGame()` 在调用 `onPlayerJoin()` **之前**已经 `switchProfile(gameId)` 把玩家切到游戏档案
（大厅快照被存起来、当前物品栏被清空）。所以被拒的玩家会停在空的游戏档案上：
**物品栏和手中的物品看起来被清空，且不会自己恢复**——`switchProfile(null)` 才是把大厅快照套回来的那一步。

这正是 R14 给 party 修过的同一个坑，但**单人路径漏了**。

### 修复

新增三个私有方法，单人 / 整队共用同一套回滚：

```java
private static void rejectPlayer(PlayerExt player, String message) {
    player.sender().warn(message);
    backToLobby(player);
}

private static void rejectParty(Party party, String message) {
    party.sender().warn(message);
    party.getAllPlayer().forEach(CeasefireWaitStrategy::backToLobby);
}

/** 退回大厅档案；页脚、游戏模式、Tab 名称都由框架在这一步与随后的 leaveGame 里处理。 */
private static void backToLobby(PlayerExt player) {
    player.switchProfile(null);
}
```

`switchProfile(null)` 会被 `PlayerExt` 翻译成 `"lobby"`，**一次调用做完全部复原**：

| 复原项 | 由谁完成 |
|---|---|
| 物品栏 / 护甲 / 副手 / 末影箱 / 经验 | `ProfileCapture#apply` 套回 `joinGame` 时捕获的大厅快照 |
| 游戏模式（回 ADVENTURE） | `ProfileManager#applyConfiguredProfileState` 的 lobby 分支 |
| Tab 页脚（回大厅欢迎语） | 同上（`ProfileManager` 第 303-305 行的 `sendPlayerListFooter`） |
| Tab 名称 / 前后缀 | 框架随后的 `leaveGame(JOIN_FAIL)` → `TabManager#onPlayerLeave` |

> **修正记录**：最初这里还多写了 `playerListName(null)` 与
> `sendPlayerListFooter(Component.empty())` 两行，理由是「模仿框架 `PlayerReconnectListener#teleportToLobby` 的三件套」。
> 核对框架源码后发现这是**多余且有害**的：`switchProfile(null)` 自己就会把页脚改成大厅欢迎语，
> 紧随其后的 `Component.empty()` 反而把它清空。该两行**已回撤**，`Component` 导入也一并删除。

### 被拒之后到底算不算「正在游玩」

**不算。**（针对被拒的那一局）

| 时刻 | 框架「是否在游戏中」 | 档案 / 物品 | Tab 页脚 |
|---|---|---|---|
| 点「加入游戏」 | `joinGame()` 写入 `playerGameMap` → **在游戏中** | 切到 `ceasefire` 档案，大厅物品入库 | 「正在游玩：止戈之战」 |
| 12 人满 → `rejectPlayer()` | 仍在（尚未移除） | `switchProfile(null)` → 回大厅：物品 / 经验 / 游戏模式 / 页脚全部复原 | 大厅欢迎语 |
| `tryJoin` 返回 false → `leaveGame(JOIN_FAIL)` | **从 `playerGameMap` 移除 → 不在游戏中** | 不再变动（`JOIN_FAIL` 提前 `return`） | 保持大厅欢迎语 |

即被拒后 `new PlayerExt(p).getGame()` 返回 `null`、`isInGame()` 为 `false`。

**两个容易被误判的连带现象**（均为框架既有行为，本次未改动）：

1. **他会连着收到两条提示**：插件发的「该游戏人数已满（最多 12 人）」，
   紧接框架 `DefaultGameManager#joinGame` 在 `tryJoin` 失败后继续走 `createGame()`、
   没有空闲房间时发的「该游戏没有空闲的房间，请稍后再试」。
2. **若服务器还配了第二个空房间**，框架会把他放进那个房间并开一局新的 ——
   此时他确实在游玩，但**不是被拒的那一局**；日志里 `将房间 X 分配给游戏 ceasefire` 就是这条路径留下的痕迹。

### 行为变化

| 场景 | 之前 | 之后 |
|---|---|---|
| 满员（12/12）时**单人**加入 | 提示「人数已满」，但**卡在游戏档案：物品栏被清空、游戏模式与页脚都停在游戏态** | 提示「人数已满」，物品栏 / 手中物品 / 游戏模式 / 页脚**全部回到进房前的大厅状态** |
| 满员时**整队**加入被拒 | 已由 `switchProfile(null)` 正确回滚（R14 起就是对的） | 行为不变（本次只是把它抽成共用的 `backToLobby`） |

> R15 只改变「被拒之后」的状态，**拒绝条件本身未变**（仍是 `> 12` 才拒）。

---

## R16 — 等待房加入播报

### 问题

`onPlayerJoin()` 刻意不调用 `super.onPlayerJoin()`（避免框架在 `size == playerLimit` 时自动开局），
但框架那句「玩家X加入了该游戏（n/12）」恰好写在 `DefaultGameWaitStrategy.onJoin()` 里，
于是**加入完全没有提示**，而退出有（`onPlayerLeave` 播报「玩家 X 退出了等待」）。
房间里的人因此不知道人数变了、队伍数 / 每队上限可能已经跟着变了。

### 修复

单人加入（`onPlayerJoin`）：

```java
game.sender().info("玩家 %s 加入了等待（%d/%d）".formatted(
        player.getDisplayName(), game.getPlayers().size(), MAX_PLAYERS));
```

整队加入（`onPartyJoin` → `announcePartyJoin`）**一人一条**，
人数按加入顺序递增（全队已被 `tryJoin` 预注册，故从「总人数 − 队伍人数」开始累加）：

```java
private void announcePartyJoin(Party party) {
    int count = game.getPlayers().size() - party.size();
    for (PlayerExt member : party.getAllPlayer()) {
        count++;
        game.sender().info("玩家 %s 加入了等待（%d/%d）".formatted(
                member.getDisplayName(), count, MAX_PLAYERS));
    }
}
```

两种情况播报格式**完全一致**，整队加入看起来就是 N 条单人加入。
`CeasefireGame.acceptParty()` 的原有播报（R14 的「一支 N 人的队伍加入了等待房……」与
「请各自选择队伍：右键手中的 下界之星……」）**保持原样、未作任何精简**，因此整队进场时聊天里是：

```
单人进房：
玩家 <green>AniloA</green> 加入了等待（3/12）

3 人队伍进房：
玩家 A 加入了等待（5/12）
玩家 B 加入了等待（6/12）
玩家 C 加入了等待（7/12）
一支 3 人的队伍加入了等待房，队伍已自动解散。                     ← acceptParty 原样，未改动
请各自选择队伍：右键手中的 下界之星，或使用 /ceasefire team <颜色>  ← acceptParty 原样，未改动
```

> 曾尝试把上面两行精简为「该队伍已自动解散，请各自选择队伍」，**该改动已按需求回撤**：
> `CeasefireGame.acceptParty()` 与 R14 记录的原实现完全一致。

---

## 验证

本机无 Maven，使用 **JDK 25.0.4.1 + javac `--release 25`**，classpath 由本地 m2 的真实依赖组成
（`paper-api-26.2.build.133-stable`、`MCZJUGameCore-1.1.2`、brigadier、guava、adventure、bungeecord-chat、annotations），
编译 `src/main/java` 全部 17 个源文件：

```
javac exit: 0    （19 个 class 文件，0 error / 0 warning）
```

**未做实机验收**（本机未启动 Paper 服务器），以下两项需在测试服确认：

1. 12 人满员后让第 13 人点「加入游戏」→ 应提示人数已满，且物品栏与手中物品保持进房前的样子
2. 单人进房、3 人队伍进房 → 聊天应分别出现 1 条 / 3 条「玩家 X 加入了等待（n/12）」

## 本次涉及文件

| 文件 | 变更 |
|---|---|
| `game/CeasefireWaitStrategy.java` | 新增 `rejectPlayer` / `rejectParty` / `backToLobby` / `announcePartyJoin`；`onPlayerJoin` 加入播报 + 满员回滚；`onPartyJoin` 逐人播报 |

> `game/CeasefireGame.java` 在 R15/R16 期间**未改动**（`acceptParty()` 播报保持 R14 原样）；它是在后续的 R17 里才被修改的。

---
---

# 1.1 补充修复（R17 — 对局内状态与地图清理）

> 追加内容：上文（含 R15 / R16 段落）保持原样。
> 涉及文件：`game/CeasefireGame.java`、`room/CeasefireRoom.java`、`block/BlockProtectionListener.java`。
> **本组改动全部在插件侧完成，未修改 MGC 框架**（框架侧无改动权限）。

## 变更总览（追加）

| # | 需求 | 状态 |
|---|---|---|
| R17-1 | 正式开局（30 秒准备阶段结束、`startGameplay`）后把玩家切成生存模式 | ✅ |
| R17-2 | 对局结束（含中途退出）时清空物品栏 / 护甲 / 副手 / 末影箱 | ✅ |
| R17-3 | 对局结束后把世界边界还原成开局前的状态 | ✅ |
| R17-4 | 倒桶产生的水 / 岩浆纳入方块记录并在终局清除 | ✅ |

## R17-1 开局游戏模式

**问题**：大厅档案会把玩家设为 `ADVENTURE`（框架 `ProfileManager` 第 326 行），
而 MGC 切到游戏档案时**不会**改游戏模式 → 玩家放不下开局发的木板 / 羊毛，也拆不掉自己搭的防御。

**实现**：`CeasefireGame.startGameplay()` 在 `giveStartItems()` **之前**调用新增的
`applyGameMode()`，按房间字段 `gameMode` 设置模式（默认 `SURVIVAL`）；
留空或非法值只记录控制台警告并"不干预"。

- 时机取 `startGameplay`（30 秒准备阶段结束、正式开打那一刻），准备阶段仍沿用大厅的 ADVENTURE
- **不额外加任何限制**：方块保护规则（只允许拆玩家放置的方块、保护自然地形、火焰不蔓延）完全不变
- 房间字段：`gameMode`（String，op 可编辑）

## R17-2 结束（含中途退出）清空物品栏

**问题**：MGC 会在玩家离开对局时（`switchProfile(null)`）把**当前**物品栏存进该游戏的档案快照，
而这份快照**从不删除**（`ProfileData#removeProfile` 存在但从未被调用）。于是：

- 第 2 局开局 = 上一局结束时身上的装备 + 开局补给
- 中途 `/mgc leave` 等于把整套装备「存」进下一局 —— 刷装备漏洞

**实现**：按房间字段 `clearInventoryOnEnd`（默认 true），在**两处**清空
`getInventory().clear()`（主栏 + 4 格护甲 + 副手，共 41 格）与 `getEnderChest().clear()`：

| 触发点 | 位置 | 覆盖的场景 |
|---|---|---|
| 对局清理 | `cleanup()` 的**第一步** | 正常结束 / 中止（人走空）/ 取消 |
| 中途退出 | `handlePlayerQuit()` 的**第一行** | `/mgc leave`、掉线 |

两处都**早于**框架的 `switchProfile(null)`（那一步才是把物品栏写进档案），
所以存进档案的快照是空的 —— 跨局残留从源头消失，刷装备的路径同时被堵住。

- 清的是**当前（游戏档案）**的背包与末影箱；大厅那套物品在 `switchProfile` 时换回，不受影响
- 若某张图想保留「上一局物品带进下一局」，把 `clearInventoryOnEnd` 设为 `false` 即可
- 房间字段：`clearInventoryOnEnd`（boolean，op 可编辑）

> 注：更新前已经产生的旧快照，会在**下一局结束时**被清空（自愈），无需手工清理。
> 这条也顺便覆盖了准备阶段：因为残留不再被存进档案，下一局准备阶段手里本来就是空的。

## R17-3 结束还原世界边界

**问题**：`startBorder()` 会改写世界边界，但 `cleanup()` 只取消任务、**不还原**；
而 Minecraft 会把世界边界写进 `level.dat` → 该世界会永久停在本局最后缩到的大小，
影响同一世界的其他对局、大厅，服务器重启后依然存在。

**实现**：

- `startBorder()` 改动边界**之前**先 `snapshotBorder()` 记录原值（大小 / 中心 / 警告距离 / 警告时间）
- `cleanup()`（cancel / abort / end 三条路径共用）末尾调用 `restoreBorder()`
- `borderTouched` 标记保证：**只还原一次**、且**只在真的改动过边界时才还原**
  （没配 `borderCenter` 的房间不缩圈，不能去盖掉服主原本的边界设置）
- 用"设回原值"而**不是** `WorldBorder#reset()`（后者会重置成原版 29999984 格 + 中心 (0,0)）
- 房间字段：`restoreBorderOnEnd`（boolean，默认 true，op 可编辑）

> 边界初始大小沿用已有房间字段 `borderInitialSize`（默认 200，op 可编辑），本次未改动其语义。

## R17-4 水 / 岩浆监听

**问题**：铁职业配方池含铁桶，玩家能倒水 / 岩浆。原实现只记录
`BlockPlaceEvent`（放方块）与 `BlockFormEvent`（水 + 岩浆**反应生成**的圆石 / 石头 / 黑曜石），
**倒桶产生的液体源方块既不算「玩家放置」、也不会被终局清理** → 地图永久积水 / 岩浆，下一局还在。

**实现**：`BlockProtectionListener` 新增 `onBucketEmpty(PlayerBucketEmptyEvent)`，
把液体源方块记入本局 `BlockChangeTracker`，终局由 `restore()` 一并清除。

- **坐标坑**：`getBlock()` 返回的是**被点击的方块**，液体实际生成在
  `getBlock().getRelative(getBlockFace())`，必须取相对位置，否则记录到错坐标、清理无效
- 只需记录**源方块**：`restore()` 用的是带物理更新的 `setType(AIR, true)`，
  源头一删，流动出去的液体下一 tick 自行消失

## 房间配置项（新增 3 项）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `gameMode` | String | `SURVIVAL` | 正式开局时设置的玩家游戏模式；留空 = 不干预 |
| `clearInventoryOnEnd` | boolean | true | 对局结束或中途退出时清空物品栏（含护甲与副手）与末影箱 |
| `restoreBorderOnEnd` | boolean | true | 对局结束后还原世界边界 |

均为 `public` + `@FieldDescription`，用 `/mgcop room edit ceasefire <房名>` 直接编辑。
旧房间 JSON 缺这些键时保留字段默认值（Gson 反序列化语义），**向后兼容**。

## 验证

```
mvn -o -B -DskipTests package   →   BUILD SUCCESS
target/ceasefire-war-0.1.0.jar      66,572 字节
```

**未做实机验收**，需在测试服确认：

1. `/mgc start` → 30 秒准备结束 → 能正常放置木板 / 羊毛、能拆自己放的方块
2. 连打两局，第 2 局开局背包只有开局补给（无上一局残留）；中途 `/mgc leave` 后再进一局同样为空
3. 一局打完后 `/worldborder get` 回到开局前的大小；控制台出现「已还原世界 X 的边界（N 格）」
4. 铁职业倒水 / 岩浆 → 对局结束后地面无残留

## 附带确认：1v1 中途离场判负

一方 `/mgc leave`（或掉线）时，框架会**先把人从 `getPlayers()` 移除**，再走退出策略：

```
handlePlayerQuit → checkTeamWipe(该队)   // getTeamPlayers() 已为空 → 判定该队团灭
                 → checkWin()            // 只剩 1 支活队 → 立刻判对方胜利并结算
```

即 **1v1 里一方离场 = 对方立刻获胜**，依次播报「X 团灭！」→「Y 获得胜利！」→ 本局战绩 → 结束。
2v2 里走 1 人不会结束（该队仍存活 1 人），符合预期。

---
---

# 1.1 补充修复（R18 — 整队加入补发选队物品）

> 追加内容：上文保持原样。涉及文件：`game/CeasefireGame.java`。

## 问题

`giveTeamSelectItems()` 在整个插件里**只有一个调用点** —— `refreshWaitingTeams()` 的
`if (shrunk)` 分支内（即**队伍数减少**时才执行）。而：

- 单人加入靠 `CeasefireWaitStrategy#onPlayerJoin` 单独发（`giveTeamSelectItem(player)`）
- **`acceptParty()` 从头到尾没有发物品**

于是整队加入时（尤其是 12 人局 `2 队 → 4 队` 这种**增长**场景），刚进来的整队手里**没有「选择队伍」物品**，
而 `acceptParty()` 的播报仍在说「右键手中的下界之星」→ 玩家找不到物品。

只有当这次加入恰好让队伍数**减少**（如 10 人 → 9 人档的缩队）时，才会因缩队分支顺带补发。

> 顺带更正 R14 的描述：R14 写「`refreshWaitingTeams()` … 并给所有人补发选队物品」并不准确，
> 实际只在缩队时才发。

## 修复

`CeasefireGame.acceptParty()` 在 `refreshWaitingTeams()` 之后显式补一次：

```java
refreshWaitingTeams();
giveTeamSelectItems();   // 整队加入多半是增长，refreshWaitingTeams 不会补发
```

`giveTeamSelectItems()` 对每个玩家调用幂等的 `TeamSelectItem#ensureInFixedSlot`
（清掉背包中所有副本 → 在快捷栏第 5 格放一颗新的），所以在房里的其他人不受影响。

## 验证

```
mvn -o -B -DskipTests clean package   →   BUILD SUCCESS
```

需在测试服确认：3 人组队 → 进入使队伍数**增长**的局（如 12 人：2 队 → 4 队）→
三人手里都有下界之星，且都在快捷栏第 5 格。

---
---

# 1.1 补充修复（R19 — 结束传送回主大厅）

> 追加内容：上文保持原样。涉及文件：`game/CeasefireGame.java`、`room/CeasefireRoom.java`。

## 问题

框架结束对局时**不会移动玩家** —— `DefaultGameManager#solveGameEnd` 只做三件事：
`removeAllPlayer(game)`（换档案 + 清 Tab）、`gameRoom.setState(READY)`、`gameList.remove(game)`，**全程没有 `teleport`**。
所以打完一局，玩家原地站在竞技场里，只能自己想起用 `/lobby`。

（顺带：`/lobby` 在对局进行中会被拒绝 —— `LobbyCommand` 第 59-62 行要求「未加入游戏或游戏处于等待状态」。）

## 修复

`cleanup()` 末尾调用新增的 `teleportPlayersToLobby()`，按房间字段 `teleportToLobbyOnEnd`（默认 true）执行：

- 落点为 `LobbyManager#getMainLobby()`（主大厅，即 `/lobby` 无参数的目标）
- **延迟 1 tick 执行**：此刻框架还没执行 `removeAllPlayer()` 里的 `switchProfile(null)`，
  等大厅档案（物品栏 / 游戏模式 / 页脚）换好再传送，玩家不会带着游戏状态落地
- 传送成功后给该玩家提示「已传送回大厅」
- 兜底两种异常：
  - 主大厅未配置 / 世界未加载 → 打一条控制台警告并跳过本次传送
  - 关服过程中触发的 abort（此时 `CeasefireWar#getInstance()` 已为 `null`）→ 直接跳过，避免向已关闭的调度器排队
- 房间字段：`teleportToLobbyOnEnd`（boolean，默认 true，op 可编辑）

> `cleanup()` 同时服务 cancel / abort / end 三条路径：cancel 只在房间空掉时发生，
> 所以不会出现「把人从等待房拽回大厅」；abort 时若人已走空，循环自然为空。

## 房间配置项（新增第 4 项）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `teleportToLobbyOnEnd` | boolean | true | 结束 / 中止时把局内玩家传送回主大厅 |

## 验证

```
mvn -o -B -DskipTests clean package   →   BUILD SUCCESS
target/ceasefire-war-0.1.0.jar      67,309 字节
```

需在测试服确认：打完一局（或用 `/mgcop stop_game` 中止）→ 1 tick 后所有局内玩家出现在主大厅，
背包是大厅那一套、游戏模式为大厅的 ADVENTURE、页脚为大厅欢迎语。

---
---

# 1.1 补充修复（R20 — 掉落物不再残留）

> 追加内容：上文保持原样。涉及文件：`game/CeasefireGame.java`、`room/CeasefireRoom.java`。

## 问题

先明确一点：**矿物 / 知识之书的生成任务本身是对的** ——

```java
// CeasefireGame#cleanup() 第 1524-1531 行
if (mineralTask != null) { mineralTask.cancel(); mineralTask = null; }
if (bookTask != null)    { bookTask.cancel();    bookTask = null; }
```

所以对局结束后**不会再产生新的**矿物与书。真正的问题是另外两点：

1. **已经掉在地上的那些不会被清理**。`cleanup()` 原本只还原方块（`BlockChangeTracker#restore`），
   完全不处理 `Item` 实体 —— 上一局的矿物 / 书 / 死亡掉落会留在竞技场里（原版要等 5 分钟才消失），
   影响下一局的资源平衡。
2. **生成任务缺少"是否已启动"的守卫**。`startMineralSpawn()` / `startBookSpawn()` 直接赋值
   `mineralTask = ...`；万一 `startGameplay()` 被重复调用，任务句柄会被覆盖，
   **旧任务泄漏且永远不会被 cancel** —— 那才会出现"对局结束后还一直掉"。本次顺手加了守卫。

## 修复

| 改动 | 位置 |
|---|---|
| 两处生成任务入口加防重复启动守卫：`if (mineralTask != null) return;` / `if (bookTask != null) return;` | `CeasefireGame#startMineralSpawn()` / `#startBookSpawn()` |
| 新增 `removeDroppedItems()`，在 `cleanup()` 里于取消任务之后执行 | `cleanup()` → `removeDroppedItems()` |

`removeDroppedItems()` 的清理范围：

- 目标：本局世界（`gameWorld`）内的所有 `Item` 实体 —— 矿物、知识之书、玩家死亡掉落、主动丢弃、背包满时掉出的开局物资
- **安全范围**：若房间配置了缩圈中心 `borderCenter`，只清理以该点为中心、
  `borderInitialSize / 2` 半径以内的掉落物，**避免误删同一世界里其他小游戏或大厅的物品**；
  未配置缩圈中心时退回清理整个世界
- 只删 `Item` 实体，不碰盔甲架、生物等其他实体
- 清除数量会打一条控制台日志：`已清除场地内 N 个掉落物`

新增房间字段：`clearDroppedItemsOnEnd`（boolean，默认 true，op 可编辑）。

> **顺带一处文档更正**：R12 写「背包满时会掉落在脚下」并不准确 ——
> `giveStartItems()` 用的是 `PlayerInventory#addItem(...)`，它返回的是**放不下的余量**，
> 而代码没有处理这个返回值，所以放不下时是**直接消失**而非掉落。本次未改该行为（需要的话可另行处理）。

## 验证

```
mvn -o -B -DskipTests clean package   →   BUILD SUCCESS
target/ceasefire-war-0.1.0.jar      68,002 字节
```

需在测试服确认：打到一半让场上留一堆矿物 / 书 → 对局结束 → 地面无任何掉落物，
控制台出现「已清除场地内 N 个掉落物」；下一局开局地面干净。

---
---

# 1.1 补充修复（R21 — 液体还原）

> 追加内容：上文保持原样。涉及文件：`block/BlockChangeTracker.java`、`block/BlockProtectionListener.java`。

## 问题

1. **装桶绕过方块保护**：玩家用铁桶装走地图上的水 / 岩浆时**不触发 `BlockBreakEvent`**，
   原保护完全看不到这件事 → 自然水域、岩浆池会被逐局抽干，而且无法恢复。
2. **水浇岩浆会吃掉原岩浆**：水 + 岩浆生成黑曜石时，`onForm` 只把新方块记为"本局生成物"，
   终局 `restore()` 把它清成空气 → 原来那格岩浆**永久消失**，留下一个空气坑。

（曾一并实现过"清理由水派生出的源方块"，后按需求**移除**，见下面第 2 节。）

## 修复

### 1. 记录被移除的自然液体（两条路径）

`BlockChangeTracker` 新增 `removedLiquids`（位置 → 原液体类型）与 `markRemovedLiquid(...)`，
由 `BlockProtectionListener` 在**两种会让天然液体消失的操作**上记录：

| 路径 | 事件 | 说明 |
|---|---|---|
| **用桶装走** | `onBucketFill(PlayerBucketFillEvent)` | **不拦截**（保留铁桶玩法），只记录；非液体目标（挤奶、装雪球等）忽略 |
| **用方块顶掉** | `onPlace(BlockPlaceEvent)` 读 `getBlockReplacedState()` | 直接把方块放进天然水/岩浆里，原液体被替换掉 |

两条路径都遵守同样的排除规则：

- 原液体是玩家自己倒的（已在 `placedBlocks`）→ **不记录** —— 那本来就该被清掉，不能还原
- 只记录**源**方块（`Levelled#getLevel() == getMinimumLevel()`）—— 流动液体不算，避免凭空造出水源

### 2. 派生水源**不清理**（已按需求移除）

第一版实现里做过「从每处玩家倒出的液体出发做 BFS（半径 6 格、上限 24 个源方块），
把由水派生出的源方块 —— 即无限水刷出来的那格 —— 一并清掉」。
因为存在**误删天然水体**的风险（玩家把水倒在一个小水洼旁边，水洼会被一起清掉），已按需求**整段移除**。

现状与代价：

| 场景 | 结果 |
|---|---|
| 玩家直接倒下的水 / 岩浆 | 终局清除 ✅ |
| **两桶水刷出的第三格源方块** | **留在地图上**（单格孤水，不再清理） |

### 3. 被反应掉的液体也还原

`onForm` 额外读取**方块改变前的类型**（`BlockFormEvent` 在方块真正改变之前触发，此时读到的还是岩浆）：

```java
Material previous = block.getType();                          // 仍是岩浆
if (previous == Material.LAVA || previous == Material.WATER) {
    game.getBlockTracker().markRemovedLiquid(block, previous); // 记下来，终局放回去
}
game.getBlockTracker().markPlaced(block);                     // 新方块（黑曜石/圆石）仍可拆、终局清除
```

### 4. 还原顺序（`BlockChangeTracker#restore`）

```
① 分批清除：玩家放置的方块 / 液体（每 tick 50 格）
② 全部清空之后，再把被装走的自然液体源放回原处（只填空位）
```

**这个顺序顺带解决了「玩家用方块把被装走的水坑填上」**：

- 他填的那一块本身也在 `placedBlocks` 里 → 第 ① 步被清成空气
- 第 ② 步发现该位置是空气 → **把原来的水放回去** ✅
- 玩家的方块不残留，地图恢复原样

反过来，如果坑里是**非本局玩家**放的方块（没有被记录），第 ② 步会**跳过、不覆盖** ——
宁可留个洞，也不破坏别人的建筑。

### 5. 附带加固

`onBucketEmpty` 增加校验：**目标位置本来就是液体时不再记录**。
以前是无条件记录，若原版对「把水倒进已有的水里」也派发事件，
会导致一个**自然液体**被当成玩家放置的液体清成空气。

### 6. 三条路径的口径统一

之前三处的**归属判定**与**源方块校验**并不一致，本轮统一：

| 事件 | 有玩家？ | 归属判定 | 源方块校验 |
|---|---|---|---|
| `onPlace`（方块顶掉） | ✅ | `PlayerExt#getGame()` | ✅ `isLiquidSource(replaced.getBlockData())` |
| `onBucketEmpty`（倒出液体） | ✅ | `PlayerExt#getGame()` | ——（记录的是新生成的液体） |
| `onBucketFill`（装走液体） | ✅ | `PlayerExt#getGame()` ← **本轮改**，原为 `gameInWorld()` | ✅ **本轮显式加上** |
| `onForm`（水+岩浆反应） | ❌ | `gameInWorld()`（无玩家信息，只能按世界） | ✅ **本轮加上** |
| `onBurn` / `onFireSpread` | ❌ | `gameInWorld()` | —— |

原则：**有玩家的事件记到「玩家所在的对局」，无玩家的事件才按世界找第一个对局。**

为什么这个统一有必要：`gameInWorld()` 只返回该世界里**第一个**运行中的对局，
多房间共用同一世界时，装桶/倒桶会被记到隔壁局的 tracker 里 ——
结果是"这局结束时清了别局的水"，或者"这局的水没被清"。
改成按玩家归属后，玩家在哪一局就记在哪一局，与 `onPlace`（方块保护）完全同一个口径。

补充：`onForm` 加源方块校验是为了避免**凭空造出水源/岩浆源** ——
被反应掉的如果是**流动**液体，它本来就会消失，不该在终局被还原成一个源方块。

## 验证

```
mvn -o -B -DskipTests clean package   →   BUILD SUCCESS
target/ceasefire-war-0.1.0.jar      69,306 字节
```

需在测试服确认：

1. 装走一桶自然水 → 结束对局 → **那一格水回来了**
2. **不装水，直接拿木板放进天然水里**（水被顶掉）→ 结束对局 → 木板被清掉、**水回来了**
3. 装走自然水后**用木板把那个水坑填上** → 结束对局 → 木板被清掉、**水回来了**
4. 拿装了地图水的水桶浇地图岩浆 → 生成黑曜石 → 结束对局 → **变回岩浆**（不是空气坑）
5. 用两桶水刷出第三格源方块 → 结束对局 → **只剩那一格**（派生水源按现状不清理）

> 用例 2 与 3 的区别：2 是"方块顶掉原水"（走 `onPlace` + `getBlockReplacedState()`），
> 3 是"先抽干、再回填"（走 `onBucketFill`，方块本身也在清除列表里）——
> 两条路径最终都靠"先清空、后还原"收敛到同一个正确结果。

# 1.1 补充修复（R22 — 终局时序 / 死亡界面 / 液体清理）

## 问题

测试服反馈三条，全部集中在「对局结束那一刻」：

1. 最后一个人被打死后，**依然卡在重生界面**（R17-2 那一版的补救没生效）。
2. 最后一个人被打死后，**他的掉落物清不掉**。
3. **玩家倒下的水源清不掉**，终局仍留在地上。

## 根因

### 前两条是同一个时序问题

```
PlayerDeathEvent 触发
└─ CeasefireDeathStrategy → CeasefireGame#handlePlayerDeath
   ├─ respawnAsSpectator()                                    ← 排一个「1 tick 后重生」任务
   ├─ checkTeamWipe() → checkWin() → endGame() → cleanup()    ← 对局在这里就结束了
   └─ 处理函数返回 → ★ 原版此时才生成死亡掉落、把客户端推到死亡界面
```

`cleanup()` 跑在**原版生成掉落物之前**，于是：

- `removeDroppedItems()` 扫不到最后这名死者的掉落物
  （日志里能看到「已清除 N 个」，但那个 N 不含他）。
- 补救用的 `spigot().respawn()` 在 1 tick 后执行时，血量已被框架 `switchProfile(null)`
  复原成 20，「是否在死亡界面」的判断失效 —— 这就是 R17-2 补救没起作用的原因。

### 第三条是坐标不可靠

旧实现固定用 `getBlock().getRelative(getBlockFace())` **推算**液体落点。
纸面语义没错，但 26.2 的流体/事件实现与旧版存在差异时，
记录到的不一定是真正变成水的那一格 → 终局等于「清了个空气」，水留在原地。

## 修复

### R22-1 终局死亡直接取消（一次解决卡界面 + 掉落物残留）

`handlePlayerDeath()` 在 `checkWin()` 之后调用新增的 `cancelDeathIfGameEnded(player, event)`：

```java
private void cancelDeathIfGameEnded(PlayerExt player, PlayerDeathEvent event) {
    if (!finished || event.isCancelled()) {
        return;
    }
    event.setCancelled(true);                  // 玩家不死 → 没有死亡界面、也没有掉落物
    cancelledDeaths.add(player.getUniqueId()); // 让排队的「重生」任务跳过（他根本没死）
    player.resetState();                       // 回满血、清火焰/冻结/药水
}
```

配套三点：

| 位置 | 改动 |
|---|---|
| `handlePlayerDeath()` | 两个分支都调用（决战阵亡 / 普通阵亡后 `checkTeamWipe` 判定胜负之后） |
| `respawnAsSpectator()` | END 分支改为 `if (!cancelledDeaths.remove(uuid)) p.spigot().respawn();` —— 避免对**活着的**玩家调 `respawn()`（那会触发重生点传送，把人从大厅拽回世界出生点） |
| `cleanup()` | 额外补一次 `removeDroppedItems()`（延迟 2 tick），兜住「其它死亡掉落产生在清理之后」的边角情况 |

### R22-2 倒桶改为「看实际结果再记录」

`BlockProtectionListener#onBucketEmpty` 不再猜落点，而是延迟 1 tick 后检查**哪一格真的变成了液体源**：

```java
Block clicked  = event.getBlock();
Block expected = clicked.getRelative(event.getBlockFace());
Bukkit.getScheduler().runTask(plugin, () -> {
    Block actual = isLiquidSource(expected.getBlockData()) ? expected
                 : (isLiquidSource(clicked.getBlockData()) ? clicked : null);
    if (actual == null) {
        return; // 这次没生成新液体源（例如倒进已有的水里）
    }
    ...
    tracker.markPlaced(actual);
});
```

- 记录位置由「推算」变成「实测」，服务端语义怎么变都不会记错。
- 保留原逻辑：若「预期落点」原本是自然液体源，仍记 `markRemovedLiquid` 以便终局还原。
- 新增诊断日志：`[Ceasefire] 倒桶记录: x,y,z WATER`。

### R22-3 清理流程加日志

`BlockChangeTracker#restore()` 现在会打印：

```
[Ceasefire] 清理：玩家方块 N 个，待还原液体 M 个
[Ceasefire] 清理：本局没有记录到任何方块改动        ← 二者皆空时
```

用于区分「没记录」和「记录了但没清掉」，避免下次再靠猜。

## 顺带补强：R17-2 的「局外物品带入局内」

测试反馈「局外东西会被带入局内」。根因不是结束没清，而是**升级前遗留的游戏档案快照**里
还存着旧装备（`plugins/MCZJUGameCore/profiles/<uuid>.yml` 里那份 `ceasefire` 快照）。
R17-2 只在结束/退出时清，管不到历史快照。

因此新增房间字段：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `clearInventoryOnStart` | boolean | true | 开局（`/mgc start`）时清空物品栏与末影箱 |

`onGameStart()` 在 `finalizeTeams()` 之后调用 `clearInventoriesOnStart()`。

## 已知影响（取舍）

- **最后一次击杀的死亡播报不再显示**：这一死被取消，Bukkit 不会广播 `deathMessage`。
  击杀数/死亡数照常计入，胜负播报照常进行。
- 取消死亡意味着最后一名玩家**不会掉装备**——这正是需求（否则要清掉落物），
  且 `cleanup()` 本来就会清空背包。

## 验证

```
mvn -o -B -DskipTests clean package   →   BUILD SUCCESS
target/ceasefire-war-0.1.0.jar      70,938 字节
```

需在测试服确认：

1. 打死最后一个人 → **不进死亡界面**、**地上没有他的掉落物**
2. 普通（非终局）死亡 → 仍正常进旁观 + 10~30 秒复活
3. 放一桶水 → 结束对局 → **那格水消失**
4. 日志出现 `[Ceasefire] 倒桶记录: x,y,z WATER`，且 y 坐标等于实际放水的高度
5. 日志出现 `[Ceasefire] 清理：玩家方块 N 个，待还原液体 M 个`
6. 老玩家进局背包为空（`clearInventoryOnStart` 兜住历史快照）

> 若第 3 条仍失败：看第 4 条日志——
> 没打印 → `PlayerBucketEmptyEvent` 在 26.2 不再触发，需要换监听方式；
> 打印了但坐标对、水还在 → `restore()` 的 `setType(AIR, true)` 对流体行为变了，需改为先 `setType(AIR, false)` 再补邻块更新。
# 1.1 补充修复（R23 — 清理隔离 / 收尾传送 / 掉落物 / 边界）

## 问题（测试服实测，4 条一起出现）

1. 方块不清除，尤其是放下的羊毛。
2. 最后一个被杀的玩家卡在重生界面，复活后与其他玩家完全无法交互。
3. 掉落物没有清理，尤其是最后一名死者的掉落物与远处的知识之书。
4. 所有人没有被传送回主城。

## 根因

### 1/3/4 是同一个结构性缺陷：`cleanup()` 是一条直线

```java
清背包 → 取消任务 → 收 BossBar → ACTIVE_GAMES.remove → 移语音组
→ restoreBorder() → removeDroppedItems() → blockTracker.restore() → teleportPlayersToLobby()
```

后四步**没有任何 try/catch**。任何一步抛异常（离线玩家收 BossBar、世界未加载、语音插件异常……），
后面的边界还原 / 掉落物清理 / 方块清理 / 传送**全部被跳过** —— 恰好就是问题 1、3、4 的组合。

### 问题 2 的三个洞（R22 的修复不够）

| 判定 | 洞 |
|---|---|
| `getHealth() <= 0` | 框架 `switchProfile(null)` 先把血复原成 20 → 判定失效 |
| `getState() == END` | 死亡与结束可能不在同一 tick（服务器卡顿），此时仍是 RUNNING |
| 传送 | **死亡界面中的玩家 `teleport()` 会失败** → 别人回主城了，他留在原地 → 「无法交互」 |

### 问题 3 的第二个原因：半径过滤

`removeDroppedItems()` 只清「缩圈中心 + `borderInitialSize/2`」= 半径 100 格内的掉落物，
玩家把知识之书带到 100 格外再死，就永远清不到。

## 修复

### R23-1 清理流程分步隔离

新增 `step(name, action)`：每步独立 try/catch，失败只记录并打印堆栈，不影响后续步骤。

```java
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
```

`cleanup()` 的六步全部改为 `step(...)`：
清空背包 / 恢复旁观者模式 / 收起 BossBar / 还原世界边界 / 清理掉落物 / 还原方块 / 传送回主城。

### R23-2 死亡后无条件重生

`respawnAsSpectator()` 不再判断血量与状态 —— 这个延迟任务只可能由「刚死亡」触发，
目标玩家必然在死亡界面：

```java
if (cancelledDeaths.remove(p.getUniqueId())) {
    return;                 // 死亡被取消（对局正好被这一死终结），人没死
}
p.spigot().respawn();       // 无条件
if (getState() == GameState.END) {
    return;                 // 交给收尾传送循环
}
p.setGameMode(GameMode.SPECTATOR);
if (target != null) p.teleport(target);
```

### R23-3 收尾传送：+1 / +5 / +20 tick 三次重试

新增 `schedulePostGameTeleports()` + `sendAllToLobby(ids)`：

- **先抓名单**（`cleanup()` 之后框架会 `removeAllPlayer()`，那时 `getPlayers()` 已空）
- 三次尝试分别 +1 / +5 / +20 tick，容忍「死亡与结束不在同一 tick」
- 每次：血量 <= 0 先 `respawn()` → 旁观者设回 ADVENTURE → `teleport(主大厅)` → 已在目标世界且距离 < 2 格则跳过
- 主大厅缺失时打 **error** 日志（不再静默返回）
- 支持跨维度（竞技场世界 ≠ 主城世界）

### R23-4 旁观者强制恢复

对局结束时把所有仍处于 `SPECTATOR` 的参与者设回 `ADVENTURE`。
（框架只在 `enable-lobby-profile-features: true` 时才把大厅档案设成 ADVENTURE，
关掉该开关时死亡玩家会永远停在旁观 → 「和谁都交互不了」。）

### R23-5 掉落物：整维度清理 + 20 tick 补检

- 默认**清理整个世界**（新房间字段 `clearDroppedItemsRadius = 0`），不再受 100 格半径限制
- 清理前后各打一行日志（含数量与范围），0 个也打，便于确认这一步确实执行
- 清理后 **+2 tick 与 +20 tick 各补检一次**（死亡掉落可能晚几 tick 落地）
- 若该世界里**已有新对局**则跳过，避免误删新对局的矿物 / 知识之书

### R23-6 边界回到「世界自带」的默认值

`restoreBorder()` 改为：

```java
border.reset();   // level.dat 里的默认边界（5.9999968E7，超级远）
```

不再还原「开局前的快照」—— 快照可能本来就已经被上一局缩过了。

## 房间配置项（新增 1 项）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `clearDroppedItemsRadius` | double | 0 | 0 = 整维度清理（推荐）；>0 = 只清缩圈中心周围该半径内 |

## 验证

```
mvn -o -B -DskipTests clean package   →   BUILD SUCCESS
target/ceasefire-war-0.1.0.jar      72,589 字节
```

三份 jar：工作区 72,589 / GitHub 72,589 / **Paper 测试服保持 70,938（R22，未替换）**。

需在测试服确认：

1. 打死最后一个人 → 不进死亡界面、地上没有他的掉落物
2. 结束后所有人（含死者）都在**主城**，且能正常交互
3. 结束 1 秒后地面无掉落物（含远处的知识之书）
4. 结束后世界边界恢复到 5.9999968E7
5. 日志出现 `[Ceasefire] 清理步骤「…」失败` 时，该步骤失败但其余步骤仍执行