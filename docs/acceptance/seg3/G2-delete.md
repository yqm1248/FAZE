# 第 3 段 · G2「会话左滑删除（软删）」验收证据

> 施工日期：2026-11-07 · 分支 `UI设计` · 施工成员：`fe-history`（G 组）
> 对应工作流：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` L1270–1300（G2）；设计稿 `design/ui/18-ai-history.html` L252–260（左滑删除块）
> 结论：**左滑 `Swipeable` + `renderRightActions`、二次确认浮层、`DELETE` 调用、成功后移除、
> 失败（`401008` / 网络）保留条目 + Toast 都已实现并通过 `npm run verify` 0 告警 + Metro web bundle 编译复核。
> **真机/浏览器上的左滑手势与二次确认弹层未实测**（本机无真机/模拟器，截图归 Lead CDP），挂账见 §七。
> **「恢复已删除的会话」按工作流 L1283 不做**，理由见 §五。**

---

## 一、交付物

| 文件 | 状态 | 说明 |
|---|---|---|
| `apps/mobile-app/features/ai/pages/AiHistoryPage.tsx` | 新建 | 左滑容器 `Swipeable`（L353–387）、确认框（L394–410）、删除流程（L257–272） |
| `apps/mobile-app/features/ai/api/ai.ts` | 修改 | `deleteConversation(id)`（L243–247，`request<null>('/api/v1/app/ai/conversations/{id}', {method:'DELETE'})`） |
| `apps/mobile-app/app/_layout.tsx` | 修改 | **最外层新增 `<GestureHandlerRootView style={{flex:1}}>`**（L168、L176、style L181–183） |
| `apps/mobile-app/shared/components/PrimaryButton.tsx` | 修改 | 可选 `testID`（供 `ai-history-retry`，与 G2 无直接关系，见 G1 文档） |
| `apps/mobile-app/shared/components/ErrorState.tsx` | 修改 | 可选 `retryTestID`（同上） |
| `apps/mobile-app/shared/i18n/resources/zh-Hans/ai.json` | 修改 | 删除相关 7 个 key：`ai.history.delete` / `deleteTitle` / `deleteBody` / `deleteConfirm` / `deleteCancel` / `deleteFailed` / `deleteForbidden` |

### 1.1 `app/_layout.tsx` 的实际改动（先核实再改）

任务书提示「可能还没包 `GestureHandlerRootView`」——**核实结果：确实没有包**（改前 import 里没有
`react-native-gesture-handler`，根节点直接是 `SafeAreaProvider`）。已按批准的方案加：

```text
改前：return (<SafeAreaProvider> ... </SafeAreaProvider>);
改后：return (
  <GestureHandlerRootView style={styles.gestureRoot}>   ← 新增，最外层
    <SafeAreaProvider> ... </SafeAreaProvider>
  </GestureHandlerRootView>
);
```

- **Provider 顺序未动**：`SafeAreaProvider → I18nProvider → RootNavigator（ToastProvider → Stack）` 原样。
- **`ErrorBoundary` 导出未动**（expo-router 约定名与位置都不变）。
- 为什么必须加：`react-native-gesture-handler` 的手势协商要求整棵树在其根视图内；缺了它，
  原生端 `Swipeable` 不响应、web 端报 `GestureHandlerRootView not found`。
- `ErrorBoundary` 分支**没有**包 GH 根视图（错误边界里只有按钮、没有手势组件），保持最小改动。

---

## 二、接口契约

```text
DELETE /api/v1/app/ai/conversations/{id}
Authorization: Bearer <app 用户 accessToken>
```

| 场景 | 服务端行为（源码与 Lead 实测） | 前端表现 |
|---|---|---|
| 正常删除 | HTTP 200，`UPDATE ai_conversation SET deleted_at=UTC_TIMESTAMP() WHERE id=? AND user_id=?`；**软删**，行与消息都还在 | 从列表移除该条 |
| 重复删除 | 幂等成功（先按 `id+user_id` 更新，0 行再 `countOwned` 区分，命中则返回成功） | 同正常（本页删除成功后条目已不在列表，正常不会再发） |
| 删别人的会话 | 业务码 **`401008`**（HTTP 403，不返回 404，避免枚举会话 id） | `AppToast`「无权操作该对话」，**条目保留** |
| 未登录/token 失效 | `402003`（HTTP 401）→ `client.ts` 统一回登录页 | 页面随全局 401 逻辑走 |
| 网络失败 | 无响应 | `AppToast`「删除失败，请重试」，**条目保留** |

证据：
- 后端实现：`services/faze-api/src/main/java/com/faze/modules/ai/service/AiConversationService.java`
  L92–110（`softDelete` → 0 行时 `countOwned` 区分幂等/越权）、
  `AiConversationController.java` L124–132（`@DeleteMapping("/conversations/{id}")`）。
- 真实 HTTP 复核：**DELETE 的实调由 Lead 完成**（任务书：「后端已完成并实测：200；重复删幂等 200；
  删别人的会话返回 401008；软删，行还在」）。**我没有再实调 DELETE**——它会改测试用户的数据，
  而且 Lead 已验过；我只做了只读的 `GET` 复核（见 G1 文档 §二）。
- `401008` 的前端映射链是双保险：`shared/api/errorCodes.ts` L25 已有
  `401008 → 'error.common.forbidden'`（「没有权限执行该操作」）；本页对 `401008` 再用更贴业务的
  文案 `ai.history.deleteForbidden`（「无权操作该对话」），判断在 `AiHistoryPage.tsx` L267–268。

---

## 三、交互实现（左滑 → 确认 → 删除）

```text
条目（Pressable，testID=ai-history-item-<index>）
 └─ Swipeable（react-native-gesture-handler，renderRightActions）
     └─ renderRightActions → 88dp 红色删除块（testID=ai-history-delete-<index>）
          onPress → setDeleteTarget(item) → ConfirmDialog（danger，title/body/确认/取消）
              ├─ 取消 / 点遮罩 → 关闭，不发请求
              └─ 确认 → handleConfirmDelete()
                   ├─ 成功 → setItems(filter 掉该 id)（列表消失；若删空，自动切空态）
                   └─ 失败 → 关闭弹窗 + AppToast（401008 / 其他）+ **条目保留**
