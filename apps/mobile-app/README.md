# mobile-app —— 法泽 APP（Expo）

**这是移动端 APP，不是 PC 管理端，也不是后端服务。**

12 个页面的 UI 复刻实现。当前为纯 UI 阶段：无后端、无鉴权、数据全部来自本地假数据。

## 启动

```bash
npm install          # 首次
npm run web          # 浏览器预览（推荐，无需 Android SDK）
npm run android      # Android 真机 / 模拟器
npm run ios          # iOS（需 macOS）
```

## 质量门

```bash
npm run lint         # tsc --noEmit + eslint（必须零错误零告警）
npm run typecheck    # 只做类型检查
```

## 目录

```text
app/                   Expo Router 路由壳，每个文件一行 re-export
  _layout.tsx          根 Stack + SafeAreaProvider + ToastProvider
  index.tsx            → SplashPage
  (auth)/              login / register（无 tab）
  (tabs)/              home / lawyer / agent / case / profile（5 个 tab）
  lawyer/              detail / join
  progress.tsx         → ProgressPage
  lawsuit-form.tsx     → LawsuitFormPage
  +not-found.tsx       404 兜底

features/              业务域
  auth/                SplashPage、LoginPage、RegisterPage
  home/                HomePage + home.fixture.ts
  lawyer/              LawyerListPage、LawyerDetailPage、LawyerJoinPage + lawyers.fixture.ts
  agent/               AgentChatPage + agent.fixture.ts
  case/                CaseListPage + cases.fixture.ts
  profile/             ProfilePage
  progress/            ProgressPage + progress.fixture.ts
  tools/               LawsuitFormPage

shared/                跨域复用
  theme/               tokens.ts（设计令牌运行时实现）、typography.ts
  components/          Screen / AppHeader / AppCard / PrimaryButton / SectionTitle / TagChip / SearchBar / AppToast
  navigation/          routes.ts（路由常量）

assets/legacy/         原项目位图素材 + index.ts 统一导出
```

## 路由路径说明

| 路径 | 页面 | 说明 |
| --- | --- | --- |
| `/` | SplashPage | 启动页。原项目 `pages.json` 第一项 |
| `/home` | HomePage | 首页 tab。**与原项目不同**：原为 `/pages/index/index`，因 `app/index.tsx` 已被启动页占用而改为 `home` |
| `/lawyer` | LawyerListPage | 律师 tab |
| `/agent` | AgentChatPage | 智能体 tab |
| `/case` | CaseListPage | 案件 tab |
| `/profile` | ProfilePage | 我的 tab |
| `/lawyer/detail?name=` | LawyerDetailPage | 律师详情，参数规则同原项目 |
| `/lawyer/join` | LawyerJoinPage | 入驻申请 |
| `/progress` | ProgressPage | 进度查询 |
| `/lawsuit-form` | LawsuitFormPage | 起诉状生成 |

## 尚未实现（如实记录）

- 无后端接口层。`shared/api/` 尚未创建。
- 无登录态持久化。启动页固定进入登录页。
- 律师搜索框、案件搜索框只渲染不生效（原项目也未实现搜索）。
- 起诉状生成后没有预览页（原项目的预览页同样被注释掉）。
- 智能体只在输入包含「借钱不还」时回复固定文案（与原项目一致）。
