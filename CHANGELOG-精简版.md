# Ceasefire_War 1.1 · 改动精简版

> 完整细节见 `CHANGELOG.md`。
> 本文件只回答三件事：**改了什么、改在哪里、还遗留什么**。
> 覆盖 **R1~R14（上游基线改动）** 与 **R15~R23（本轮补充修复）**。

---

## 一、改动一览

### A. 基线改动（R1~R14：上游 `Ceasefire_War` master → 本副本 1.1）

| 编号 | 改了什么 | 改在哪里 |
|---|---|---|
| **R1** | 删除持久化 KD 统计与「按 KD 选队长」；每局击败/死亡只存内存 | 删除 `player/CeasefirePlayerData.java`；`CeasefireWar`（去掉 `registerPlayerData`）；`CeasefireGame`（删 `kd()` 与分队里的 KD 排序） |
| **R2** | 分队从「开局自动分」改为「**等待阶段自选** + 未选者开局随机补齐」，队伍数随人数动态变化 | `CeasefireGame#refreshWaitingTeams / finalizeTeams / smallestTeam`；新增 `game/TeamCommand`、`game/TeamSelectListener`、`game/menu/TeamSelectMenu`、`item/TeamSelectItem`；`CeasefireWaitStrategy`（join/leave 触发重算）；`plugin.yml`（`ceasefire.team`） |
| **R3** | 删除实时击杀榜计分板；改为对局结束时在聊天框播报「玩家名/K/D」 | `CeasefireGame`（删 `startScoreboard / updateScoreboard / scoreboardTask`；新增 `roundDeaths`、`broadcastRoundStats`、`nameOf`） |
| **R4** | ⏭️ **跳过**：在职业菜单里显示队友已选职业 | 未实现；`menu/ProfessionMenu`、`menu/RecipeChoiceMenu` 未改动 |
| **R5** | 新增「第二类」矿物 / 知识之书生成点，**仅 4 队局叠加**生效 | `CeasefireRoom`（`mineralSpawnPoints2` / `bookSpawnPoints2`）；`CeasefireGame#secondPointsActive / effective*Points` |
| **R6** | 矿物间隔 10→**4 秒**；铁/金/钻权重 70/25/5 → **50/30/20** | `CeasefireRoom`（`mineralIntervalSeconds` 与三个权重字段的默认值） |
| **R7** | 每队人数上限 = `ceil(人数/队伍数)`；禁止空队；确认 7→6 裁队规则 | `CeasefireGame#teamCapacity / smallestTeam / ensureNoEmptyTeams`；`TeamSelectMenu`（显示 `人数 X/Y`、「该队已满」） |
| **R8** | `.git` 的 origin 改指向 `Ceasefire_War1.1` | `.git/config`（非源码） |
| **R9** | 队伍超员时**禁止开局**，并向局内全体播报 | `CeasefireWaitStrategy#tryStart()` 第一道拦截 |
| **R10** | 审计所有对话框提示的发送范围（谁该收到谁才收到） | **纯审计，无代码改动**；逐条结论见详细版「对话框提示归属审计」 |
| **R11** | 「选择队伍」物品固定在**快捷栏第 5 格**且不可移动 | `item/TeamSelectItem`（`FIXED_SLOT=4`、`ensureInFixedSlot`、`maxStackSize(1)`）；`game/TeamSelectListener`（7 类事件拦截）；`CeasefireGame#giveTeamSelectItem` |
| **R12** | 开局发金苹果（每局仅一次）、开局与复活各发金胡萝卜（数量可配） | `CeasefireRoom`（`startGoldenApples` / `goldenCarrots`）；`CeasefireGame#giveStartItems / giveRespawnItems` + `startItemsGranted` 幂等守卫 |
| **R13** | 合成改**白名单制**：只有职业配方池（24 种）可合成，A+B 双保险 | `GamePlayListener`（新增 `onPrepareCraft` 清空产物；`onCraft` 三段白名单判定；提示改用 `ext.sender().error`） |
| **R14** | MGC party 整队进等待房后**自动解散**；超员整队拒绝 | `CeasefireWaitStrategy#onPartyJoin` + `rejectParty`；`CeasefireGame#acceptParty` |

### B. 本轮补充修复（R15~R23）