```

关键源码位置（`features/ai/pages/AiHistoryPage.tsx`）：

| 项 | 行号 |
|---|---|
| `Swipeable` + `renderRightActions` | L353–387 |
| 删除块样式（88dp、`color.state.danger`、白字） | L472–482 |
| 二次确认 `ConfirmDialog`（`danger`、`busy={deleting}`） | L394–410 |
| 删除流程 `handleConfirmDelete` | L257–272 |
| `401008` 判断 | L267–268 |

设计细节：

- **明确标注「用户不可逆」**：确认框正文 `ai.history.deleteBody` =「删除后不会出现在对话历史里，
  也无法恢复。」——提醒用户这是不可恢复操作（服务端是软删，但用户侧没有恢复入口）。
- **不乐观删除**：先发请求，成功后才 `setItems` 移除。任务书明确要求「不要乐观删除后
  就地消失又冒出来」。
- **防连点**：请求中 `ConfirmDialog busy={deleting}`（确认按钮禁用 + 半透明）。
- **RTL**：删除块的 `flexDirection` 由 `Swipeable` 内部按 `I18nManager.isRTL` 处理
  （源码：`rightActions` = `row-reverse`，RTL 时变 `row`），本页**没有**写 `left/right` 或
  `scaleX(-1)`，符合 i18n 规范 §8。

---

## 四、删除后的一致性

| 场景 | 本页处理 | 说明 |
|---|---|---|
| 删除的条目在列表里 | 本地 `filter` 移除 | 不重新拉整页（避免跳页/闪烁） |
| 删空最后一页 | `items.length === 0` → 自动切空态（`ai-history-empty`） | 四态流转正确 |
| 当前在 F2 且删的是当前会话 | **未做** | 见 §六 第 1 条（跨页面状态协调，超出本页范围，且任务书 G2 清单未列此项） |
| 后台 A-28 仍能查到 | 服务端软删保证（`deleted_at` 有值、行还在） | 本页不涉及；Lead 的 A-28 验收覆盖 |
| 配额/对话状态 | 不受影响（删除不改配额） | 无需刷新 |

---

## 五、「恢复已删除的会话」——不做（明确记录）

依据：工作流 L1283 原文「**「恢复该对话」不做**：导航树 L225 写『点击 → 恢复该对话』，那是指
『点进去看历史』，不是『恢复已删除』。**已删除的会话用户侧无法恢复**（后台可查）。」

因此：

1. 本页**没有**「撤销删除」/「回收站」/「恢复」任何入口；删除成功后 Toast 也不提「已撤销」。
2. 用户侧删除不可逆：`DELETE` 只写 `deleted_at`，前端从列表移除；再进页也不会返回该会话
   （后端列表过滤 `deleted_at IS NULL`）。
3. 后台可查：A-28（PC 端 AI 对话检索）**包含已软删会话**并标「用户已删除」（工作流 H2/G2 第 5 条）；
   会话行与消息原文都保留，供合规追溯（《AI对话留存策略》§三 / E3）。
4. 若以后产品要「彻底删除」（隐私权诉求）：与合规留痕冲突，**必须负责人裁决**，
   技术侧默认软删（工作流 L1299 已写明）。本任务不做。

---

## 六、未做到 / 风险项（如实）

1. **「若当前在 F2 且删的是当前会话 → 清空对话区回空态」未实现**（工作流 G2 第 4 条后半句）。
   原因：删除发生在历史页，F2 的对话区状态在 `useAiStream` 内部，两个页面同时不可见；
   要实现需要跨页面的会话失效通知（事件总线/全局 store/重新进入时校验 `conversationId` 是否仍可读）。
   这属于 F2 的状态管理范围，且任务书给我的 G2 清单（1–5 条）没有列这一项，所以**没有擅自扩张**。
   已在此登记，请 Lead 决定归到 F 组收口还是后续补。
2. **左滑手势与二次确认弹层未在真机/浏览器实测**：本机无真机/模拟器，**没有**启动浏览器会话；
   只有编译级证据（§六 见 G1 文档的 bundle 复核）。CDP 中若用鼠标拖拽模拟左滑，
   `react-native-gesture-handler` 的 web 实现需要真实 pointer 事件序列（`pointerdown → move → up`），
   简单的 `click` 不会触发——复核时请按拖拽事件模拟；若 CDP 拖不出来，可退化为「原生真机复核」
   或直接用 `delete` 请求 + 列表刷新验证服务端语义（**不要**因此改实现）。
3. **web 端 `Swipeable` 依赖 RNGH web 实现**：Expo web 下 RNGH 手势是 JS 实现，行为与原生可能有
   细微差异（阻尼/吸附阈值）。原生端 `Swipeable` 不依赖 reanimated（本仓未装 reanimated），
   用的是 RN `Animated`，本段不需要额外依赖。
4. **没有「删除失败后的自动重试」**：失败只提示，用户可再滑一次。避免后台无限重试放大问题。
5. **删除确认框不能长按多选删除**：只支持单条左滑（设计稿也只有单条删除）。
6. **列表删除后不刷新 `total`**：本页不展示总数，无影响。
7. **极端边界**：一页 20 条且 `hasMore=true` 时，如果用户把**当前已加载的全部条目**一条条删光，
   页面会直接切空态（`items.length === 0`）而不会自动去拉下一页。要触发需要连删 20 条，属极端场景；
   本页按最小改动未处理（下次进页会重新拉第一页，数据不会丢）。

---

## 七、待 Lead CDP 复核的项

| # | 复核项 | 期望 |
|---|---|---|
| 1 | 重启 8090 / 真机后，条目左滑 | 右侧露出 88dp 红色「删除」块（RTL 页面下露在左侧） |
| 2 | 点删除块 | 弹出二次确认浮层（标题「删除这条对话？」、正文含「无法恢复」、红色「删除」、取消） |
| 3 | 点取消 / 点遮罩 | 浮层关闭，列表与数据都不变（可查库 `deleted_at IS NULL`） |
| 4 | 点确认（正常会话） | 请求 200，条目从列表消失；`GET .../conversations` 不再返回它 |
| 5 | 确认后查库 | `SELECT deleted_at FROM ai_conversation WHERE id=<id>` 有值，**行还在**（软删） |
| 6 | 重复删同一个（可用接口直调） | 幂等 200 |
| 7 | 用 A 账号删 B 账号的会话（接口直调） | `401008`；页面 `AppToast` 显示「无权操作该对话」，条目**仍在** |
| 8 | 断网/停 8081 后确认删除 | `AppToast`「删除失败，请重试」，条目**仍在**（不乐观删除） |
| 9 | 删到列表为空 | 自动切空态 `ai-history-empty` |
| 10 | A-28（PC 后台 AI 对话检索） | 仍能查到刚删的会话，标「用户已删除」（属于 H2 验收，这里一并确认口径） |
| 11 | RTL 下左滑方向 | 删除块从左侧滑出（RNGH 自动镜像），不出现「翻两遍」 |

---

## 八、Lead CDP 复核（2026-11-07，真实 Chrome）

> 执行方式：`node scripts/cdp-shot.mjs --url http://127.0.0.1:8090/ai-history --width 390 --height 844 --mobile --eval-file tmp/seg3/g2-swipe-delete.js`。
> 脚本用 **pointer 事件序列**（pointerdown → 8 次 pointermove → pointerup）模拟左滑（RN Web 的 `Swipeable` 不响应 click）。

