import type { ImageSourcePropType } from 'react-native';

import { LEGACY_IMAGES } from '@/assets/legacy';

/**
 * 智能体（AI 聊天）域类型与假数据。
 *
 * 原项目的行为（`pages/ai-agent/ai-agent.vue:51-79`）：
 * - 用户发送消息后，只有文案包含「借钱不还」才会触发一条固定回答；
 * - 其他输入不会有任何回应。
 *
 * 复刻时保留这一行为以维持观感一致，但把「触发关键词」与「回答文案」抽成常量，
 * 接入真实模型时只替换 `buildAgentReply` 的实现，页面不动。
 */

/** 一条聊天消息。 */
export type AgentMessage = {
  /** 稳定标识，用自增序号字符串，避免列表重排时 key 冲突。 */
  id: string;
  /** 消息方向：`ai` 为左侧白色气泡，`user` 为右侧主色气泡。 */
  role: 'ai' | 'user';
  /** 消息正文。 */
  content: string;
};

/** 触发固定回答的关键词。 */
export const AGENT_TRIGGER_KEYWORD = '借钱不还';

/** 顶栏右侧两个操作图标，沿用原项目素材。 */
export const AGENT_NAV_ACTIONS: { key: string; icon: ImageSourcePropType }[] = [
  { key: 'action-1', icon: LEGACY_IMAGES.agentNavAction1 },
  { key: 'action-2', icon: LEGACY_IMAGES.agentNavAction2 },
];

/**
 * 固定回答文案。
 *
 * 内容完全照搬原项目 `simulateAiReply` 里的多行文本，包含换行与缩进，
 * 因此气泡必须使用 `whiteSpace: pre-wrap` 等价方式（RN 中用 `Text` 保留 `\n`）。
 */
export const AGENT_LOAN_REPLY = `根据您的实际情况，法泽建议按先协商→再调解→后法律的顺序来，成本从低到高、效率从快到稳：
一、先做：固定证据（最关键）
    借条/欠条、转账记录、聊天记录、通话录音、对方身份信息（身份证号/住址/电话）
    没借条也没关系：转账+聊天+催款记录 能形成完整证据链
二、第一步：友好协商（成本最低）
    直接沟通，明确还款期限，全程录音
    发催款函，写明金额、期限、逾期后果
    达成一致：签书面还款协议，写清时间、金额、违约责任`;

/**
 * 根据用户输入生成 AI 回复。
 *
 * 纯 UI 阶段的规则与原项目一致：命中关键词才回答，否则不回答。
 *
 * @param input 用户输入的原文
 * @returns 需要追加的 AI 消息；不命中时返回 `null` 表示无回应
 */
export function buildAgentReply(input: string): AgentMessage | null {
  if (!input.includes(AGENT_TRIGGER_KEYWORD)) {
    return null;
  }
  return {
    id: `ai-${Date.now()}`,
    role: 'ai',
    content: AGENT_LOAN_REPLY,
  };
}
