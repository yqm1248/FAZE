/**
 * 认证流布局。
 *
 * 登录与注册不显示底部 tab，因此单独成组。`(auth)` 是路由分组，
 * 不出现在 URL 中，实际路径为 `/login`、`/register`。
 */
import { Stack } from 'expo-router';

export default function AuthLayout() {
  return <Stack screenOptions={{ headerShown: false }} />;
}
