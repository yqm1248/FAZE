/**
 * 地区选择器（注册页「所在地区」专用）。
 *
 * 职责：渲染一行「标签 + 当前值/占位 + 箭头」，点击后从底部弹层里单选一个地区。
 * 调用方：`features/auth/pages/RegisterPage.tsx`。
 * 数据来源：`options` 由页面传入（服务端 `/pub/regions` 或内置默认列表），本组件不发请求。
 * 注意：
 *   1. **不引入 `@react-native-picker/picker`**（项目没装该依赖，AGENTS §12 要求新增依赖先说明理由）：
 *      用 `Modal` + `ScrollView` 自绘底部弹层，交互形态与项目里已有的底部浮层（设置页字号/深色）一致。
 *   2. 地区名是中文专有名词，**原样展示不翻译**；标签、占位、关闭按钮走调用方传入的已翻译文案。
 *   3. 选中项用「勾选图标 + 加粗文字」双重标记，不只靠颜色（与徽标规范一致）。
 *   4. 组件受控：`value` 与 `onSelect` 都由页面持有，组件不存内部选中态（除弹层开关）。
 */

import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { useI18n } from '@/shared/i18n';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** `RegionPicker` 的属性。 */
export type RegionPickerProps = {
  /** 字段标签（已翻译）。 */
  label: string;
  /** 当前选中值；空串表示未选。 */
  value: string;
  /** 可选地区（中文专有名词，原样展示）。 */
  options: readonly string[];
  /** 未选时的占位文案（已翻译）。 */
  placeholder: string;
  /** 选中回调。 */
  onSelect: (region: string) => void;
  /** 字段级错误（已翻译），可选。 */
  error?: string | null;
};

/**
 * 地区选择器。
 *
 * 副作用：打开/关闭底部弹层（纯 UI 状态）。
 *
 * @param props 标签、当前值、选项、占位与回调
 * @returns 一行选择入口 + 底部弹层
 */
export function RegionPicker({
  label,
  value,
  options,
  placeholder,
  onSelect,
  error,
}: RegionPickerProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const hasValue = value !== '';

  return (
    <View style={styles.field}>
      <LocalizedText numberOfLines={1} style={styles.label}>
        {label}
      </LocalizedText>

      <Pressable
        onPress={() => setOpen(true)}
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityValue={{ text: hasValue ? value : placeholder }}
        style={({ pressed }) => [styles.trigger, pressed ? styles.pressed : null]}
      >
        <LocalizedText
          numberOfLines={1}
          style={[styles.value, hasValue ? styles.valueFilled : styles.valuePlaceholder]}
        >
          {hasValue ? value : placeholder}
        </LocalizedText>
        <Ionicons name="chevron-down" size={16} color={color.text.muted} />
      </Pressable>

      {error === undefined || error === null ? null : (
        <LocalizedText numberOfLines={2} style={styles.error}>
          {error}
        </LocalizedText>
      )}

      <Modal
        visible={open}
        transparent
        animationType="slide"
        onRequestClose={() => setOpen(false)}
      >
        <View style={styles.mask}>
          <Pressable
            style={StyleSheet.absoluteFill}
            onPress={() => setOpen(false)}
            accessibilityRole="button"
            accessibilityLabel={t('auth.register.regionClose')}
          />
          <View style={styles.sheet}>
            <View style={styles.sheetHead}>
              <LocalizedText numberOfLines={1} style={styles.sheetTitle}>
                {label}
              </LocalizedText>
              <Pressable
                onPress={() => setOpen(false)}
                accessibilityRole="button"
                accessibilityLabel={t('auth.register.regionClose')}
                hitSlop={spacing.sm}
              >
                <Ionicons name="close" size={22} color={color.text.muted} />
              </Pressable>
            </View>
            <ScrollView style={styles.sheetScroll} contentContainerStyle={styles.sheetList}>
              {options.map((option) => {
                const active = option === value;
                return (
                  <Pressable
                    key={option}
                    onPress={() => {
                      onSelect(option);
                      setOpen(false);
                    }}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={option}
                    style={({ pressed }) => [
                      styles.option,
                      pressed ? styles.pressed : null,
                    ]}
                  >
                    <LocalizedText numberOfLines={1} style={[styles.optionText, active ? styles.optionTextActive : null]}>
                      {option}
                    </LocalizedText>
                    {active ? (
                      <Ionicons name="checkmark" size={20} color={color.brand.primary} />
                    ) : null}
                  </Pressable>
                );
              })}
            </ScrollView>
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  field: {
    marginTop: spacing.lg,
  },
  label: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginBottom: spacing.xs,
  },
  trigger: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: size.inputHeight,
    paddingHorizontal: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  pressed: {
    opacity: 0.7,
  },
  value: {
    ...typography.body,
    flex: 1,
  },
  valueFilled: {
    color: color.text.body,
  },
  valuePlaceholder: {
    color: color.text.muted,
  },
  error: {
    ...typography.caption,
    color: color.state.danger,
    marginTop: spacing.xxs,
  },
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
    justifyContent: 'flex-end',
  },
  sheet: {
    maxHeight: '70%',
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.lg,
    borderTopEndRadius: radius.lg,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxl,
  },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.xl,
    marginBottom: spacing.xs,
  },
  sheetTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  sheetScroll: {
    maxHeight: 420,
  },
  sheetList: {
    paddingHorizontal: spacing.xl,
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 52,
    paddingVertical: spacing.sm,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  optionText: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.body,
  },
  optionTextActive: {
    fontWeight: typography.h3.fontWeight,
    color: color.brand.primary,
  },
});
