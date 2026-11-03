/*
 * 职责：Expo 应用清单（段 0 起用 `app.config.ts` 全量替换 `app.json`，避免两处配置）。
 * 调用方：Expo CLI / EAS 构建 / `npx expo start`。
 * 数据来源：`process.env.EXPO_PUBLIC_*` 与 `EAS_PROJECT_ID`（见同目录 `.env.example`）。
 * 注意：
 *   1. **必需值缺失即抛错，不静默回退 `localhost`**：静默回退在真机上表现为「一直网络错误」，
 *      是最难查的一类故障（第 0 段工作流 B2 第 2 条）。
 *   2. 这里的颜色字面量（`#F7F8FA`）是 **app 清单要求**，无法引用 TS 令牌，
 *      已在 `eslint.config.mjs` 的 `no-restricted-syntax` 豁免块里显式列出。
 *   3. `expo-updates` 只当「软重启原语」用：**不配 `updates.url`**，见 B3。
 *   4. 探针路由的启用开关走 `extra.enableDevRoutes`，由 `app/dev/_layout.tsx` 读取。
 */
import type { ExpoConfig } from 'expo/config';

/**
 * 读取必需的环境变量，缺失时立刻失败。
 *
 * @param name 环境变量名
 * @returns 非空值
 * @throws Error 未设置或为空时抛出（阻止构建出一个连不上后端的包）
 */
function required(name: string): string {
  const value = process.env[name];
  if (!value || value.trim() === '') {
    throw new Error(
      `[app.config] 缺少环境变量 ${name}：复制 apps/mobile-app/.env.example 为 .env 并填写。` +
        '真机联调要用 PC 的局域网 IPv4（不能用 localhost / 10.0.2.2）。',
    );
  }
  return value.trim();
}

const config: ExpoConfig = {
  name: '法泽',
  slug: 'faze',
  version: '1.0.0',
  orientation: 'portrait',
  scheme: 'faze',
  userInterfaceStyle: 'light',
  newArchEnabled: true,
  icon: './assets/legacy/logo.png',
  splash: {
    image: './assets/legacy/logo.png',
    resizeMode: 'contain',
    // app 清单要求的启动底色，与设计稿页面底色一致（无法引用 TS 令牌，见文件头注释第 2 条）。
    backgroundColor: '#F7F8FA',
  },
  assetBundlePatterns: ['**/*'],
  ios: {
    supportsTablet: false,
    bundleIdentifier: 'com.faze.app',
  },
  android: {
    package: 'com.faze.app',
    adaptiveIcon: {
      foregroundImage: './assets/legacy/logo.png',
      backgroundColor: '#F7F8FA',
    },
    edgeToEdgeEnabled: true,
  },
  web: {
    bundler: 'metro',
    output: 'single',
    favicon: './assets/legacy/logo.png',
  },
  plugins: [
    'expo-router',
    // Android 备份：SecureStore 的 key 需要随备份迁移，否则恢复后登录态丢失。
    ['expo-secure-store', { configureAndroidBackup: true }],
    'expo-font',
    'expo-localization',
  ],
  experiments: {
    typedRoutes: true,
  },
  extra: {
    /** 服务端基地址。`shared/api/client.ts` 经 expo-constants 读取，是本项目唯一来源。 */
    apiBaseUrl: required('EXPO_PUBLIC_API_BASE_URL'),
    /** 是否放开 `/dev/**` 探针路由（生产构建必须为 false）。 */
    enableDevRoutes: process.env.EXPO_PUBLIC_ENABLE_DEV_ROUTES === 'true',
    eas: {
      projectId: process.env.EAS_PROJECT_ID ?? '',
    },
  },
};

export default config;
