import { Ionicons } from '@expo/vector-icons';
import {
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { color, radius, size, spacing, typography } from '@/shared/theme';

/**
 * 搜索框。
 *
 * 两种模式：
 * - 只读态（`editable=false`）：原首页的「模拟搜索框」，点击整块跳转到案件页，
 *   本身不接收输入。渲染为 `Text` 而非禁用的 `TextInput`，避免 Android 上点击后
 *   仍然获得焦点弹出键盘。
 * - 可输入态：律师列表页、案件页顶部的真实搜索框。
 *
 * 两种形态的圆角不同（首页 6dp / 列表页胶囊 999），这是原项目的实际差异，予以保留。
 *
 * @param placeholder 占位文案
 * @param editable 是否可输入，默认 `false`
 * @param value 受控值，仅在可输入态使用
 * @param onChangeText 输入回调，仅在可输入态使用
 * @param onPress 只读态的点击回调
 */
export function SearchBar({
  placeholder,
  editable = false,
  value,
  onChangeText,
  onPress,
  style,
}: {
  placeholder: string;
  editable?: boolean;
  value?: string;
  onChangeText?: (text: string) => void;
  onPress?: () => void;
  style?: StyleProp<ViewStyle>;
}) {
  if (!editable) {
    return (
      <Pressable
        onPress={onPress}
        style={({ pressed }) => [
          styles.container,
          styles.containerSoft,
          pressed ? styles.pressed : null,
          style,
        ]}
        accessibilityRole="button"
        accessibilityLabel={placeholder}
      >
        <Ionicons name="search" size={16} color={color.text.muted} />
        <Text style={styles.placeholder} numberOfLines={1}>
          {placeholder}
        </Text>
      </Pressable>
    );
  }

  return (
    <View style={[styles.container, styles.containerPill, style]}>
      <Ionicons name="search" size={16} color={color.text.muted} />
      <TextInput
        style={styles.input}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={color.text.muted}
        returnKeyType="search"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 44,
    paddingHorizontal: spacing.lg,
  },
  /** 首页模拟搜索框：灰底小圆角，对应原 `#F5F5F5 + border-radius: 12rpx`。 */
  containerSoft: {
    backgroundColor: color.background.input,
    borderRadius: radius.sm,
  },
  /** 列表页真实搜索框：白底胶囊描边，对应原 `#F2F3F5 + border-radius: 38rpx`。 */
  containerPill: {
    backgroundColor: color.background.surface,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: color.border.subtle,
  },
  pressed: {
    opacity: 0.7,
  },
  placeholder: {
    ...typography.body,
    flex: 1,
    marginLeft: spacing.sm,
    color: color.text.muted,
  },
  input: {
    ...typography.body,
    flex: 1,
    height: size.inputHeight - 6,
    marginLeft: spacing.sm,
    color: color.text.body,
    paddingVertical: 0,
  },
});
