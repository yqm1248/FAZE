<template>
  <div class="app-container">
    <el-row :gutter="10">
      <el-col :span="12" class="card-box">
        <el-card>
          <template #header><Cpu style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">CPU</span></template>
          <div class="el-table el-table--enable-row-hover el-table--medium">
            <table cellspacing="0" style="width: 100%;">
              <thead>
                <tr>
                  <th class="el-table__cell is-leaf"><div class="cell">属性</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">值</div></th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">核心数</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.cpu">{{ server.cpu.cpuNum }}</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">用户使用率</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.cpu">{{ server.cpu.used }}%</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">系统使用率</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.cpu">{{ server.cpu.sys }}%</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">当前空闲率</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.cpu">{{ server.cpu.free }}%</div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12" class="card-box">
        <el-card>
          <template #header><Tickets style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">内存</span></template>
          <div class="el-table el-table--enable-row-hover el-table--medium">
            <table cellspacing="0" style="width: 100%;">
              <thead>
                <tr>
                  <th class="el-table__cell is-leaf"><div class="cell">属性</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">内存</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">JVM</div></th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">总内存</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.mem">{{ server.mem.total }}G</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ server.jvm.total }}M</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">已用内存</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.mem">{{ server.mem.used}}G</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ server.jvm.used}}M</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">剩余内存</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.mem">{{ server.mem.free }}G</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ server.jvm.free }}M</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">使用率</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.mem" :class="{'text-danger': server.mem.usage > 80}">{{ server.mem.usage }}%</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm" :class="{'text-danger': server.jvm.usage > 80}">{{ server.jvm.usage }}%</div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-card>
      </el-col>

      <el-col :span="24" class="card-box">
        <el-card>
          <template #header><Monitor style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">服务器信息</span></template>
          <div class="el-table el-table--enable-row-hover el-table--medium">
            <table cellspacing="0" style="width: 100%;">
              <tbody>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">服务器名称</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.sys">{{ server.sys.computerName }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">操作系统</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.sys">{{ server.sys.osName }}</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">服务器IP</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.sys">{{ server.sys.computerIp }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">系统架构</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.sys">{{ server.sys.osArch }}</div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-card>
      </el-col>

      <el-col :span="24" class="card-box">
        <el-card>
          <template #header><CoffeeCup style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">Java虚拟机信息</span></template>
          <div class="el-table el-table--enable-row-hover el-table--medium">
            <table cellspacing="0" style="width: 100%;table-layout:fixed;">
              <tbody>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">Java名称</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ server.jvm.name }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">Java版本</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ server.jvm.version }}</div></td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf"><div class="cell">启动时间</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ server.jvm.startTime }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">运行时长</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ server.jvm.runTime }}</div></td>
                </tr>
                <tr>
                  <td colspan="1" class="el-table__cell is-leaf"><div class="cell">安装路径（仅末级目录）</div></td>
                  <td colspan="3" class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ tailPath(server.jvm.home) }}</div></td>
                </tr>
                <tr>
                  <td colspan="1" class="el-table__cell is-leaf"><div class="cell">项目路径（仅末级目录）</div></td>
                  <td colspan="3" class="el-table__cell is-leaf"><div class="cell" v-if="server.sys">{{ tailPath(server.sys.userDir) }}</div></td>
                </tr>
                <tr>
                  <td colspan="1" class="el-table__cell is-leaf"><div class="cell">运行参数</div></td>
                  <td colspan="3" class="el-table__cell is-leaf"><div class="cell" v-if="server.jvm">{{ maskArgs() }}</div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-card>
      </el-col>

      <el-col :span="24" class="card-box">
        <el-card>
          <template #header><MessageBox style="width: 1em; height: 1em; vertical-align: middle;" /> <span style="vertical-align: middle;">磁盘状态</span></template>
          <div class="el-table el-table--enable-row-hover el-table--medium">
            <table cellspacing="0" style="width: 100%;">
              <thead>
                <tr>
                  <th class="el-table__cell el-table__cell is-leaf"><div class="cell">盘符路径</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">文件系统</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">盘符类型</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">总大小</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">可用大小</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">已用大小</div></th>
                  <th class="el-table__cell is-leaf"><div class="cell">已用百分比</div></th>
                </tr>
              </thead>
              <tbody v-if="server.sysFiles">
                <tr v-for="(sysFile, index) in server.sysFiles" :key="index">
                  <td class="el-table__cell is-leaf"><div class="cell">{{ sysFile.dirName }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">{{ sysFile.sysTypeName }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">{{ sysFile.typeName }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">{{ sysFile.total }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">{{ sysFile.free }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell">{{ sysFile.used }}</div></td>
                  <td class="el-table__cell is-leaf"><div class="cell" :class="{'text-danger': sysFile.usage > 80}">{{ sysFile.usage }}%</div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { getServer } from '@/api/monitor/server'

const server = ref([])
const { proxy } = getCurrentInstance()

/**
 * 路径脱敏（工单 W13 / 盲测 S50）。
 *
 * 服务监控页原来把服务器绝对路径原样渲染（如 `E:\dshcode\FAZE\pc\admin-server`），
 * 等于把部署目录结构暴露给任何能进这个页面的管理员，也会被截屏/录屏/浏览器历史带走。
 * 只保留最后一级目录名：运维仍能看出是哪个组件（jdk-17 / ruoyi-admin），
 * 但看不到盘符和上层目录。
 *
 * @param {string} p 服务端返回的绝对路径
 * @returns {string} 最后一级目录名；拿不到时返回破折号
 */
function tailPath(p) {
  if (!p || typeof p !== 'string') return '—'
  const parts = p.split(/[\\/]+/).filter(Boolean)
  return parts.length === 0 ? '—' : parts[parts.length - 1]
}

/**
 * 运行参数脱敏（工单 W13）。
 *
 * `jvm.inputArgs` 里可能带 `-Dspring.datasource.password=...`、`-Dfaze.jwt.secret=...`
 * 这类启动参数——比路径更敏感，属凭据泄露，整格不展示。
 *
 * @returns {string} 固定占位文案
 */
function maskArgs() {
  return '已隐藏（可能含凭据）'
}

function getList() {
  proxy.$modal.loading("正在加载服务监控数据，请稍候！")
  getServer().then(response => {
    server.value = response.data
    proxy.$modal.closeLoading()
  })
}

getList()
</script>
