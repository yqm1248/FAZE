import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

/**
 * ESLint 扁平配置。
 *
 * 规则分四层：
 * 1. `@eslint/js` + `typescript-eslint` 推荐集——通用错误与 `no-explicit-any`；
 * 2. `react-hooks`——Hook 依赖数组与调用位置；
 * 3. **RTL 坐标系**（B3）：禁物理方向属性、`textAlign: left/right`、手工镜像；
 * 4. **i18n**（B3）：禁裸 `<Text>`、裸字符串、拼 key。
 *
 * 豁免块只有 4 组，每组都写了理由——**豁免必须可审**，不许为了让某文件过检而加。
 */
export default tseslint.config(
  {
    ignores: ['node_modules/**', '.expo/**', 'dist/**', 'web-build/**', 'expo-env.d.ts', '.release-excluded/**'],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: {
      'react-hooks': reactHooks,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,

      // 类型安全：本仓库规范禁止 any（见 AGENTS.md 第 5 节）。
      '@typescript-eslint/no-explicit-any': 'error',

      // 未使用变量允许以 _ 开头，用于忽略回调中不使用的参数。
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      // 禁裸 <Text>：用「禁 import」比「查 JSX 标签」准，别名导入也拦得住。
      // 用户可见文本一律走 <LocalizedText>；原生 Text 只允许出现在
      // LocalizedText.tsx 与 LtrText.tsx（见下面的豁免块）。
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'react-native',
              importNames: ['Text'],
              message:
                '用户可见文本必须走 <LocalizedText>；原生 Text 只允许出现在 LocalizedText.tsx 与 LtrText.tsx。',
            },
            {
              // RNW 的 Alert.alert 是空函数：Web 上点了没反应（P1-5 的根因）。
              // 二次确认统一用 shared/components/ConfirmDialog，轻提示用 AppToast。
              name: 'react-native',
              importNames: ['Alert'],
              message:
                'RNW 的 Alert.alert 是空函数（点了没反应）；二次确认用 shared/components/ConfirmDialog，轻提示用 AppToast。',
            },
          ],
        },
      ],

      'no-restricted-syntax': [
        'error',

        // ── 风格：颜色不许写字面量（原有规则）────────────────────────────
        {
          selector: "Literal[value=/^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/]",
          message: '禁止在业务代码中直接写颜色字面量，请使用 shared/theme/tokens.ts 的 color 令牌。',
        },
        {
          selector: "Literal[value=/^rgba?\\(/]",
          message: '禁止在业务代码中直接写 rgba() 颜色，请使用令牌或 opacity 样式属性。',
        },

        // ── ① RTL 坐标系：禁物理方向属性（i18n 规范 §8.1）─────────────────
        {
          selector:
            "Property[key.name=/^(left|right|marginLeft|marginRight|paddingLeft|paddingRight|borderLeftWidth|borderRightWidth|borderLeftColor|borderRightColor|borderTopLeftRadius|borderTopRightRadius|borderBottomLeftRadius|borderBottomRightRadius)$/]",
          message: 'RTL 必需：用 start/end 替代 left/right 物理方向属性（i18n 规范 §8.1）。',
        },

        // ── ①b textAlign 没有 start/end，RN 只认 auto ────────────────────
        {
          selector: "Property[key.name='textAlign'][value.value=/^(left|right)$/]",
          message: "RN 的 textAlign 无 start/end：用 'auto' 跟随方向，或按 lang 取值。",
        },

        // ── ③a no-raw-text：JSX 里的裸字符串 ─────────────────────────────
        {
          selector: "JSXText[value=/[^\\s]/]",
          message: '硬编码文本：所有用户可见字符串走 t()（no-raw-text）。',
        },

        // ── ③a 补充：三元/表达式里的字符串字面量 ─────────────────────────
        {
          selector: "JSXExpressionContainer > Literal[value=/[^\\s]/]",
          message: '硬编码文本：三元/表达式里的字符串字面量也要走 t()。',
        },

        // ── ③b no-dynamic-i18n-key：禁拼 key ─────────────────────────────
        {
          selector: "CallExpression[callee.name='t'] TemplateLiteral",
          message: '禁拼 key：t() 实参必须是字面量或已登记常量（no-dynamic-i18n-key）。',
        },
        {
          selector: "CallExpression[callee.name='t'] > BinaryExpression[operator='+']",
          message: '禁拼 key：t() 实参不许字符串拼接。',
        },

        // ── ⑤ 图标镜像只能由 DirectionalIcon 做 ─────────────────────────
        {
          selector: "Property[key.name='transform'] Property[key.name='scaleX'][value.value=-1]",
          message: '图标镜像只能由 <DirectionalIcon> 统一处理（i18n 规范 §8.2）。',
        },

        // ── ⑥ no-array-fallback：禁止静默兜底取首元素（R-02）─────────────
        //
        // 典型坏味道：`res.data.list.find(...) ?? res.data.list[0]`。
        // 它把「没找到」伪装成「找到了」，用户看到的是**另一条数据**，而且没有任何报错。
        // 2026-11-11 的实例：案例详情的法条弹层用 `find(...) ?? list[0]`，
        // 检索结果一变就显示出别的法条——那是内容错误。
        //
        // 正确做法：匹配不到就走空态/错误态（`EmptyState` / `ErrorState` / 显式的「无法定位」文案）。
        {
          selector:
            "LogicalExpression[operator='??'] > MemberExpression.computed[computed=true] > Literal[value=0]",
          message:
            '禁止静默兜底取首元素（如 `x ?? list[0]`）：匹配不到要显式报「暂无数据」，不要把另一条数据当结果（R-02）。',
        },

        // ── ⑦ no-raw-money：金额展示必须走 formatAmount / MoneyText（R-08 同源）──
        //
        // `` `¥${n}` `` 的问题不是格式丑，是**它绕过了唯一的金额格式化入口**：
        // 负数、千分位、货币符号位置、阿拉伯系 RTL 下的符号方向全都各写各的。
        // 合法例外只有「输入中的原始文本回显」（见 FilingPage 的 eslint-disable 与理由）。
        {
          // 注意选择器写法：`${` 是模板字面量的**语法**，不在 `TemplateElement.raw` 里。
          // `\`¥${n}\`` 的 AST 是 TemplateElement(raw="¥") + Expression + TemplateElement(raw="")。
          // 所以匹配「raw 里含 ¥ 的模板片段」，而不是去匹配 `${`。
          selector: "TemplateLiteral > TemplateElement[value.raw=/¥/]",
          message:
            '金额必须走 formatAmount() / <MoneyText>，禁止模板字符串里手拼 ¥（金额是整数分，展示要统一）。',
        },

        // ── ⑧ no-idem-key-in-api：幂等键只能由 useIdempotencyKey 生成（R-01）──
        //
        // 幂等键的生命周期是「页面挂载固定 → 成功重置 → 失败复用」。
        // 任何自造的 `newIdempotencyKey()` 都拿不到这个生命周期：写在 api 层就是「每次调用新键」，
        // 等于每次都当新提交，网络抖动后重试会建出第二单/第二案。
        {
          selector: "Identifier[name='newIdempotencyKey']",
          message:
            '幂等键只能用 useIdempotencyKey（挂载固定 / 成功 reset / 失败复用），禁止自造生成函数（R-01）。',
        },
      ],
    },
  },

  {
    // 幂等键生成的唯一合法落点（规则 ⑧ 的豁免）。
    // 这个文件里除了生成函数就是 hook 本身，没有 JSX、没有颜色、没有 i18n，关掉整条规则代价可控。
    files: ['shared/hooks/useIdempotencyKey.ts'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },

  {
    // 豁免 1：两个文本组件是全仓唯一允许 import RN Text 的地方；
    // 它们内部要写 <Text> 与样式，所以同时关掉 no-restricted-syntax。
    files: ['shared/components/LocalizedText.tsx', 'shared/components/LtrText.tsx'],
    rules: {
      'no-restricted-imports': 'off',
      'no-restricted-syntax': 'off',
    },
  },

  {
    // 豁免 2：图标白名单与镜像组件——镜像写法的唯一合法落点（规则 ⑤）。
    files: ['shared/components/icons/**', 'shared/components/DirectionalIcon.tsx'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },

  {
    // 豁免 2.5：字体资源映射——`expo-font` 的 `useFonts` 必须用 `require('*.ttf')`，
    // Metro 只认 require 形式的资源引用，静态 import 拿不到 asset id。
    // 这是 Expo 官方写法，不是绕规则；本文件除这两个 require 外没有别的 require。
    files: ['shared/theme/fonts.ts'],
    rules: {
      '@typescript-eslint/no-require-imports': 'off',
    },
  },

  {
    // 豁免 3：设计令牌的唯一实现位置允许颜色字面量；
    // app.config.ts 的颜色是 app 清单要求（splash / adaptiveIcon），无法引用 TS 令牌。
    files: ['shared/theme/**/*.ts', 'app.config.ts'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },

  {
    // api 层专属：禁止用「时间戳 + 随机数」现造标识。
    //
    // 为什么单独一块：这一条只在 api 层要，写在主规则里会误伤页面层的合法用法
    // （聊天页用 `Date.now() + Math.random()` 造 clientMsgId、倒计时用 `Date.now()` 都合法）。
    // api 层是「与后端对契约」的地方，任何「每次调用都不同」的随机值都会破坏幂等与重放。
    //
    // 注意：本块只覆盖这 3 个文件的 no-restricted-syntax，其他文件仍走主规则。
    // api 层是纯 .ts（无 JSX），主规则里的裸文本/颜色条目对这里本来也不适用。
    files: ['features/*/api/**/*.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: "MemberExpression[object.name='Math'][property.name='random']",
          message:
            'api 层禁止生成随机值：幂等键等标识必须由页面用 useIdempotencyKey 传入，否则重试会被当成新请求（R-01）。',
        },
        {
          selector: "MemberExpression[object.name='Date'][property.name='now']",
          message:
            'api 层禁止用 Date.now() 造标识：时间戳每次都不同，重试会被当成新请求（R-01）。要时间请由调用方传入。',
        },
      ],
    },
  },

  {
    // 豁免 4：探针页——**全仓唯一的 i18n/颜色豁免区**（B1 第 2 条的裁决）。
    // 理由：探针页不进 release 包（scripts/prepare-release.ps1 会整体移出），
    // 且它是验收工具，不值得为它做四语言文案。
    // 关掉整条 no-restricted-syntax = 颜色 / 裸文本 / 拼 key 三条一起关，代价可控；
    // 对应的闸门由 scripts/check-raw-text.ps1 用「路径排除」独立承担。
    files: ['app/dev/**', 'features/dev/**'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },

  {
    // CommonJS 配置文件运行在 Node 环境，需要声明 Node 全局变量。
    files: ['babel.config.js', 'metro.config.js'],
    languageOptions: {
      globals: {
        module: 'writable',
        require: 'readonly',
        __dirname: 'readonly',
        process: 'readonly',
      },
    },
  },
);
