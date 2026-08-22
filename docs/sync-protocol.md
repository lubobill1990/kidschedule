# KidSchedule 同步协议规范 v1

本文档是 iOS / Android 两端同步实现的**单一事实源**。任何协议行为的修改必须先更新本文档和 `shared-tests/vectors/` 中的测试向量,再改双端代码。双端单元测试必须加载同一组向量并全部通过。

## 0. 术语

| 术语 | 含义 |
|---|---|
| 同步实体 | 参与同步的表:`babies`、`activity_types`、`events`、`event_attachments` |
| 行 | 同步实体的一条记录,同步粒度为**整行**,不做字段级合并 |
| 本地库 | 客户端 SQLite(iOS: GRDB / Android: Room),是 UI 的唯一数据源 |
| outbox | 本地待推送变更队列 |
| 游标 | 每实体一个 `(server_updated_at, id)` 复合游标,记录增量拉取进度 |

## 1. 标识与时间戳

- **主键**:所有同步实体的 `id` 由客户端生成 UUID v4(小写字符串形式)。
- **device_id**:安装时生成一次的 UUID,持久保存,写入每次变更。
- 每行有两个时间戳,职责严格分离:
  - `client_updated_at`(客户端写):该行最后一次被用户修改的客户端时钟时间。**只用于 LWW 冲突判定**。
  - `updated_at`(服务端触发器写 `now()`):**只用于增量拉取游标**。客户端不得写入、不得用于冲突判定。
- 所有时间戳以 UTC ISO8601 毫秒精度传输(`2026-08-21T14:30:00.000Z`)。
- **时钟校正**:客户端每次成功请求后可从响应记录服务器时间,维护 `clock_skew_ms`;写 `client_updated_at` 时使用 `本地时间 + clock_skew_ms`。校正非强制,但推荐。

## 2. 软删除

- 删除 = 置 `deleted_at = 当前时间` 并更新 `client_updated_at`,作为普通 update 走同步。永不物理删除(撤销窗口内除外,见 §5)。
- 客户端 UI 层过滤 `deleted_at IS NOT NULL` 的行。
- 已删除行参与 LWW:删除与修改冲突时,同样按 `client_updated_at` 新者赢(即后发生的操作赢,可能"复活"该行)。

## 3. Outbox 状态机

本地表 `outbox`:

```
op_id (自增), entity (表名), entity_id (uuid), hold_until (nullable), state, created_at
```

状态:`held → pending → inflight → acked(删除)`

| 转移 | 触发 |
|---|---|
| 创建为 `held` | 带撤销窗口的写入(widget/app 快速记录),`hold_until = now + 撤销窗口` |
| 创建为 `pending` | 无撤销窗口的写入(编辑、补录、结束、auto_end 等) |
| `held → pending` | `now >= hold_until`(由定时器/延迟任务/下次同步扫描触发,以先到者为准) |
| `held → 删除` | 用户撤销:**同时物理删除本地行**(该行从未上行,可以物理删) |
| `pending → inflight` | 推送批次组装时 |
| `inflight → acked(删除)` | 服务端确认该行成功 |
| `inflight → pending` | 请求失败(网络/5xx),整批回退,指数退避重试 |

规则:
- **held 状态的行对同步层不可见**:推送批次组装必须跳过 `held`。
- 同一 `entity_id` 若已有未 acked 的 outbox 项,新变更**合并**(保留一条,不排重复项);若已有项是 `inflight`,新变更新建一条 `pending`(推完后再推)。
- 推送内容不存 outbox,推送时从本地库按 `entity_id` 读**当前行全量**(整行 upsert 语义)。
- outbox 按 `op_id` 升序推送;同批内同一实体的行去重后一次 upsert。
- 推送批大小上限 200 行/实体。

## 4. 推送(Push)

- 调用服务端 RPC:`push_babies(rows jsonb)` / `push_activity_types` / `push_events` / `push_event_attachments`,每实体一个,入参为整行数组。
- 服务端对每行执行 LWW upsert(见 §6),逐行返回结果:

```json
{ "id": "...", "outcome": "applied" | "stale" | "rejected", "reason": null | "ongoing_conflict" }
```

- `applied`:服务端已采纳 → 本地 outbox 项 acked。
- `stale`:服务端已有更新版本 → 本地 outbox 项 acked(照常删除),正确数据将随下次 pull 覆盖本地。
- `rejected` + `ongoing_conflict`:违反"同一宝宝+行为只能有一个进行中"约束(见 §7)→ 本地处理:将本地这条 ongoing 事件改为 `deleted_at = now`(标记删除,**不入 outbox**,纯本地),随后 pull 采纳远端的 ongoing。UI 提示"家人已开始该行为"。
- 推送顺序按实体依赖:`babies → activity_types → events → event_attachments`。

## 5. 撤销窗口

- 窗口时长:**10 秒**(常量 `UNDO_WINDOW_SEC = 10`,双端一致)。
- 快速记录(widget 一键 / app 快捷按钮)流程:
  1. 立即写本地行(正常数据,UI/widget 立刻可见)+ outbox `held, hold_until = now + 10s`;
  2. 窗口内撤销:物理删除本地行 + 删除 outbox 项。该数据从未离开设备;
  3. 窗口过期:outbox 项转 `pending`,进入正常同步。
- 窗口内对该行的**其他修改**(如立即点了结束):修改照常写本地行并更新 `client_updated_at`,outbox 项保持 `held` 不变(仍可整体撤销)。
- app 进程被杀不影响:held 项持久在本地库,下次启动扫描 `hold_until` 已过者转 pending。

## 6. LWW 合并规则(服务端与客户端各自实现,行为必须一致)

