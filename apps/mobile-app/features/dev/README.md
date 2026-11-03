/**
 * `features/dev` —— 验收探针页（不进 release 包）。
 *
 * 边界：只放 ping / AI 流 / law 检索 / RTL 镜像的验收界面；不放任何产品功能。
 * 数据：服务端 `/api/v1/pub/probe/*`（prod profile 下 404）。
 * 注意：
 *   1. 本目录与 `app/dev/**` 是全仓**唯一**的 lint 豁免区（`no-restricted-syntax` 关闭），
 *      理由见 B1 第 2 条：探针不进 release 包，不值得为它做四语言文案。
 *   2. 生产构建前由 `scripts/prepare-release.ps1` 整体移出，构建后还原。
 */
