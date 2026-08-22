# KidSchedule

多用户家庭共享的婴儿看护记录 app:自定义行为记录(喂奶/辅食/尿/便…)、桌面 widget 一键记录(10s 可撤销)、统计/日历、pattern 超时提醒。离线优先。

| 目录 | 内容 |
|---|---|
| `docs/sync-protocol.md` | 同步协议规范(双端实现的单一事实源) |
| `shared-tests/` | 双端共用的一致性测试向量(JSON) |
| `supabase/` | 数据库 migrations + Edge Functions |
| `ios/` | SwiftUI app + WidgetKit + Live Activities |
| `android/` | Jetpack Compose app + Glance widget |

技术栈:双端原生(SwiftUI / Compose)+ Supabase(Postgres + Auth 手机号 OTP + Realtime + Storage)。

协议改动流程:改 `docs/sync-protocol.md` → 改 `shared-tests/vectors/` → 改双端实现,向量全绿才可合并。
