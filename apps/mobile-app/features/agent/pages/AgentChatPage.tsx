import { useState } from 'react';
import {
  FlatList,
  Image,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { LEGACY_IMAGES } from '@/assets/legacy';
import { useToast } from '@/shared/components/AppToast';
import { Screen } from '@/shared/components/Screen';
import { color, fontSize, radius, size, spacing, shadow, typography } from '@/shared/theme';

import {
  AGENT_NAV_ACTIONS,
  buildAgentReply,
  type AgentMessage,
} from '../__fixtures__/agent.fixture';

/**
 * 智能体（tab 3）—— 法律问题对话。
 *
 * 路由：`/agent`
 * 数据：`__fixtures__/agent.fixture.ts`（固定回答）
 * 结构：顶栏 → 消息列表 → 底部输入区。
 *
 * 与原实现的差异（两处，均在 `docs/design/页面清单与信息架构.md` 第 5 节登记）：
 * 1. 原页有返回按钮，但本页处在 tabBar 中，返回行为不可预期；此处去掉返回按钮。
 * 2. 原页标题写死「法泽」，与 tab 名「智能体」不一致；此处改为「法泽智能体」。
 *
 * 气泡样式保留原设计：AI 白色气泡右上角收窄成小圆角、用户气泡左上角收窄，
 * 形成"尖角指向头像"的指向感。用户气泡原为微信绿 `#95EC69`，按配色统一改为主色。
 */
export default function AgentChatPage() {
  const insets = useSafeAreaInsets();
  const toast = useToast();

  const [messages, setMessages] = useState<AgentMessage[]>([]);
  const [inputText, setInputText] = useState('');

  /**
   * 发送消息。
   *
   * 流程与原实现一致：先追加用户消息并清空输入框，再判断是否需要 AI 回应。
   * AI 回应不阻塞用户继续输入，因此不在此时显示全屏 loading。
   */
  const handleSend = () => {
    const text = inputText.trim();
    if (text.length === 0) {
      return;
    }

    const userMessage: AgentMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: text,
    };
    setMessages((previous) => [...previous, userMessage]);
    setInputText('');

    const aiReply = buildAgentReply(text);
    if (aiReply !== null) {
      // 延迟 600ms 再插入回答，模拟"思考中"的节奏感（原实现为 1000ms + loading）。
      setTimeout(() => {
        setMessages((previous) => [...previous, aiReply]);
      }, 600);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right']} style={styles.screen}>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        {/* 顶栏 */}
        <View style={styles.navBar}>
          <Text style={styles.navTitle}>法泽智能体</Text>
          <View style={styles.navIcons}>
            {AGENT_NAV_ACTIONS.map((action) => (
              <Pressable
                key={action.key}
                onPress={() => toast.show('功能开发中')}
                hitSlop={spacing.sm}
                style={styles.navIconButton}
                accessibilityRole="button"
              >
                <Image source={action.icon} style={styles.navIcon} resizeMode="contain" />
              </Pressable>
            ))}
          </View>
        </View>

        {/* 消息列表 */}
        <FlatList
          data={messages}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.listContent}
          showsVerticalScrollIndicator={false}
          ListEmptyComponent={
            <Text style={styles.emptyHint}>
              描述您遇到的法律问题，例如「借钱不还怎么办」
            </Text>
          }
          renderItem={({ item }) => <MessageRow message={item} />}
        />

        {/* 输入区 */}
        <View style={[styles.inputSection, { paddingBottom: insets.bottom + spacing.md }]}>
          <View style={styles.inputWrap}>
            <Pressable onPress={() => toast.show('附件发送开发中')} hitSlop={spacing.sm}>
              <Image source={LEGACY_IMAGES.agentInputAttach} style={styles.inputIcon} resizeMode="contain" />
            </Pressable>
            <TextInput
              style={styles.input}
              value={inputText}
              onChangeText={setInputText}
              placeholder="发消息和按住说话"
              placeholderTextColor={color.text.muted}
              onSubmitEditing={handleSend}
              returnKeyType="send"
              multiline
            />
            <Pressable onPress={() => toast.show('语音输入开发中')} hitSlop={spacing.sm}>
              <Image source={LEGACY_IMAGES.agentInputVoice} style={styles.inputIcon} resizeMode="contain" />
            </Pressable>
            <Pressable onPress={handleSend} hitSlop={spacing.sm}>
              <Image source={LEGACY_IMAGES.agentInputSend} style={styles.inputIcon} resizeMode="contain" />
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

/**
 * 单条消息行。
 *
 * AI 消息头像在左、气泡在右；用户消息顺序相反。
 * 气泡圆角方向：靠近头像的一侧收窄为 2dp，形成指向感。
 */
function MessageRow({ message }: { message: AgentMessage }) {
  const isAi = message.role === 'ai';

  return (
    <View style={[styles.messageRow, isAi ? styles.messageRowAi : styles.messageRowUser]}>
      {isAi ? (
        <Image source={LEGACY_IMAGES.agentAvatar} style={styles.avatar} resizeMode="cover" />
      ) : null}

      <View style={[styles.bubble, isAi ? styles.bubbleAi : styles.bubbleUser]}>
        <Text style={[styles.bubbleText, isAi ? styles.bubbleTextAi : styles.bubbleTextUser]}>
          {message.content}
        </Text>
      </View>

      {isAi ? null : (
        <View style={styles.userAvatar}>
          <Text style={styles.userAvatarIcon}>👤</Text>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
  },
  screen: {
    backgroundColor: color.background.canvas,
  },

  /* ── 顶栏 ─────────────────────────────────────────────── */
  navBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    backgroundColor: color.background.canvas,
    borderBottomWidth: 1,
    borderBottomColor: color.border.outline,
  },
  navTitle: {
    ...typography.h2,
    color: color.text.body,
  },
  navIcons: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  navIconButton: {
    marginLeft: spacing.lg,
  },
  navIcon: {
    width: 22,
    height: 22,
  },

  /* ── 消息列表 ─────────────────────────────────────────── */
  listContent: {
    padding: spacing.lg,
    paddingBottom: spacing.xl,
  },
  emptyHint: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.xxxl * 2,
  },
  messageRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    marginBottom: spacing.xl,
    width: '100%',
  },
  messageRowAi: {
    justifyContent: 'flex-start',
  },
  messageRowUser: {
    justifyContent: 'flex-end',
  },
  avatar: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.pill,
    marginRight: spacing.md,
  },
  userAvatar: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: spacing.md,
  },
  userAvatarIcon: {
    fontSize: 20,
  },
  bubble: {
    maxWidth: '70%',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...shadow.card,
  },
  bubbleAi: {
    backgroundColor: color.background.surface,
    borderTopLeftRadius: 2,
    borderTopRightRadius: radius.md,
    borderBottomLeftRadius: radius.md,
    borderBottomRightRadius: radius.md,
  },
  bubbleUser: {
    backgroundColor: color.brand.primary,
    borderTopLeftRadius: radius.md,
    borderTopRightRadius: 2,
    borderBottomLeftRadius: radius.md,
    borderBottomRightRadius: radius.md,
  },
  bubbleText: {
    // 原文案为多行缩进文本，行高需要比正文更大（24dp），否则缩进层级会糊在一起。
    fontSize: fontSize.bodyLarge,
    lineHeight: 24,
  },
  bubbleTextAi: {
    color: color.text.body,
  },
  bubbleTextUser: {
    color: color.text.onBrand,
  },

  /* ── 输入区 ───────────────────────────────────────────── */
  inputSection: {
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.md,
    backgroundColor: color.background.canvas,
    borderTopWidth: 1,
    borderTopColor: color.border.outline,
  },
  inputWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: color.background.surface,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
  },
  input: {
    ...typography.body,
    flex: 1,
    maxHeight: 90,
    marginHorizontal: spacing.sm,
    color: color.text.body,
    paddingVertical: spacing.xs,
  },
  inputIcon: {
    width: 24,
    height: 24,
    marginHorizontal: spacing.xs / 2,
  },
});
