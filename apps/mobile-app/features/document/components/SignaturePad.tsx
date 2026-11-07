/**
 * 手写签名板（段 4 E6）。
 *
 * 职责：在一块画布上手写签名，支持清空 / 撤销 / 确认；确认时导出**透明底 PNG** 的 base64
 *      dataURL，交给 `DocSealPage` 走三段式上传（`scene=signature`）。
 * 调用方：`features/document/pages/DocSealPage.tsx`（新建签章面板）。
 * 数据来源：本组件自绘，不请求任何接口；导出物由调用方上传。
 * 注意：
 *   1. **Web 验收用 `react-native-svg`**（工作流 §三 E6 的 2026-11-07 裁决），
 *      不用 skia（原生模块 + CanvasKit wasm，Expo Web 跑不动，留段 8）；
 *      手势用 `react-native-gesture-handler` 的 `Gesture.Pan`。
 *   2. **导出必须是透明底**：`<Svg>` 自身不设背景色，Web 端 `toDataURL` 走 canvas
 *      （canvas 默认透明），所以导出的 PNG 没有白底。**白底图叠到 PDF 上会是一个白块盖住正文**，
 *      这是 E6 的第一个「常见失败」。
 *   3. **RTL 下坐标不许镜像错位**：笔迹是图像不是文字，板内坐标系恒为 LTR——
 *      画布容器显式 `direction: 'ltr'`，把 App 的 RTL 挡在板外；坐标一律取手势的局部
 *      `x/y`，**不做任何 `I18nManager` 反转**（AGENTS §4.9：手动反转会和 RN 自动镜像叠加，
 *      「翻两遍等于没翻」）。
 *   4. Web 端 `Svg.toDataURL(callback, options)` 返回**不带 `data:` 前缀**的 base64，
 *      本组件统一拼成 `data:image/png;base64,...`，调用方按 dataURL 处理即可。
 *   5. 只导出「笔画」，不导出任何水印或提示文字——提示文字是 DOM 层，不进 PNG。
 */