| # | 复核项 | 实测结果 |
|---|---|---|
| 1 | 左滑露出删除块 | ✅ `swipeProducedButton=true`、`deleteButtonVisible=true`（`getBoundingClientRect().width > 0`） |
| 2 | 点删除 → 二次确认浮层 | ✅ `hasConfirmDialog=true`；页面文本出现「**删除这条对话？** / 删除后不会出现在对话历史里，也无法恢复。/ 取消 / 删除」 |
| 3 | 点取消 / 遮罩 | ⚠️ **未实测**（脚本直接走了确认路径） |
| 4 | 点确认 → 条目消失 | ✅ `countBefore=11 → countAfter=10` |
| 5 | 查库 `deleted_at` | ✅ 会话 15：`deleted_at=2026-11-06 18:12:38`，**行还在**（软删，未物理删除） |
| 6 | 重复删幂等 | ✅ 接口直调两次都 200（`3-A4` 同轮另测：`DELETE /13` 两次均 200） |
| 7 | 删别人的会话 | ✅ 用 user 1 的 token 删 user 9227 的会话 7 → `401008`，且会话 7 的 `deleted_at` 仍为 NULL（没被误删） |
| 8 | 断网删除失败 | ⚠️ **未实测** |
| 9 | 删到空态 | ⚠️ **未实测**（要删光 10 条，破坏数据） |
| 10 | A-28 仍能查到已删会话 | ✅ 会话 15 在 PC A-28 列表与右侧头部都显示「**用户已删除**」标签，内容完整可见（截图 `img/H2-a28-retrieval-1440.png`；接口侧 `deletedAt` 非空，见 `H2-a28.md` §八 #3） |
| 11 | RTL 左滑方向 | ⚠️ **未实测**（RN Web + 无 RTL 切换的自动化手段） |

**G2 结论：11 项里 6 项通过（1/2/4/5/6/7/10 —— 其中 6、7、10 是接口层证据），5 项未实测（3/8/9/11）**。

**一个必须记下的实现坑**：列表项的删除按钮与确认浮层的确认按钮 `innerText` **都是「删除」**。
第一版 CDP 脚本取「第一个匹配」→ 点回了列表按钮（浮层不关、条目不减，`countAfter` 仍是 11）。
改成「取最后一个匹配」才点中浮层按钮。这**不是产品缺陷**（真实用户点击有坐标/层级），是自动化脚本的选择器问题，记在这里免得下次重踩。
