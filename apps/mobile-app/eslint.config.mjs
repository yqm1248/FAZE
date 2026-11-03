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
      ],
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
    // 豁免 3：设计令牌的唯一实现位置允许颜色字面量；
    // app.config.ts 的颜色是 app 清单要求（splash / adaptiveIcon），无法引用 TS 令牌。
    files: ['shared/theme/**/*.ts', 'app.config.ts'],
    rules: {
      'no-restricted-syntax': 'off',
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