import { useCallback, useMemo, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import type { LayoutChangeEvent } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import { Path, Svg } from 'react-native-svg';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { useI18n } from '@/shared/i18n';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 画布上的一个点（局部坐标，单位 dp = SVG 用户单位）。 */
type Point = {
  /** 横坐标。 */
  x: number;
  /** 纵坐标。 */
  y: number;
};

/** 一笔 = 一串点（抬笔后成一条 `Path`）。 */
type Stroke = Point[];

/** `SignaturePad` 的属性。 */
export type SignaturePadProps = {
  /**
   * 点「确认签名」时回调。
   *
   * @param dataUrl 透明底 PNG 的 dataURL（`data:image/png;base64,...`）
   */
  onConfirm: (dataUrl: string) => void;
  /** 是否禁用（上传中 / 提交中）。 */
  disabled?: boolean;
  /** 画布高度（dp），默认 180。 */
  height?: number;
};

/** 画笔粗细（dp）。 */
const STROKE_WIDTH = 2.4;

/**
 * 把一串点转成 SVG path 的 `d` 属性。
 *
 * 单点也要能画出来（点一下等于一个墨点）：给一个极短的线段，
 * 否则 `M x y` 在 `fill=none` 下什么都不显示。
 *
 * @param stroke 一笔的点序列
 * @returns path 的 `d` 字符串
 */
function toPathData(stroke: Stroke): string {
  const first = stroke[0];
  if (first === undefined) {
    return '';
  }
  const commands = stroke.map(
    (point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(1)} ${point.y.toFixed(1)}`,
  );
  if (stroke.length === 1) {
    return `${commands[0]} L ${(first.x + 0.1).toFixed(1)} ${(first.y + 0.1).toFixed(1)}`;
  }
  return commands.join(' ');
}

/**
 * 手写签名板。
 *
 * 副作用：持有本地笔迹状态；确认时调用 `Svg.toDataURL` 异步回调 `onConfirm`。
 *
 * @param props 回调、禁用态、画布高度
 * @returns 画布 + 清空 / 撤销 / 确认按钮
 */
export function SignaturePad({ onConfirm, disabled = false, height = 180 }: SignaturePadProps) {
  const { t } = useI18n();
  const svgRef = useRef<Svg | null>(null);
  const [boardWidth, setBoardWidth] = useState(0);
  const [boardHeight, setBoardHeight] = useState(height);
  const [strokes, setStrokes] = useState<Stroke[]>([]);

  /** 画布尺寸变化（首次布局、转屏、RTL 下宽度可能变）。 */
  const handleLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height: measuredHeight } = event.nativeEvent.layout;
    setBoardWidth(width);
    setBoardHeight(measuredHeight);
  }, []);

  /** 起笔：新开一笔。 */
  const handleBegin = useCallback((x: number, y: number) => {
    setStrokes((previous) => [...previous, [{ x, y }]]);
  }, []);

  /** 运笔：把点追加到最后一笔。 */
  const handleMove = useCallback((x: number, y: number) => {
    setStrokes((previous) => {
      if (previous.length === 0) {
        return previous;
      }
      const last = previous[previous.length - 1];
      if (last === undefined) {
        return previous;
      }
      const next = previous.slice(0, -1);
      next.push([...last, { x, y }]);
      return next;
    });
  }, []);

  // runOnJS(true)：回调要 setState，必须回到 JS 线程执行。
  const pan = useMemo(
    () =>
      Gesture.Pan()
        .enabled(!disabled)
        .minDistance(0)
        .runOnJS(true)
        .onBegin((event) => {
          handleBegin(event.x, event.y);
        })
        .onUpdate((event) => {
          handleMove(event.x, event.y);
        }),
    [disabled, handleBegin, handleMove],
  );

  /** 清空全部笔迹。 */
  const clear = useCallback(() => {
    setStrokes([]);
  }, []);

  /** 撤销最后一笔。 */
  const undo = useCallback(() => {
    setStrokes((previous) => previous.slice(0, -1));
  }, []);

  const hasStroke = strokes.length > 0;

  /**
   * 确认签名：把画布导成透明底 PNG 的 dataURL。
   *
   * 副作用：读取 SVG 节点的位图（Web 走 canvas，原生走 SVG 模块），随后回调 `onConfirm`。
   */
  const confirm = useCallback(() => {
    const node = svgRef.current;
    if (node === null || boardWidth <= 0 || boardHeight <= 0) {
      return;
    }
    node.toDataURL(
      (base64: string) => {
        onConfirm(`data:image/png;base64,${base64}`);
      },
      { width: Math.round(boardWidth), height: Math.round(boardHeight) },
    );
  }, [boardHeight, boardWidth, onConfirm]);

  return (
    <View style={styles.wrap} testID="docSeal.pad">
      {/*
        画布容器显式 LTR：手写坐标与 SVG 用户坐标必须同向，
        否则阿拉伯系界面下 Web 会把 x 轴镜像，笔画左右颠倒（E6 的第二个「常见失败」）。
      */}
      <View
        style={[styles.board, { height }, boardWidth > 0 ? null : styles.boardPending]}
        onLayout={handleLayout}
        testID="docSeal.pad.board"
      >
        <GestureDetector gesture={pan}>
          <View style={styles.canvas} collapsable={false}>
            <Svg ref={svgRef} width={boardWidth} height={boardHeight}>
              {strokes.map((stroke, index) => (
                <Path
                  // 笔迹没有业务 id，用下标即可：清空/撤销都是整笔增删。
                  key={`stroke-${index}`}
                  d={toPathData(stroke)}
                  stroke={color.text.primary}
                  strokeWidth={STROKE_WIDTH}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  fill="none"
                />
              ))}
            </Svg>
          </View>
        </GestureDetector>

        {hasStroke ? null : (
          <View pointerEvents="none" style={styles.placeholder}>
            <LocalizedText numberOfLines={2} style={styles.placeholderText}>
              {t('document.seal.padHint')}
            </LocalizedText>
          </View>
        )}
      </View>

      <View style={styles.controls}>
        <PrimaryButton
          label={t('document.seal.padClear')}
          variant="outline"
          onPress={clear}
          disabled={disabled || !hasStroke}
          style={styles.control}
          testID="docSeal.pad.clear"
        />
        <PrimaryButton
          label={t('document.seal.padUndo')}
          variant="outline"
          onPress={undo}
          disabled={disabled || !hasStroke}
          style={styles.control}
          testID="docSeal.pad.undo"
        />
        <PrimaryButton
          label={t('document.seal.padConfirm')}
          onPress={confirm}
          disabled={disabled || !hasStroke}
          style={styles.control}
          testID="docSeal.pad.confirm"
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: spacing.md,
  },
  board: {
    position: 'relative',
    // 手写板是图像区域：坐标恒定 LTR，不跟随 App 的 RTL 镜像。
    direction: 'ltr',
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    // 白底只是「纸」，不进 PNG：Svg 自身没有背景色，导出仍是透明的。
    backgroundColor: color.background.surface,
    overflow: 'hidden',
  },
  boardPending: {
    opacity: 0.6,
  },
  canvas: {
    flex: 1,
  },
  placeholder: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.lg,
  },
  placeholderText: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
  controls: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  control: {
    flex: 1,
    minHeight: size.inputHeight,
  },
});