| 编号 | 改了什么 | 改在哪里 | 新增房间字段 |
|---|---|---|---|
| **R15** | 满员被拒的玩家（第 13 人）退回大厅档案，不再卡在「物品栏被清空」 | `CeasefireWaitStrategy#onPlayerJoin`；新增 `rejectPlayer / rejectParty / backToLobby` | — |
| **R16** | 等待房加入播报：单人 1 条、整队**按人逐条** | `CeasefireWaitStrategy#onPlayerJoin`；新增 `announcePartyJoin` | — |
| **R17-1** | 正式开局切**生存模式**（原来继承大厅的 ADVENTURE，放不了也拆不了方块） | `CeasefireGame#applyGameMode`（由 `startGameplay()` 调用） | `gameMode` |
| **R17-2** | 对局结束 / 中途退出时清空物品栏（主栏+护甲+副手）与末影箱，杜绝跨局残留与刷装备 | `CeasefireGame#clearInventory`（由 `cleanup()` 与 `handlePlayerQuit()` 调用） | `clearInventoryOnEnd` |
| **R17-3** | 对局结束后**还原世界边界**（原来永久停在本局缩到的尺寸，且写进 `level.dat`） | `CeasefireGame#snapshotBorder`（开局存档）+ `restoreBorder`（`cleanup()` 还原） | `restoreBorderOnEnd` |
| **R17-4** | 倒桶产生的水 / 岩浆纳入方块记录，终局一并清除 | `BlockProtectionListener#onBucketEmpty` | — |
| **R18** | 整队加入时补发「选择队伍」物品（原来只在**缩队**时才发） | `CeasefireGame#acceptParty` | — |
| **R19** | 对局正常结束 / 中止后把局内玩家传送回**主大厅** | `CeasefireGame#teleportPlayersToLobby`（由 `cleanup()` 调用） | `teleportToLobbyOnEnd` |
| **R20** | 对局结束后**不再残留掉落物**：生成任务加防重复守卫（杜绝任务泄漏后一直掉）+ 清理场地内所有 `Item` 实体 | `CeasefireGame#removeDroppedItems`（`cleanup()` 调用）、`startMineralSpawn` / `startBookSpawn` 加守卫 | `clearDroppedItemsOnEnd` |
| **R21** | 液体还原：**被桶装走的**、**被方块顶掉的**天然水/岩浆终局放回；**水浇岩浆生成的黑曜石还原成岩浆**；玩家回填水坑也能正确还原（派生水源按需求**不清理**） | `BlockProtectionListener#onBucketFill`（新增）、`#onPlace`（读 `getBlockReplacedState()`）、`#onForm`（记原液体）、`BlockChangeTracker#markRemovedLiquid` / `restore` | — |
| **R22-1** | **终局死亡直接取消**：最后一个被杀的人不再卡死亡界面，也不再留掉落物（`cleanup()` 跑在原版生成掉落**之前**，这是两者的共同根因） | `CeasefireGame#cancelDeathIfGameEnded`（新增，`handlePlayerDeath` 两个分支都调用）、`respawnAsSpectator`（跳过已取消死亡的玩家）、`cleanup()`（补一次延迟 2 tick 的掉落物清扫） | — |
| **R22-2** | **倒桶改为"看实际结果再记录"**：延迟 1 tick 检查哪一格真的变成液体源，不再用 `getRelative(face)` 推算（推算错一格就清不掉） | `BlockProtectionListener#onBucketEmpty`（重写） | — |
| **R22-3** | 清理流程加日志，方便区分"没记录"和"记录了没清掉" | `BlockChangeTracker#restore` | — |
| **R22-4** | 补强 R17-2：**开局再清一次**背包，兜住升级前遗留的游戏档案快照（老玩家进局带装备） | `CeasefireGame#clearInventoriesOnStart`（由 `onGameStart()` 调用） | `clearInventoryOnStart` |

| **R23-1** | 清理流程**分步隔离**：任一步失败不再拖垮后面的边界 / 方块 / 掉落物 / 传送 | `CeasefireGame#step`（新增）+ `cleanup()` 全部分步 | — |
| **R23-2** | 死亡后**无条件** `respawn()`，不再依赖血量 / 状态判断 | `CeasefireGame#respawnAsSpectator` | — |
| **R23-3** | 收尾传送 **+1 / +5 / +20 tick 三次重试**：先复活 → 旁观者设 ADVENTURE → 传主城 → 校验是否到位（支持跨维度） | `CeasefireGame#schedulePostGameTeleports` / `sendAllToLobby`（新增） | — |
| **R23-4** | 结束时把旁观者强制设回 ADVENTURE（修「复活后无法交互」） | `cleanup()` 的 `step("恢复旁观者模式")` | — |
| **R23-5** | 掉落物改**整维度清理** + 清理后 **2 / 20 tick 各补检**；世界里已有新对局则跳过 | `CeasefireGame#removeDroppedItems` | `clearDroppedItemsRadius` |
| **R23-6** | 边界改为 `border.reset()`，恢复到世界自带默认（5.9999968E7） | `CeasefireGame#restoreBorder` | — |

