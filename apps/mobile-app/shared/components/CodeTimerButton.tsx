/**
 * 「获取验证码」按钮（含 60 秒倒计时）。
 *
 * 职责：短信验证码按钮的唯一实现——点击后调用调用方给的发送逻辑，成功则开始倒计时，
 * 失败保持可点（用户可以直接重试）。
 * 调用方：注册、忘记密码、换绑手机（两个验证码）、支付密码重置等页面。
 * 数据来源：倒计时秒数来自 `sys_config.sms_send_interval_seconds`（当前 60 秒），
 *           段 1 无公开配置接口，先按 60 秒常量；页面不各写一个数字。
 * 注意：
 *   1. **成功后才倒计时**：发送失败还倒计时会让用户被锁 60 秒不能重试。
 *   2. 卸载后清理定时器（`clearInterval`），避免返回上一页后仍在跑。
 *   3. 倒计时文案由调用方以函数给出（`resendLabel(seconds)`），便于各页复用不同 i18n key。
 */

import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** 短信发送间隔（秒）。与后端 `sms_send_interval_seconds` 默认值一致。 */
export const SMS_RESEND_SECONDS = 60;

/** `CodeTimerButton` 的属性。 */
export type CodeTimerButtonProps = {
  /** 空闲态文案（已翻译）。 */
  label: string;
  /** 倒计时态文案生成函数，入参为剩余秒数。 */
  resendLabel: (seconds: number) => string;
  /** 发送逻辑；返回 true 表示已发出、开始倒计时。 */
  onSend: () => Promise<boolean>;
  /** 外部禁用（例如还没填手机号）。 */
  disabled?: boolean;
};

/**
 * 渲染验证码按钮。
 *
 * 副作用：发送成功后在本地跑一个 60 秒倒计时。
 *
 * @param props 文案、发送逻辑与禁用态
 * @returns 可点击的胶囊按钮
 */
export function CodeTimerButton({ label, resendLabel, onSend, disabled = false }: CodeTimerButtonProps) {
  const [seconds, setSeconds] = useState(0);
  const [sending, setSending] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(
    () => () => {
      if (timerRef.current !== null) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    },
    [],
  );

  /**
   * 点击处理：先发码，成功后才开始倒计时。
   *
   * 副作用：置 sending；成功后启动 interval 并每 1000ms 递减。
   */
  const handlePress = async () => {
    if (seconds > 0 || sending) {
      return;
    }
    setSending(true);
    try {
      const ok = await onSend();
      if (!ok) {
        return;
      }
      setSeconds(SMS_RESEND_SECONDS);
      timerRef.current = setInterval(() => {
        setSeconds((value) => {
          if (value <= 1) {
            if (timerRef.current !== null) {
              clearInterval(timerRef.current);
              timerRef.current = null;
            }
            return 0;
          }
          return value - 1;
        });
      }, 1000);
    } finally {
      setSending(false);
    }
  };

  const blocked = disabled || seconds > 0 || sending;

  return (
    <Pressable
      onPress={() => {
        void handlePress();
      }}
      disabled={blocked}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: blocked }}
      style={({ pressed }) => [
        styles.button,
        blocked ? styles.disabled : null,
        pressed && !blocked ? styles.pressed : null,
      ]}
    >
      <LocalizedText numberOfLines={1} style={[styles.text, blocked ? styles.textDisabled : null]}>
        {seconds > 0 ? resendLabel(seconds) : label}
      </LocalizedText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 32,
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
  },
  pressed: {
    opacity: 0.7,
  },
  disabled: {
    backgroundColor: color.background.input,
  },
  text: {
    ...typography.caption,
    color: color.brand.primary,
  },
  textDisabled: {
    color: color.text.muted,
  },
});
