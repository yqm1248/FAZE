/**
 * Babel 配置。
 *
 * 使用 Expo 官方预设；`expo-router` 的 babel 插件已包含在 `babel-preset-expo` 中，
 * 因此不需要额外添加 `expo-router/babel`（该插件自 SDK 50 起已废弃）。
 *
 * 路径别名 `@/` 由 `tsconfig.json` 的 paths 与 Metro 解析共同支持，
 * 不在此处使用 `module-resolver`，避免出现两套别名映射规则。
 */
module.exports = function babelConfig(api) {
  api.cache(true);
  return {
    presets: ['babel-preset-expo'],
  };
};