### 涉及文件（R1~R23 累计）

```
新增 4： game/TeamCommand.java
        game/TeamSelectListener.java
        game/menu/TeamSelectMenu.java
        item/TeamSelectItem.java
删除 1： player/CeasefirePlayerData.java
修改 8： CeasefireWar.java          plugin.yml
        game/CeasefireGame.java    game/CeasefireWaitStrategy.java
        game/GamePlayListener.java  room/CeasefireRoom.java
        block/BlockProtectionListener.java  block/BlockChangeTracker.java
```

---

## 二、要点摘要

### 基线玩法规则（R1~R14 之后的现状）

- **队伍数随人数**：2/3/4/6/8/9/10/12 人 → 2/3/2/2/4/3/2/4 队；**5 / 7 / 11 人沿用上一次有效队伍数，且不能开局**
- **每队上限** = `ceil(人数 / 队伍数)`；超员时禁止开局并向局内全体播报
- **选队**：右键下界之星（固定第 5 格）或 `/ceasefire team [red|blue|yellow|green]`；未选者开局随机补齐
- **统计**：每局击败/死亡只存内存，结束播报；**无持久化统计、无排行榜**
- **合成白名单**：钻石/金/铁的 斧·剑·镐·四件甲 + 铁桶·盾·剪刀 = **24 种**，其余一律不能合成
- **知识之书**：房间开关决定「随机解锁」或「三选一」；**金职业第一本必出金斧**
- **咬人斧**：金职业合成金斧 10% 概率（每队上限 1），锋利 99，1~3 次使用
- **末影珍珠**：钻石职业合成 15% 概率额外获得
- **资源点**：矿物每 4 秒、书每 20 秒，每点各掉 1 个；4 队局叠加第二类生成点
- **缩小圈 + 时间上限**（默认 900 秒）→ **决战时刻**：关闭复活、强制加速缩圈、存活者发光
- **复活** 10~30 秒（随对局延长），优先在活着的队友附近；全员倒地即团灭判负
- **掉线 / 主动退出** 判负出局，比赛继续；场上无人时中止对局
- **1v1 里一方离场 = 对方立刻获胜**（框架先移除成员 → 判团灭 → 判胜并结算）

### 开局 / 结束 / 中途退出（R15~R23）

| 阶段 | 行为 |
|---|---|
| **加入等待房** | 播报「玩家 X 加入了等待（n/12）」；发/归位下界之星到第 5 格（整队加入也逐人播报、逐人发星） |
| **满员被拒** | 提示「人数已满」→ `switchProfile(null)` 退回大厅档案（物品/模式/页脚全复原），**不算在游玩** |
| **`/mgc start` → 30 秒准备** | 准备阶段禁锢移动 + 选职业（此时仍是大厅的 ADVENTURE） |
| **正式开始（`startGameplay`）** | `applyGameMode()` 切生存 → `giveStartItems()` 发物资 → 缩圈/指南针/矿物/书/复活/时间上限全部启动 |
| **对局结束（`cleanup()`）** | ① **先清背包**（让框架随后存进游戏档案的快照为空）② 取消全部任务（矿物 / 书停止生成）、移除语音组 ③ **还原世界边界** ④ **清除场地内掉落物**（并延迟 2 tick 再补扫一次，兜住"死亡掉落生成在清理之后"）⑤ 分批还原方块：清掉玩家放置的方块 / 液体，再把**被桶装走的自然水/岩浆放回** ⑥ **延迟 1 tick 传送回主大厅** |
| **中途退出** | `handlePlayerQuit()` **第一行就清背包**（堵住把装备存进下一局）；判负出局；**不传送**（保留现状，可自行 `/lobby`） |
| **最后一个被击倒** | 该次死亡被**取消**（玩家不死）：**不进死亡界面、不产生掉落物**；击杀/死亡数照常计入，胜负播报正常（死亡播报不显示，见遗留问题） |

