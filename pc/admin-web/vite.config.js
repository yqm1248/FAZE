import { defineConfig, loadEnv } from 'vite'
import path from 'path'
import createVitePlugins from './vite/plugins'

const baseUrl = 'http://localhost:8080' // 后端接口（RuoYi 自身）
// 法泽业务接口：faze-api 是**另一个 Spring Boot 应用**，端口 8081（第 1 段 F1）。
// 业务页统一走 /biz-api 前缀，由下面 server.proxy 转给 8081，rewrite 掉前缀。
// 这是本段唯一允许改的前端配置文件（接口规范 §1.3 方案 A / 第 1 段工作流 F1 第 3 步）。
const bizBaseUrl = 'http://127.0.0.1:8081'

// https://vitejs.dev/config/
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd())
  const { VITE_APP_ENV } = env
  return {
    // 部署生产环境和开发环境下的URL。
    // 默认情况下，vite 会假设你的应用是被部署在一个域名的根路径上
    // 例如 https://www.ruoyi.vip/。如果应用被部署在一个子路径上，你就需要用这个选项指定这个子路径。例如，如果你的应用被部署在 https://www.ruoyi.vip/admin/，则设置 baseUrl 为 /admin/。
    base: VITE_APP_ENV === 'production' ? '/' : '/',
    plugins: createVitePlugins(env, command === 'build'),
    resolve: {
      // https://cn.vitejs.dev/config/#resolve-alias
      alias: {
        // 设置路径
        '~': path.resolve(__dirname, './'),
        // 设置别名
        '@': path.resolve(__dirname, './src')
      },
      // https://cn.vitejs.dev/config/#resolve-extensions
      extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
    },
    // 打包配置
    build: {
      // https://vite.dev/config/build-options.html
      sourcemap: command === 'build' ? false : 'inline',
      outDir: 'dist',
      assetsDir: 'assets',
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        output: {
          chunkFileNames: 'static/js/[name]-[hash].js',
          entryFileNames: 'static/js/[name]-[hash].js',
          assetFileNames: 'static/[ext]/[name]-[hash].[ext]'
        }
      }
    },
    // vite 相关配置
    server: {
      port: 80,
      host: true,
      open: true,
      // 2026-11-05 修：写文件的一方用「临时目录 + rename」做原子写（`.index.vue.<pid>.<uuid>.tmpdir/`），
      // Vite 默认的 fs.watch 会去监听那个临时文件，Windows 上抛 EBUSY（资源被占用），
      // 而 Node 的 fs.watch error 事件没人接 → dev server 直接退出（实测 2026-11-05 01:30 崩过一次）。
      // 改成轮询 + 忽略临时目录/临时文件：轮询不走 fs.watch，从根上不会 EBUSY；
      // node_modules 默认不监听，src 很小，300ms 轮询的代价可以接受。
      watch: {
        usePolling: true,
        interval: 300,
        ignored: ['**/*.tmpdir/**', '**/.*.tmpdir/**', '**/*.tmp']
      },
      proxy: {
        // https://cn.vitejs.dev/config/#server-proxy
        '/dev-api': {
          target: baseUrl,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/dev-api/, '')
        },
        // 法泽业务接口 → faze-api:8081。前缀必须在转发时去掉，
        // 因为 faze-api 的路径本身就是 /api/v1/admin/**（不带 /biz-api）。
        '/biz-api': {
          target: bizBaseUrl,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/biz-api/, '')
        },
         // springdoc proxy
         '^/v3/api-docs/(.*)': {
          target: baseUrl,
          changeOrigin: true,
        }
      }
    },
    css: {
      postcss: {
        plugins: [
          {
            postcssPlugin: 'internal:charset-removal',
            AtRule: {
              charset: (atRule) => {
                if (atRule.name === 'charset') {
                  atRule.remove()
                }
              }
            }
          }
        ]
      }
    }
  }
})
