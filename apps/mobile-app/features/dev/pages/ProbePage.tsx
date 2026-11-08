/**
 * 探针页：ping / AI 流 / law 检索（`0-A2` 与 `0-A5` 的验收载体）。
 *
 * 职责：把三件事的可视证据集中在一屏，验收人一眼看完。
 * 调用方：路由壳 `app/dev/probe.tsx`。
 * 数据来源：`GET /api/v1/pub/ping`、`POST /api/v1/pub/probe/ai`（SSE）、`GET /api/v1/pub/probe/law?kw=`。
 * 注意：
 *   1. 本页在 `app/dev/**` 的 lint 豁免区内（颜色/裸文本/拼 key 不检查），但**仍用 `<LocalizedText>`**：
 *      `no-restricted-imports`（禁 RN Text）没有豁免本目录。
 *   2. 页面必须显示**当前 baseURL / platform / appVersion**——验收时要能证明「打到哪台机器」。
 *   3. AI 流的首字节要显示**两个数**：客户端实测与服务端 done 帧回传的，两次互证——
 *      只看客户端测不出「是服务端慢还是网络慢」（D3 第 3 条）。
 *   4. 探针页不进 release 包（`app/dev/**` 由 `scripts/prepare-release.ps1` 构建前移出）。
 */

import { useState } from 'react';
import { Platform, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { get, getBaseUrl } from '@/shared/api/client';
import { streamSse } from '@/shared/api/sse';
import type { SseEvent } from '@/shared/api/sse';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { color, radius, spacing, typography } from '@/shared/theme';

/** ping 响应 data。 */
type PingData = {
  appName: string;
  version: string;
  serverTime: string;
  env: string;
};

/** law 命中项。 */
type LawHit = {
  id: string;
  lawCode: string;
  articleNo: string;
  snippet: string;
  score: number;
  matchedTerms: string[];
};

/**
 * 探针页。
 *
 * @returns 三块探针 + 运行时信息
 */
export default function ProbePage() {
  const [ping, setPing] = useState<string>('（未请求）');
  const [aiText, setAiText] = useState<string>('');
  const [aiClientMs, setAiClientMs] = useState<number | null>(null);
  const [aiServerMs, setAiServerMs] = useState<number | null>(null);
  const [aiMock, setAiMock] = useState<boolean | null>(null);
  const [kw, setKw] = useState('借款');
  const [lawResult, setLawResult] = useState<string>('（未检索）');

  const baseUrl = getBaseUrl();
  const appVersion = '1.0.0';
  const platform = Platform.OS;

  /**
   * 调 ping。
   *
   * 副作用：更新页面状态。
   */
  const doPing = async () => {
    setPing('请求中…');
    const result = await get<PingData>('/api/v1/pub/ping');
    if (!result.ok) {
      setPing(`失败：kind=${result.error.kind} msgKey=${result.error.msgKey}`);
      return;
    }
    const d = result.data;
    setPing(`${d.appName} / v${d.version} / env=${d.env} / ${d.serverTime}`);
  };

  /**
   * 发起一次 AI 流并记录首字节。
   *
   * 副作用：更新逐段文本与两个首字节数字。
   */
  const doAi = async () => {
    setAiText('');
    setAiClientMs(null);
    setAiServerMs(null);
    setAiMock(null);
    const startedAt = Date.now();
    let firstChunkAt: number | null = null;

    const onEvent = (event: SseEvent) => {
      if (event.event === 'meta') {
        try {
          const meta = JSON.parse(event.data) as { mock?: boolean };
          setAiMock(meta.mock ?? null);
        } catch {
          // meta 解析失败不影响流
        }
        return;
      }
      if (event.event === 'delta') {
        if (firstChunkAt === null) {
          firstChunkAt = Date.now();
          setAiClientMs(firstChunkAt - startedAt);
        }
        try {
          const chunk = JSON.parse(event.data) as { text?: string };
          if (typeof chunk.text === 'string') {
            setAiText((prev) => prev + chunk.text);
          }
        } catch {
          // 单块解析失败跳过，不中断整条流
        }
        return;
      }
      if (event.event === 'done') {
        try {
          const done = JSON.parse(event.data) as { firstByteMs?: number };
          if (typeof done.firstByteMs === 'number') {
            setAiServerMs(done.firstByteMs);
          }
        } catch {
          // 忽略
        }
      }
    };

    try {
      await streamSse(
        {
          url: `${baseUrl}/api/v1/pub/probe/ai`,
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
          body: '{}',
        },
        onEvent,
      );
    } catch (error) {
      setAiText((prev) => `${prev}\n[错误] ${String(error)}`);
    }
  };

  /**
   * 检索法条。
   *
   * 副作用：更新检索结果。
   */
  const doLaw = async () => {
    setLawResult('检索中…');
    const result = await get<{ hits: LawHit[] }>(
      `/api/v1/pub/probe/law?kw=${encodeURIComponent(kw)}`,
    );
    if (!result.ok) {
      setLawResult(`失败：kind=${result.error.kind} msgKey=${result.error.msgKey}`);
      return;
    }
    const hits = result.data.hits;
    if (hits.length === 0) {
      setLawResult('命中 0 条（kw 少于 2 字时必然为 0，这是 ngram 分词器行为）');
      return;
    }
    const lines = hits.map(
      (h) => `${h.articleNo} | score=${h.score} | 依据=${h.matchedTerms.join('、')}`,
    );
    setLawResult(`命中 ${hits.length} 条：\n${lines.join('\n')}`);
  };

  return (
    <Screen style={styles.screen} edges={['top', 'left', 'right', 'bottom']}>
      <View style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.label}>{'运行时'}</LocalizedText>
        <LocalizedText numberOfLines={2} style={styles.value}>{`baseURL: ${baseUrl}`}</LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.value}>{`platform: ${platform} / appVersion: ${appVersion}`}</LocalizedText>
      </View>

      <View style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.label}>{'1) ping'}</LocalizedText>
        <Button label="请求 ping" onPress={() => void doPing()} />
        <LocalizedText numberOfLines={4} style={styles.value}>{ping}</LocalizedText>
      </View>

      <View style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.label}>{'2) AI 流（SSE）'}</LocalizedText>
        <Button label="开始流式" onPress={() => void doAi()} />
        <LocalizedText numberOfLines={2} style={styles.value}>
          {`mock=${String(aiMock)} 客户端首字节=${aiClientMs ?? '-'}ms 服务端=${aiServerMs ?? '-'}ms`}
        </LocalizedText>
        <LocalizedText numberOfLines={8} style={styles.streamText}>{aiText || '（尚无输出）'}</LocalizedText>
      </View>

      <View style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.label}>{'3) law 检索（ngram）'}</LocalizedText>
        <TextInput
          value={kw}
          onChangeText={setKw}
          style={styles.input}
          placeholder="关键词（≥2 字）"
          placeholderTextColor={color.text.muted}
        />
        <Button label="检索" onPress={() => void doLaw()} />
        <LocalizedText numberOfLines={10} style={styles.value}>{lawResult}</LocalizedText>
      </View>
    </Screen>
  );
}

/**
 * 探针页的按钮（内联样式，dev 页不追求视觉规范）。
 *
 * @param props 文案与回调
 * @returns 按钮
 */
function Button({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={styles.button} accessibilityRole="button">
      <LocalizedText numberOfLines={1} style={styles.buttonText}>{label}</LocalizedText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    paddingHorizontal: spacing.md,
  },
  block: {
    marginTop: spacing.md,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
    gap: spacing.xs,
  },
  label: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  value: {
    ...typography.caption,
    color: color.text.body,
  },
  streamText: {
    ...typography.caption,
    color: color.text.body,
    minHeight: 60,
  },
  input: {
    ...typography.caption,
    height: 36,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
    color: color.text.body,
  },
  button: {
    alignSelf: 'flex-start',
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  buttonText: {
    ...typography.caption,
    color: color.text.onBrand,
  },
});