### 房间配置项（op 用 `/mgcop room edit ceasefire <房名>` 编辑）

**资源与生成**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `mineralIntervalSeconds` | int | 4 | 矿物生成间隔（秒） |
| `ironWeight` / `goldWeight` / `diamondWeight` | int | 50 / 30 / 20 | 相对权重 |
| `mineralSpawnPoints` / `mineralSpawnPoint` | List / Location | 空 | 矿物点（新/旧字段会合并去重） |
| `mineralSpawnPoints2` | List | 空 | 第二类矿物点，**仅 4 队局**叠加 |
| `bookIntervalSeconds` | int | 20 | 书生成间隔（秒） |
| `bookSpawnPoints` / `bookSpawnPoint` | List / Location | 空 | 书点 |
| `bookSpawnPoints2` | List | 空 | 第二类书点，**仅 4 队局**叠加 |
| `bookChooseThree` | boolean | true | 知识之书三选一 |
| `spawnPoints` | List | 空 | 出生点（**少于队伍数会导致部分队伍不被传送 → 对局卡死**） |

**战斗与时间**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `timeLimitSeconds` | int | 900 | 对局时间上限；0 = 不限；到时进决战时刻 |
| `suddenDeathShrinkSeconds` | int | 60 | 决战时刻缩圈秒数 |
| `borderCenter` | Location | null | 缩圈中心；null = 不缩圈 |
| `borderInitialSize` | double | 200 | 初始边界直径 |
| `borderFinalSize` | double | 40 | 最终边界直径 |
| `borderShrinkSeconds` | int | 180 | 缩圈持续秒数 |
| `borderStartDelaySeconds` | int | 30 | 开局后延迟多久开始缩圈 |

**职业与物资**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `biteAxeLimit` | int | 1 | 每队咬人斧上限 |
| `biteAxeChancePercent` | int | 10 | 金斧变咬人斧概率 |
| `enderPearlChancePercent` | int | 15 | 钻石职业返末影珍珠概率 |
| `startPlanks` / `startSticks` / `startWool` | int | 16 / 16 / 32 | 开局物资 |
| `startCraftingTables` | int | 1 | 开局工作台 |
| `startGoldenApples` | int | 1 | 开局金苹果（每局仅一次） |
| `goldenCarrots` | int | 3 | 金胡萝卜（开局 + 每次复活） |
| `teamCount` | int | 3 | 兜底队伍数（实际由人数决定） |

**本轮新增（R17~R22）**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `gameMode` | String | `SURVIVAL` | 开局游戏模式；留空 = 不干预 |
| `clearInventoryOnEnd` | boolean | true | 结束 / 中途退出清空背包与末影箱 |
| `clearInventoryOnStart` | boolean | true | **开局时**清空背包与末影箱（兜住升级前遗留的游戏档案快照） |
| `restoreBorderOnEnd` | boolean | true | 结束后还原世界边界 |
| `teleportToLobbyOnEnd` | boolean | true | 结束后传送回主大厅 |
| `clearDroppedItemsOnEnd` | boolean | true | 结束后清除场地内掉落物（配了缩圈中心时只清半径内） |
| `clearDroppedItemsRadius` | double | 0 | 掉落物清理半径；**0 = 整维度清理（推荐）**，>0 才按缩圈中心过滤 |

> 旧房间 JSON 缺新增键时保留默认值（Gson 反序列化语义），**向后兼容**。

---

## 三、遗留问题

### A. 根因在框架，只能绕开（框架无改动权限）

| 问题 | 现状 |
|---|---|
| 游戏档案快照永不删除（`ProfileData#removeProfile` 存在但从未被调用） | 用「结束时清空」绕过；插件拿不到 `ProfileData`，无法真正删档案 |
| 切到游戏档案时不改游戏模式（只在切回大厅时设 ADVENTURE） | 用房间字段 `gameMode` 显式设置 |
| 结束对局时不移动玩家（`solveGameEnd` 全程无 teleport） | 用房间字段 `teleportToLobbyOnEnd` 自行传送 |

### B. 已知但未处理

