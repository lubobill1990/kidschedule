# shared-tests

双端(iOS / Android)必须共同通过的一致性测试向量。协议规范见 `docs/sync-protocol.md`。

## 契约

- 双端各自实现一个薄的向量加载器,把 `vectors/*.json` 直接打进单元测试(iOS: XCTest 读 bundle 资源;Android: JUnit 读 resources)。
- **全部向量通过是合并代码的前提**(双端 CI 各自跑)。
- 修改协议行为的顺序:先改 `docs/sync-protocol.md` → 再改/加向量 → 最后改双端实现。

## 向量文件

| 文件 | 覆盖 | 对应协议章节 |
|---|---|---|
| `vectors/lww-merge.json` | LWW 整行合并裁决 | §6 |
| `vectors/outbox-state.json` | outbox 状态机 + 撤销窗口 | §3 §5 |
| `vectors/reminder-calc.json` | 提醒阈值与下次触发时刻计算 | §13 |

## 通用约定

- 所有时间戳为 UTC ISO8601 毫秒精度字符串。
- 每个 case 有唯一 `name`,测试失败信息必须包含它。
