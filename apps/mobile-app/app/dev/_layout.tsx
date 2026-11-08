/**
 * 探针路由的布局与守卫。
 *
 * 职责：`/dev/**` 只能在开发态或显式开关打开时访问。
 * 数据：`__DEV__`（RN 全局）；B2 装上 `expo-constants` 后追加 `extra.enableDevRoutes` 判断。
 * 注意：
 *   1. expo-router 会把 `app/` 下全部路由打进 bundle，**运行时 `if` 拦不住「进包」**。
 *      段 0 只构建 preview 包，production 包的排除由 `scripts/prepare-release.ps1` 负责。
 *   2. 本守卫解决的是「误入」：非开发态直接重定向回登录页，不让验收人看到探针界面。
 */

import { Redirect, Stack } from 'expo-router';

/**
 * 探针区布局。
 *
 * @returns 开发态渲染子路由，否则重定向到登录页
 */
export default function DevLayout() {
  // __DEV__ 在 release 包里恒为 false（Metro 构建期注入），不需要额外依赖即可判断。
  if (!__DEV__) {
    return <Redirect href="/login" />;
  }
  return <Stack screenOptions={{ headerShown: false }} />;
}