| 问题 | 影响 | 备注 |
|---|---|---|
| **出生点数量 < 队伍数时，多出的队伍不会被传送** | **严重** | 会留在别的世界 → 对局永远打不完、房间永久占用；建议在 `onGameInit` 加硬校验 |
| **R4 未实现**：职业菜单里显示队友已选职业 | 功能缺失 | 用户明确要求跳过（箱子 GUI 无悬停事件，只能靠定时刷新近似） |
| 中途 `/mgc leave` 后**不传送**，人留在竞技场 | 体验 | 按需求保留（此时 `/lobby` 已可用） |
| 多房间同世界：争用同一个 WorldBorder、方块事件可能归错房 | 运维 | `getWorldBorder()` 每世界唯一；`gameInWorld()` 只返回第一个 |
| 咬人斧打**任何实体**都扣次数（含队友 / 动物 / 盔甲架） | 平衡 | 上游既有行为 |
| 合成白名单导致金苹果 / 金胡萝卜 / 木板 / 木棍 / 工作台等**无法自制** | 玩法取舍 | R13 的既定取舍，物资耗尽后无法补充 |
| **派生水源不清理**：两桶水刷无限水刷出的第三格源方块会留在地图上 | 轻微 | 按需求移除该清理逻辑（避免误删天然水体）；表现为地图上多一格孤水 |
| 拖拽事件未覆盖固定槽；漏斗可搬走选队物品 | 轻微 | 实测很难触发 |
| 命令权限 `ceasefire.team` 与菜单权限 `mgc.mgc` 不一致 | 运维 | 两者默认都是 true；手动收紧权限时会「能输命令但界面静默打不开」 |
| 三选一菜单不校验手里是否还有知识之书 | 理论 | 菜单打开时无法丢物品，实际难触发 |
| **最后一次击杀的死亡播报不显示** | 文案 | R22-1 主动**取消**了这次死亡（正是为了避免死亡界面与掉落物）；击杀/死亡统计与胜负播报不受影响 |

### C. 经核实**不是**问题（已确认保留现状）

- 增队导致每队上限变小 → 设计要求（超员时禁止开局并全员播报）
- 离开等待房不清 `teamMap` → 按需求保留（Tab 可见队伍）
- 5 / 7 / 11 人的提示矛盾 → 本来就不能开局
- 超员提示不可操作 → Tab 可见队伍
- 菜单持有旧游戏实例 → 框架切档案时强制 `closeInventory()`，实际不可达
- 非等待状态可开死菜单 / 拖拽未覆盖固定槽 / 漏斗搬走物品 → 按判定不处理

---

## 四、构建与部署

```bash
mvn -B package        # Maven 3.9+ / JDK 25
# 产物：target/ceasefire-war-0.1.0.jar
```

- 服务端需安装 **MCZJUGameCore**（1.1.2 或 1.1.3）
- `plugin.yml` 含 `${project.version}` 占位符，**必须经 Maven 资源过滤打包**（IDE 直接导出会得到字面量）
- 本地 m2 曾存在多个插件「只有 jar、缺 pom」的残缺（surefire / jar / clean），联网跑一次会自动补齐

### 最近一次验证

```
mvn -o -B -DskipTests clean package   →   BUILD SUCCESS（离线，零 warning）
target/ceasefire-war-0.1.0.jar        72,589 字节
plugin.yml: version: 0.1.0            ← 资源过滤正常
```

**R23 之后需在测试服确认：**

1. 打死**最后一个人** → 不进死亡界面、地上没有他的掉落物
2. 普通（非终局）死亡 → 仍正常进旁观 + 10~30 秒复活
3. 放一桶水 → 结束对局 → **那格水消失**；日志出现 `[Ceasefire] 倒桶记录: x,y,z WATER`
4. 日志出现 `[Ceasefire] 清理：玩家方块 N 个，待还原液体 M 个`
5. 老玩家进局背包为空（`clearInventoryOnStart` 兜住历史快照）

**此前已列的常规项：**

6. `/mgc start` → 30 秒后能正常放置 / 拆除方块
7. 连打两局，第 2 局开局背包为空；中途 `/mgc leave` 后再进同样为空
8. 一局结束后 `/worldborder get` 回到开局前大小，且玩家被传送到主大厅
9. 铁职业倒水 / 岩浆，结束后地面无残留
10. **直接拿方块放进天然水里（水被顶掉）→ 结束后水回来了**
11. 整队加入（队伍数**增长**场景，如 12 人 2 队→4 队）→ 每人都有下界之星
12. 1v1 一方 `/mgc leave` → 对方立刻获胜并结算
13. 满员（12/12）时第 13 人加入 → 提示人数已满，物品栏与手中物品保持进房前状态
