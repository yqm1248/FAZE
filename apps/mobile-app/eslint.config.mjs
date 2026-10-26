import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

/**
 * ESLint 扁平配置。
 *
 * 三类规则来源：
 * 1. `@eslint/js` 推荐集——通用 JavaScript 错误；
 * 2. `typescript-eslint` 推荐集——类型相关错误，重点是 `no-explicit-any`；
 * 3. `react-hooks` 推荐集——Hook 依赖数组与调用位置。
 *
 * 自定义规则的目的见每条注释；未列出的规则保持推荐集默认。
 */
export default tseslint.config(
  {
    // 构建产物与依赖目录不参与检查。
    ignores: ['node_modules/**', '.expo/**', 'dist/**', 'web-build/**', 'expo-env.d.ts'],
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

      // 类型安全：本仓库规范禁止 any（见 AGENTS.md 第 4 节）。
      '@typescript-eslint/no-explicit-any': 'error',

      // 未使用变量允许以 _ 开头，用于忽略回调中不使用的参数。
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      // 视觉数值必须来自设计令牌；禁止在业务代码中写颜色字面量。
      // 仅检查字符串形式的十六进制颜色与 rgba()，不误伤注释。
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "Literal[value=/^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/]",
          message:
            '禁止在业务代码中直接写颜色字面量，请使用 shared/theme/tokens.ts 的 color 令牌。',
        },
        {
          selector: "Literal[value=/^rgba?\\(/]",
          message:
            '禁止在业务代码中直接写 rgba() 颜色，请使用令牌或 opacity 样式属性。',
        },
      ],
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

  {
    // 设计令牌的唯一实现位置，允许写颜色字面量。
    files: ['shared/theme/**/*.ts'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },
);