对同一 `id` 的两个版本 A(现存)与 B(新来):

1. `B.client_updated_at > A.client_updated_at` → B 赢;
2. `B.client_updated_at < A.client_updated_at` → A 赢;
3. 相等 → `device_id` **字典序大**者赢;device_id 也相等 → 视为同一版本,保持 A(幂等)。

- 赢者**整行**覆盖(除服务端管理字段 `updated_at`、`created_at`、`created_by`——首次插入后不变)。
- 客户端 pull 到远端行时,与本地行执行同一规则决定是否覆盖本地。若本地行有未 acked 的 outbox 项且本地赢,保留本地(稍后推送);若远端赢,覆盖本地并**删除该行未 acked 的 outbox 项**(本地版本已被取代,held 项同样删除但不删本地行)。

## 7. 进行中事件的唯一性

- 不变量:同一 `(baby_id, activity_type_id)` 至多一条 `status='ongoing' AND deleted_at IS NULL` 的事件。
- 服务端以 partial unique index 强制;push 违反时返回 `rejected/ongoing_conflict`(处理见 §4)。
- 客户端本地写入前也须检查:若本地已有 ongoing,不允许再次"开始"(UI 上开始按钮应显示为结束按钮)。

## 8. auto_end(超时自动结束)

- duration 类行为开始时,客户端记录预期结束时刻 `started_at + default_max_duration_sec`。
- 到时若仍 ongoing:客户端将其置 `status='done', ended_at=started_at+default_max_duration_sec, auto_ended=true`,更新 `client_updated_at`,入 outbox `pending`。这是普通 update,正常走 LWW。
- 触达方式:app 前台定时器、widget timeline 刷新、下次打开 app 时扫描,以先到者为准。**多设备可能同时 auto_end 同一事件**:LWW 收敛,无需特殊处理。
- 手动结束与 auto_end 竞争:LWW 决定;若 auto_end 赢了手动结束(时钟原因),数据仍合理(`auto_ended=true` 会提示用户确认)。
- UI:`auto_ended=true` 的事件在列表中标记,提示用户确认/修正真实结束时间;用户修正后 `auto_ended` 置 false。

## 9. 拉取(Pull)

- 每实体独立游标,持久保存 `(last_updated_at, last_id)`。
- 通过 PostgREST 查询(伪):

```
GET /rest/v1/{entity}
  ?or=(updated_at.gt.{ts},and(updated_at.eq.{ts},id.gt.{last_id}))
  &order=updated_at.asc,id.asc
  &limit=500
```

- 循环拉到不足 500 为止;每页处理完(逐行 LWW 进本地库)后再前移游标。
- 拉取顺序:`babies → activity_types → events → event_attachments`。
- 触发时机:app 启动、进前台、推送完成后、Realtime 信号、手动下拉。

## 10. Realtime

- 订阅 family 级 channel(Postgres Changes on 各实体表,filter `family_id=eq.{fid}`)。
- **只作为"有变化"信号**:收到任何消息 → 触发一次 pull。不解析、不信任消息 payload 的完整性。
- 仅前台维持连接;断线重连后必须补一次 pull。

## 11. 附件

- `event_attachments` 行随普通同步走(先行占位):`upload_state = 'pending' | 'uploaded'`。
- 图片文件进独立**上传队列**(与 outbox 分离):压缩(长边 ≤ 2048,JPEG q80)→ 上传 Supabase Storage,路径 `attachments/{family_id}/{event_id}/{attachment_id}.jpg` → 成功后置 `upload_state='uploaded'` 并入 outbox。
- 上传失败指数退避重试;仅 Wi-Fi/蜂窝均可(体积已压缩)。
- 其他设备看到 `pending` 行时显示占位图;`uploaded` 后按需下载 + 磁盘缓存。
- 删除附件 = 软删行;Storage 文件暂不清理(后续用定期任务)。

## 12. 同步调度总流程

```
trigger(启动/前台/Realtime/定时/手动)
  → 扫描过期 held → pending
  → push 循环(按实体顺序,批 200,直到 outbox 无 pending)
  → pull 循环(按实体顺序,按游标,直到无新数据)
  → 重算本地提醒(见 reminder 规范)
```

- 全程持单飞锁(同一时刻只有一个同步循环)。
- 失败:指数退避 2s/4s/8s/…上限 5min;网络恢复事件立即重试。

## 13. 提醒计算(本地)

- 对开启提醒的行为,取该宝宝该行为**最近 20 次**非删除事件(按 `started_at` 升序)为样本;间隔 = 样本内相邻事件 `started_at` 之差。
- `mode=auto`:样本事件数 <5 时不提醒;阈值 = 间隔升序第 `ceil(0.9*n)` 个(1-based,P90 向上取)。`mode=fixed`:阈值 = 用户设定值,至少需 1 个事件作锚点。
- 提醒时刻 = 最近一次事件 `started_at`(instant)或 `ended_at ?? started_at`(duration)+ 阈值。
- 每次同步循环结束后**全量重排**未来 24h 内的本地通知(先清后排);已过期时刻不补发。
- duration 类行为存在 ongoing 时不排该行为的提醒。

## 14. 常量表

| 常量 | 值 |
|---|---|
| UNDO_WINDOW_SEC | 10 |
| PUSH_BATCH_SIZE | 200 |
| PULL_PAGE_SIZE | 500 |
| RETRY_BACKOFF | 2s 起,×2,上限 300s |
| REMINDER_SAMPLE_N | 20 |
| REMINDER_MIN_SAMPLES | 5 |
| REMINDER_PERCENTILE | P90 |
| ATTACHMENT_MAX_EDGE | 2048px |
| ATTACHMENT_JPEG_QUALITY | 0.8 |
