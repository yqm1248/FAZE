/**
 * 案件进度数据。
 *
 * 来源：原项目 `pages/progress/progress.vue:11-42` 的硬编码内容。
 * 原页把「案件编号」「立案日期」「节点时间」直接写在模板里，且中藏双语靠组件内
 * 自建 `dataMap` 切换（`progress.vue:62-83`），与 `main.js` 安装的 vue-i18n 并存。
 * 复刻时只保留中文，数据抽到本文件。
 */

/** 时间轴上的一个节点。 */
export type ProgressNode = {
  id: string;
  /** 节点标题，例如「案件已受理」。 */
  title: string;
  /** 节点时间文案，格式 `YYYY-MM-DD HH:mm`。 */
  timeLabel: string;
  /** 是否为当前进行到的节点；true 时圆点与竖线使用主色。 */
  active: boolean;
};

/** 进度卡展示数据。 */
export type ProgressCase = {
  /** 案件编号。 */
  caseNo: string;
  /** 状态标签文案，例如「进行中」。 */
  statusLabel: string;
  /** 字段标签与值。原页面为「案件类型：」「立案日期：」两行。 */
  typeLabel: string;
  typeValue: string;
  dateLabel: string;
  dateValue: string;
  /** 时间轴节点，按时间正序。 */
  nodes: ProgressNode[];
};

/** 页面标题。 */
export const PROGRESS_PAGE_TITLE = '查询进程';

/** 假数据：一件民间借贷纠纷的进度。 */
export const PROGRESS_CASE: ProgressCase = {
  caseNo: 'NO. 202604120001',
  statusLabel: '进行中',
  typeLabel: '案件类型：',
  typeValue: '民间借贷纠纷',
  dateLabel: '立案日期：',
  dateValue: '2026-04-01',
  nodes: [
    {
      id: 'accepted',
      title: '案件已受理',
      timeLabel: '2026-04-01 09:30',
      active: true,
    },
    {
      id: 'reviewing',
      title: '正在进行材料审核',
      timeLabel: '2026-04-05 14:20',
      active: false,
    },
  ],
};
