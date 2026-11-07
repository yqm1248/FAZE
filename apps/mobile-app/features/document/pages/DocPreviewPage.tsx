/**
 * 文书预览页（段 4 E4）。
 *
 * 职责：展示生成好的文书（服务端把 PDF 每页渲染成 PNG，App 只显示图片列表），**只读**；
 *      提供三个出口——导出 PDF（F1 面板）、盖章（D2 流程）、返回表单修改。
 * 路由：`app/doc/preview.tsx` → `/doc/preview?id=<文书主键>&documentId=<文书主键>&generating=1`
 *      （`id` 与 `documentId` 两个参数都认：E2 跳转用 `id`，E5 与我方跳转用 `documentId`）。
 * 数据来源：
 *   - `GET /app/documents/{id}`（状态 / 失败原因，`features/document/api/client.ts` 的 `fetchDocument`）；
 *   - `GET /app/documents/{id}/preview`（每页一张 PNG 的 60 秒签名地址，`fetchPreview`）；
 *   - `POST /app/documents/{id}/generate`（未生成态 / 失败态点「立即生成」，`generateDocument`）；
 *   - `GET /app/documents/{id}/export?format=pdf`（导出面板，见 `features/document/api/export.ts`）；
 *   - `GET /app/signatures` + `POST /app/documents/{id}/sign`（盖章面板，`listSignatures` / `signDocument`）。
 * 注意：
 *   1. **只读**：整页没有任何 `TextInput`，也不做内联编辑。工作流 §三 E4 第 2 条与
 *      设计稿里「点击任意段落可直接修改」的文案/交互**明确不做**，已删除。
 *      预览是 PNG，点段落无法知道点的是哪个字段，因此也**不做段落定位**，只整页跳回表单。
 *   2. 四态：`draft` → 未生成态；生成中 → 加载态（3 秒轮询、60 秒超时提示）；
 *      `generated`/`signed` → 正常；`failed` → 错误态 + 服务端给的原因（不白屏）。
 *   3. **「未生成」与「生成中」以详情接口的 `generating` 为准**（2026-11-07 与 Lead 定案）：
 *      后端生成期间 `status` 仍是 `draft`（`AppDocumentService.generate` 先重置状态再投递任务），
 *      因此 `DocumentDetailVO` 加了 `generating: boolean`（服务端按任务队列 `has_active_task` 实时算）。
 *        - `status==='draft' && generating` → 生成中态：3 秒轮询、60 秒超时提示；
 *        - `status==='draft' && !generating` → 未生成态（不再空转轮询）。
 *      `?generating=1` 参数只在「刚点完生成、任务还没入队」的几百毫秒内兜底，不作为主判据。
 *   4. 预览图与导出地址都是**现签的 60 秒 URL**：不缓存、不持久化，超时重新调接口。
 *   5. 盖章位置不是写死的：用户点一下预览页选落款位置，坐标按 `widthPt/heightPt` 与点击比例换算，
 *      并把 `basePageW/basePageH` 一起上报，服务端据此缩放（工作流 `4-A5` 的「坐标写死」失败点）。
 *   6. RTL：只写 `start/end`；预览页内部坐标系恒 LTR（`direction: 'ltr'`），
 *      否则阿拉伯系下点击位置的 x 轴会被镜像，签章位置算反。
 *   7. testID 清单（真的都挂上了）：
 *      `docPreview.root`、`docPreview.state.loading`、`docPreview.state.error`、
 *      `docPreview.state.draft`、`docPreview.state.generating`、`docPreview.state.timeout`、
 *      `docPreview.state.failed`、`docPreview.preview.list`、`docPreview.preview.page`、
 *      `docPreview.action.generate`、`docPreview.action.export`、`docPreview.action.seal`、
 *      `docPreview.action.backToForm`、`docPreview.export.sheet`、`docPreview.export.download`、
 *      `docPreview.export.share`、`docPreview.export.print`、`docPreview.export.gallery`、
 *      `docPreview.sign.sheet`、`docPreview.sign.confirm`、`docPreview.sign.manage`、
 *      `docPreview.sign.retry`、`docPreview.sign.signature`。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useLocalSearchParams, useRouter, type Href } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  View,
  type GestureResponderEvent,
  type LayoutChangeEvent,
} from 'react-native';

import type { AppError } from '@/shared/api/types';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { TagChip } from '@/shared/components/TagChip';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { useI18n } from '@/shared/i18n';
import { color, radius, shadow, size, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

import {
  fetchDocument,
  fetchPreview,
  generateDocument,
  listSignatures,
  signDocument,
} from '../api/client';
import type { DocumentDetail, PreviewPage, SignatureItem } from '../api/client';
import {
  downloadDocument,
  isDownloadSupported,
  isGallerySupported,
  isPrintSupported,
  isShareSupported,
  printCurrentPage,
  shareDocument,
} from '../api/export';
import type { ExportKind, ExportOutcome } from '../api/export';

/**
 * 路由：文书预览页与表单页。
 *
 * 为什么不从 `shared/navigation/routes.ts` 取：那份常量表不在本任务的写范围内
 * （并行开发，改它会造成写冲突）。等段 4 收口时把这两条搬进去，本文件跟着换成常量。
 */
/** 表单页路由（E2/E3 的起诉状表单；`id` 传模板主键，`documentId` 传草稿主键）。 */
const FORM_ROUTE = '/doc/lawsuit';
/** 签章管理页路由（E6）。 */
const SEALS_ROUTE = '/doc/seals';

/** 轮询间隔（毫秒）：工作流 §三 E4 第 5 条 —— 3 秒。 */
const POLL_INTERVAL_MS = 3000;
/** 轮询上限（毫秒）：60 秒后提示超时。 */
const POLL_TIMEOUT_MS = 60_000;

/** 盖章时的签名框尺寸（pt）：A4 页上约 4.2cm × 2cm，接近手写落款的观感。 */
const SIGNATURE_WIDTH_PT = 120;
/** 盖章时的签名框高度（pt）。 */
const SIGNATURE_HEIGHT_PT = 56;

/** 参数缺失时的错误：页面直接落错误态，**不白屏**。 */
const MISSING_ID_ERROR: AppError = {
  kind: 'business',
  code: 0,
  msgKey: 'error.common.param-invalid',
  traceId: null,
};

/** 预览接口返回空数组时的错误（后端可能还在渲染 PNG）。 */
const EMPTY_PREVIEW_ERROR: AppError = {
  kind: 'parse',
  msgKey: 'error.common.server',
  traceId: null,
};

/** 预览页的界面状态（比 E4 的「四态」多两态：生成中、超时）。 */
type PreviewPhase =
  | { kind: 'loading' }
  | { kind: 'error'; error: AppError }
  | {
      /** 未生成态。`generating` 来自详情接口的实时字段（服务端按任务队列算）。 */
      kind: 'draft';
      /** 服务端是否正在生成这份文书（`DocumentDetail.generating`）。 */
      generating: boolean;
    }
  | { kind: 'generating'; waitedSeconds: number }
  | { kind: 'timeout' }
  | { kind: 'failed'; reason: string | null }
  | { kind: 'ready'; pages: PreviewPage[]; doc: DocumentDetail };

/** 盖章位置（相对页面的比例，0~1）。 */
type SignPlace = {
  /** 页码，从 1 开始。 */
  pageNo: number;
  /** 横坐标占页面宽度的比例。 */
  xRatio: number;
  /** 纵坐标占页面高度的比例。 */
  yRatio: number;
};

/** 导出面板的一行。 */
type ExportRowProps = {
  /** 图标名（Ionicons）。 */
  icon: React.ComponentProps<typeof Ionicons>['name'];
  /** 名称（已翻译）。 */
  name: string;
  /** 说明（已翻译）。 */
  hint: string;
  /** 是否置灰。 */
  disabled: boolean;
  /** 是否正在执行（显示转圈）。 */
  busy: boolean;
  /** testID。 */
  testID: string;
  /** 点击回调（置灰时不触发）。 */
  onPress: () => void;
};

/**
 * 读取查询参数（兼容 expo-router 可能给数组的情况）。
 *
 * @param value 参数原值（`unknown`：路由参数在类型上是 `string | string[] | undefined`）
 * @returns 第一个字符串值；缺失时返回空串
 */
function readParam(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  if (Array.isArray(value)) {
    const first: unknown = value[0];
    return typeof first === 'string' ? first : '';
  }
  return '';
}

/**
 * 把 0~1 之外的值夹回区间内。
 *
 * @param value 原值
 * @returns 夹紧后的值
 */
function clamp01(value: number): number {
  if (value < 0) {
    return 0;
  }
  if (value > 1) {
    return 1;
  }
  return value;
}

/**
 * 预览页的数据流（详情 → 预览图 → 生成中轮询）。
 *
 * 副作用：挂载即请求详情；生成中每 3 秒请求一次详情；卸载时清理定时器。
 * 所有 `setState` 都经过 `aliveRef` 判活，避免返回上一页后的告警。
 *
 * @param documentId 文书主键（空串 = 缺参数，直接错误态）
 * @param autoStart  是否从跳转参数得知「刚刚点了生成」（`?generating=1`）
 * @returns 当前状态、重载、发起生成
 */
function usePreviewFlow(
  documentId: string,
  autoStart: boolean,
): {
  phase: PreviewPhase;
  reload: () => void;
  startGenerate: () => Promise<void>;
} {
  const [phase, setPhase] = useState<PreviewPhase>({ kind: 'loading' });
  const [nonce, setNonce] = useState(0);
  const aliveRef = useRef(true);
  /** 是否要按「生成中」轮询（本页点过生成 / 跳转参数带来）。 */
  const pollRequestedRef = useRef(false);

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (documentId === '') {
      setPhase({ kind: 'error', error: MISSING_ID_ERROR });
      return;
    }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const startedAt = Date.now();
    const explicitPoll = autoStart || pollRequestedRef.current;

    /**
     * 详情 → 界面状态。
     *
     * @param detail 文书详情
     * @returns 该详情对应的界面状态
     */
    const resolve = async (detail: DocumentDetail): Promise<PreviewPhase> => {
      if (detail.status === 'failed') {
        return { kind: 'failed', reason: detail.errorMsg };
      }
      if (detail.status === 'generated' || detail.status === 'signed') {
        const preview = await fetchPreview(documentId);
        if (!preview.ok) {
          return { kind: 'error', error: preview.error };
        }
        if (preview.data.length === 0) {
          return { kind: 'error', error: EMPTY_PREVIEW_ERROR };
        }
        return { kind: 'ready', pages: preview.data, doc: detail };
      }
      return { kind: 'draft', generating: detail.generating };
    };

    /**
     * 一次探测。
     *
     * @returns 是否还要继续轮询
     */
    const probe = async (): Promise<boolean> => {
      const detail = await fetchDocument(documentId);
      if (cancelled || !aliveRef.current) {
        return false;
      }
      if (!detail.ok) {
        setPhase({ kind: 'error', error: detail.error });
        return false;
      }
      const next = await resolve(detail.data);
      if (cancelled || !aliveRef.current) {
        return false;
      }
      if (next.kind !== 'draft') {
        setPhase(next);
        return false;
      }
      // 主判据是服务端的 `generating`（后端按任务队列实时算）；
      // `explicitPoll` 只覆盖「刚点完生成本页还没轮到任务入队」的那几百毫秒。
      const waiting = next.generating || explicitPoll;
      if (!waiting) {
        setPhase(next);
        return false;
      }
      const waited = Date.now() - startedAt;
      if (waited >= POLL_TIMEOUT_MS) {
        setPhase({ kind: 'timeout' });
        return false;
      }
      setPhase({ kind: 'generating', waitedSeconds: Math.round(waited / 1000) });
      return true;
    };

    /** 轮询循环（setTimeout 串起来，避免 setInterval 叠请求）。 */
    const loop = async (): Promise<void> => {
      const shouldContinue = await probe();
      if (cancelled || !shouldContinue) {
        return;
      }
      timer = setTimeout(() => {
        void loop();
      }, POLL_INTERVAL_MS);
    };

    // 已有内容时不要退回骨架屏（重载 / 盖章后刷新），避免画面闪一下。
    setPhase((current) => (current.kind === 'ready' ? current : { kind: 'loading' }));
    void loop();

    return () => {
      cancelled = true;
      if (timer !== null) {
        clearTimeout(timer);
      }
    };
  }, [autoStart, documentId, nonce]);

  /** 重新走一遍数据流（错误态重试、盖章后刷新）。 */
  const reload = useCallback(() => {
    setNonce((value) => value + 1);
  }, []);

  /**
   * 立即生成：投递任务后进生成中态并轮询。
   *
   * 副作用：调用 `POST /app/documents/{id}/generate`（服务端幂等，重复点不会生成两份）。
   */
  const startGenerate = useCallback(async () => {
    pollRequestedRef.current = true;
    setPhase({ kind: 'generating', waitedSeconds: 0 });
    const result = await generateDocument(documentId);
    if (!aliveRef.current) {
      return;
    }
    if (!result.ok) {
      setPhase({ kind: 'error', error: result.error });
      return;
    }
    setNonce((value) => value + 1);
  }, [documentId]);

  return { phase, reload, startGenerate };
}

/**
 * 导出面板的一行。
 *
 * 副作用：无（点击由调用方处理）。
 *
 * @param props 图标、文案、置灰态、回调
 * @returns 可点击的行
 */
function ExportRow({ icon, name, hint, disabled, busy, testID, onPress }: ExportRowProps) {
  return (
    <Pressable
      testID={testID}
      onPress={disabled ? undefined : onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      style={({ pressed }) => [
        styles.exportItem,
        pressed && !disabled ? styles.exportItemPressed : null,
        disabled ? styles.exportItemDisabled : null,
      ]}
    >
      <View style={styles.exportIcon}>
        {busy ? (
          <ActivityIndicator size="small" color={color.brand.primaryPressed} />
        ) : (
          <Ionicons name={icon} size={20} color={color.brand.primaryPressed} />
        )}
      </View>
      <View style={styles.exportBody}>
        <LocalizedText numberOfLines={1} style={styles.exportName}>
          {name}
        </LocalizedText>
        <LocalizedText numberOfLines={2} style={styles.exportHint}>
          {hint}
        </LocalizedText>
      </View>
    </Pressable>
  );
}

/**
 * 文书预览页。
 *
 * 副作用：挂载拉详情与预览图；生成中轮询；导出/盖章写接口。
 *
 * @returns 预览页（四态齐全 + 底部三个出口）
 */
export function DocPreviewPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const barSpace = useActionBarSpace();
  const params = useLocalSearchParams<{
    id?: string;
    documentId?: string;
    generating?: string;
  }>();

  // id 与 documentId 都认：E2 跳转只带 id，E5 / 我的文书带 documentId。
  const documentId = readParam(params.documentId) === '' ? readParam(params.id) : readParam(params.documentId);
  const autoStart = readParam(params.generating) === '1';
  const flow = usePreviewFlow(documentId, autoStart);

  const [exportOpen, setExportOpen] = useState(false);
  const [busyExport, setBusyExport] = useState<ExportKind | null>(null);
  const [sealOpen, setSealOpen] = useState(false);
  /** 是否处于「点预览页选落款位置」模式。 */
  const [placing, setPlacing] = useState(false);
  const [place, setPlace] = useState<SignPlace | null>(null);
  const [signatures, setSignatures] = useState<SignatureItem[]>([]);
  const [signaturesLoading, setSignaturesLoading] = useState(false);
  const [signatureError, setSignatureError] = useState<AppError | null>(null);
  const [pickedSignatureId, setPickedSignatureId] = useState<string | null>(null);
  const [signing, setSigning] = useState(false);
  /** 第一页渲染宽度（所有页同宽，用于把点击位置换算成比例）。 */
  const [pageWidth, setPageWidth] = useState(0);
  /** 每一页容器的 view 引用（点落款位置时用它 measureInWindow 换算比例）。 */
  const pageRefs = useRef<Map<number, View | null>>(new Map());

  const ready = flow.phase.kind === 'ready' ? flow.phase : null;
  const downloadSupported = isDownloadSupported();
  const shareSupported = isShareSupported();
  const printSupported = isPrintSupported();
  const gallerySupported = isGallerySupported();
  const actionEnabled = ready !== null;

  /** 返回表单修改：带上模板主键与文书主键，表单页据此回填草稿。 */
  const backToForm = useCallback(() => {
    if (ready === null) {
      // 详情还没拿到（加载中 / 错误态）：只能进表单页，让它按模板列表自己兜底。
      router.push(FORM_ROUTE as Href);
      return;
    }
    const query = new URLSearchParams();
    if (ready.doc.templateId !== null) {
      query.set('id', ready.doc.templateId);
    }
    query.set('documentId', ready.doc.id);
    router.push(`${FORM_ROUTE}?${query.toString()}` as Href);
  }, [ready, router]);

  /** 加载签名库（盖章面板打开时）。 */
  const loadSignatures = useCallback(async () => {
    setSignaturesLoading(true);
    setSignatureError(null);
    const result = await listSignatures();
    setSignaturesLoading(false);
    if (!result.ok) {
      setSignatureError(result.error);
      return;
    }
    setSignatures(result.data);
    const first = result.data[0];
    setPickedSignatureId(first === undefined ? null : first.id);
  }, []);

  /**
   * 点预览页选落款位置。
   *
   * 为什么不用 `nativeEvent.locationX/locationY`：React Native Web 的 Pressable 在
   * 浏览器里**不保证**给出局部坐标（2026-11-07 实测恒为 0，标记框会落到纸面左上角），
   * 因此改用「窗口坐标 `pageX/pageY` + `measureInWindow`」自己换算，
   * 既不受 RNW 事件形状影响，也不会被 RTL 的 `inset-inline-*` 镜像二次翻转。
   *
   * 副作用：记录比例坐标并打开签章面板。
   *
   * @param page  被点的页
   * @param event 点击事件（取窗口坐标）
   */
  const handlePagePress = (page: PreviewPage, event: GestureResponderEvent) => {
    if (!placing) {
      return;
    }
    const node = pageRefs.current.get(page.pageNo) ?? null;
    if (node === null) {
      return;
    }
    const { pageX, pageY } = event.nativeEvent;
    if (!Number.isFinite(pageX) || !Number.isFinite(pageY)) {
      return;
    }
    node.measureInWindow((left, top, width, height) => {
      if (width <= 0 || height <= 0) {
        return;
      }
      const next: SignPlace = {
        pageNo: page.pageNo,
        xRatio: clamp01((pageX - left) / width),
        yRatio: clamp01((pageY - top) / height),
      };
      setPlace(next);
      setPlacing(false);
      setSealOpen(true);
      void loadSignatures();
    });
  };

  /** 执行一次导出动作。 */
  const runExport = useCallback(
    async (kind: ExportKind) => {
      if (busyExport !== null || ready === null) {
        return;
      }
      setBusyExport(kind);
      try {
        let outcome: ExportOutcome;
        if (kind === 'download') {
          outcome = await downloadDocument(documentId);
        } else if (kind === 'share') {
          outcome = await shareDocument(documentId, ready.doc.title);
        } else if (kind === 'print') {
          outcome = printCurrentPage();
        } else {
          // 存相册本段不做（expo-media-library 无 Web 实现），按钮已置灰，这里只是兜底。
          outcome = { status: 'unsupported' };
        }
        if (outcome.status === 'unsupported') {
          toast.show(
            kind === 'download'
              ? t('document.export.downloadUnsupported')
              : kind === 'share'
                ? t('document.export.shareUnsupported')
                : kind === 'print'
                  ? t('document.export.printUnsupported')
                  : t('document.export.galleryHint'),
          );
          return;
        }
        if (outcome.status === 'failed') {
          toast.show(
            kind === 'share'
              ? t('document.export.shareFailed')
              : kind === 'download'
                ? t('document.export.downloadFailed')
                : errorText(outcome.error),
          );
          return;
        }
        setExportOpen(false);
        toast.show(
          kind === 'download'
            ? t('document.export.downloadDone')
            : kind === 'share'
              ? t('document.export.shareStarted')
              : t('document.export.printStarted'),
        );
      } finally {
        setBusyExport(null);
      }
    },
    [busyExport, documentId, errorText, ready, t, toast],
  );

  /** 确认盖章：按选中的签名与位置提交坐标。 */
  const confirmSign = useCallback(async () => {
    if (place === null || pickedSignatureId === null || ready === null) {
      return;
    }
    const page = ready.pages.find((item) => item.pageNo === place.pageNo);
    if (page === undefined) {
      return;
    }
    setSigning(true);
    try {
      const result = await signDocument(documentId, {
        signatureId: pickedSignatureId,
        pageNo: page.pageNo,
        // 服务端的 posX/posY 是「相对页面左上角」的签名框左上角坐标；
        // 比例 × 页面 pt 尺寸 = 实际 pt，服务端再按 base_page_* 做缩放校验（4-A5）。
        posX: place.xRatio * page.widthPt,
        posY: place.yRatio * page.heightPt,
        width: SIGNATURE_WIDTH_PT,
        height: SIGNATURE_HEIGHT_PT,
        basePageW: page.widthPt,
        basePageH: page.heightPt,
      });
      if (!result.ok) {
        toast.show(errorText(result.error));
        return;
      }
      setSealOpen(false);
      setPlace(null);
      toast.show(t('document.preview.signSuccess'));
      flow.reload();
    } finally {
      setSigning(false);
    }
  }, [documentId, errorText, flow, pickedSignatureId, place, ready, t, toast]);

  /** 盖章入口：先进入「点位置」模式。 */
  const beginPlace = useCallback(() => {
    if (ready === null) {
      return;
    }
    setPlace(null);
    setPlacing(true);
  }, [ready]);

  /** 渲染一张预览页。 */
  const renderPage = (page: PreviewPage) => {
    const ratio = page.widthPt > 0 ? page.heightPt / page.widthPt : 1.414;
    const renderedHeight = pageWidth > 0 ? pageWidth * ratio : 0;
    const marker =
      place !== null && place.pageNo === page.pageNo && page.widthPt > 0 && page.heightPt > 0
        ? {
            start: place.xRatio * pageWidth,
            top: place.yRatio * renderedHeight,
            width: (SIGNATURE_WIDTH_PT / page.widthPt) * pageWidth,
            height: (SIGNATURE_HEIGHT_PT / page.heightPt) * renderedHeight,
          }
        : null;
    return (
      <Pressable
        key={page.pageNo}
        ref={(node) => {
          pageRefs.current.set(page.pageNo, node);
        }}
        testID="docPreview.preview.page"
        accessibilityRole="button"
        accessibilityLabel={t('document.preview.pageLabel', { pageNo: page.pageNo })}
        onPress={(event) => {
          handlePagePress(page, event);
        }}
        onLayout={(event: LayoutChangeEvent) => {
          if (pageWidth === 0) {
            setPageWidth(event.nativeEvent.layout.width);
          }
        }}
        style={[styles.paper, { aspectRatio: page.widthPt / (page.heightPt > 0 ? page.heightPt : 1) }]}
      >
        {/* 图片不接点击：让 Pressable 自己是事件目标，点击坐标才落在纸面容器上 */}
        <View pointerEvents="none" style={StyleSheet.absoluteFill}>
          <Image
            source={{ uri: page.imageUrl }}
            style={styles.pageImage}
            contentFit="contain"
            transition={0}
            accessibilityLabel={t('document.preview.pageLabel', { pageNo: page.pageNo })}
          />
        </View>
        {marker === null ? null : <View pointerEvents="none" style={[styles.marker, marker]} />}
        <View pointerEvents="none" style={styles.pageBadge}>
          <LocalizedText numberOfLines={1} style={styles.pageBadgeText}>
            {t('document.preview.pageLabel', { pageNo: page.pageNo })}
          </LocalizedText>
        </View>
      </Pressable>
    );
  };

  /** 渲染各状态的主体内容。 */
  const renderBody = () => {
    if (flow.phase.kind === 'loading') {
      return (
        <View testID="docPreview.state.loading">
          <LoadingState rows={4} />
        </View>
      );
    }
    if (flow.phase.kind === 'error') {
      return (
        <View testID="docPreview.state.error">
          <ErrorState
            error={flow.phase.error}
            onRetry={flow.reload}
            retryTestID="docPreview.state.retry"
          />
        </View>
      );
    }
    if (flow.phase.kind === 'draft') {
      return (
        <View testID="docPreview.state.draft" style={styles.stateCard}>
          <Ionicons name="document-text-outline" size={30} color={color.brand.primary} />
          <LocalizedText numberOfLines={2} style={styles.stateTitle}>
            {t('document.preview.draftTitle')}
          </LocalizedText>
          <LocalizedText numberOfLines={3} style={styles.stateDesc}>
            {t('document.preview.draftDesc')}
          </LocalizedText>
          <PrimaryButton
            label={t('document.preview.generate')}
            onPress={() => {
              void flow.startGenerate();
            }}
            testID="docPreview.action.generate"
          />
        </View>
      );
    }
    if (flow.phase.kind === 'generating') {
      return (
        <View testID="docPreview.state.generating" style={styles.stateCard}>
          <ActivityIndicator size="large" color={color.brand.primary} />
          <LocalizedText numberOfLines={2} style={styles.stateTitle}>
            {t('document.preview.generatingTitle')}
          </LocalizedText>
          <LocalizedText numberOfLines={3} style={styles.stateDesc}>
            {t('document.preview.generatingDesc')}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.stateMeta}>
            {t('document.preview.generatingWaited', { seconds: flow.phase.waitedSeconds })}
          </LocalizedText>
        </View>
      );
    }
    if (flow.phase.kind === 'timeout') {
      return (
        <View testID="docPreview.state.timeout" style={styles.stateCard}>
          <Ionicons name="time-outline" size={30} color={color.state.warning} />
          <LocalizedText numberOfLines={2} style={styles.stateTitle}>
            {t('document.preview.timeoutTitle')}
          </LocalizedText>
          <LocalizedText numberOfLines={4} style={styles.stateDesc}>
            {t('document.preview.timeoutDesc')}
          </LocalizedText>
          <PrimaryButton
            label={t('document.preview.retryGenerate')}
            onPress={() => {
              void flow.startGenerate();
            }}
            testID="docPreview.action.generate"
          />
          <PrimaryButton
            label={t('document.preview.reload')}
            variant="text"
            onPress={flow.reload}
            testID="docPreview.state.retry"
          />
        </View>
      );
    }
    if (flow.phase.kind === 'failed') {
      return (
        <View testID="docPreview.state.failed" style={styles.stateCard}>
          <Ionicons name="alert-circle-outline" size={30} color={color.state.danger} />
          <LocalizedText numberOfLines={2} style={styles.stateTitle}>
            {t('document.preview.failedTitle')}
          </LocalizedText>
          <LocalizedText numberOfLines={5} style={styles.stateDesc}>
            {flow.phase.reason === null || flow.phase.reason === ''
              ? t('document.preview.failedDesc')
              : flow.phase.reason}
          </LocalizedText>
          <PrimaryButton
            label={t('document.preview.retryGenerate')}
            onPress={() => {
              void flow.startGenerate();
            }}
            testID="docPreview.action.generate"
          />
        </View>
      );
    }

    const { pages, doc } = flow.phase;
    return (
      <View style={styles.ready}>
        <View style={styles.docHead}>
          <LocalizedText numberOfLines={2} style={styles.docTitle}>
            {doc.title}
          </LocalizedText>
          <TagChip
            label={
              doc.status === 'signed'
                ? t('document.preview.statusSigned')
                : t('document.preview.statusGenerated')
            }
            variant="state"
          />
        </View>
        <View testID="docPreview.preview.list" style={styles.pages}>
          {pages.map((page) => renderPage(page))}
        </View>
        <LocalizedText numberOfLines={3} style={styles.readonlyTip}>
          {t('document.preview.readonlyTip')}
        </LocalizedText>
      </View>
    );
  };

  /** 底部操作区（选位置模式时换成提示条）。 */
  const renderBar = () => {
    if (placing) {
      return (
        <View style={styles.placingRow}>
          <Ionicons name="hand-left-outline" size={20} color={color.brand.primary} />
          <LocalizedText numberOfLines={2} style={styles.placingText}>
            {t('document.preview.signPickHint')}
          </LocalizedText>
          <PrimaryButton
            label={t('common.action.cancel')}
            variant="text"
            fullWidth={false}
            onPress={() => {
              setPlacing(false);
            }}
            testID="docPreview.sign.cancelPlace"
          />
        </View>
      );
    }
    return (
      <>
        <View style={styles.actionRow}>
          <PrimaryButton
            label={t('document.preview.export')}
            variant="outline"
            onPress={() => {
              setExportOpen(true);
            }}
            disabled={!actionEnabled}
            style={styles.actionButton}
            testID="docPreview.action.export"
          />
          <PrimaryButton
            label={t('document.preview.seal')}
            onPress={beginPlace}
            disabled={!actionEnabled}
            style={styles.actionButton}
            testID="docPreview.action.seal"
          />
        </View>
        <PrimaryButton
          label={t('document.preview.backToForm')}
          variant="text"
          onPress={backToForm}
          testID="docPreview.action.backToForm"
        />
      </>
    );
  };

  const exportHint = useMemo(() => {
    if (!downloadSupported) {
      return t('document.export.downloadUnsupported');
    }
    return actionEnabled ? t('document.export.downloadHint') : t('document.export.notReady');
  }, [actionEnabled, downloadSupported, t]);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <View testID="docPreview.root" style={styles.root}>
        <AppHeader title={t('document.preview.title')} />
        <ScrollView
          contentContainerStyle={[styles.scroll, { paddingBottom: barSpace + size.buttonHeight }]}
        >
          {renderBody()}
        </ScrollView>

        <ActionBar>{renderBar()}</ActionBar>

        {/* ── 导出面板（F1 的 UI 载体）────────────────────────────────── */}
        <Modal
          visible={exportOpen}
          transparent
          animationType="slide"
          onRequestClose={() => {
            setExportOpen(false);
          }}
        >
          <Pressable
            style={styles.mask}
            onPress={() => {
              setExportOpen(false);
            }}
          />
          <View testID="docPreview.export.sheet" style={styles.sheet}>
            <View style={styles.sheetHead}>
              <LocalizedText numberOfLines={1} style={styles.sheetTitle}>
                {t('document.export.title')}
              </LocalizedText>
              <Pressable
                onPress={() => {
                  setExportOpen(false);
                }}
                accessibilityRole="button"
                accessibilityLabel={t('common.action.close')}
                style={styles.sheetClose}
              >
                <LocalizedText numberOfLines={1} style={styles.sheetCloseText}>
                  {t('common.action.close')}
                </LocalizedText>
              </Pressable>
            </View>

            <ExportRow
              icon="download-outline"
              name={t('document.export.download')}
              hint={exportHint}
              disabled={!actionEnabled || !downloadSupported || busyExport !== null}
              busy={busyExport === 'download'}
              testID="docPreview.export.download"
              onPress={() => {
                void runExport('download');
              }}
            />
            <ExportRow
              icon="share-social-outline"
              name={t('document.export.share')}
              hint={
                shareSupported
                  ? t('document.export.shareHint')
                  : t('document.export.shareUnsupported')
              }
              disabled={!actionEnabled || !shareSupported || busyExport !== null}
              busy={busyExport === 'share'}
              testID="docPreview.export.share"
              onPress={() => {
                void runExport('share');
              }}
            />
            <ExportRow
              icon="print-outline"
              name={t('document.export.print')}
              hint={
                printSupported
                  ? t('document.export.printHint')
                  : t('document.export.printUnsupported')
              }
              disabled={!actionEnabled || !printSupported || busyExport !== null}
              busy={busyExport === 'print'}
              testID="docPreview.export.print"
              onPress={() => {
                void runExport('print');
              }}
            />
            {/* 存相册本段不做：expo-media-library 无 Web 实现，留段 8（代码里挂账见 api/export.ts） */}
            <ExportRow
              icon="images-outline"
              name={t('document.export.gallery')}
              hint={t('document.export.galleryHint')}
              disabled={!gallerySupported}
              busy={false}
              testID="docPreview.export.gallery"
              onPress={() => undefined}
            />
          </View>
        </Modal>

        {/* ── 盖章面板（D2 流程的前端部分：选签名 + 确认位置）──────────── */}
        <Modal
          visible={sealOpen}
          transparent
          animationType="slide"
          onRequestClose={() => {
            setSealOpen(false);
          }}
        >
          <Pressable
            style={styles.mask}
            onPress={() => {
              setSealOpen(false);
            }}
          />
          <View testID="docPreview.sign.sheet" style={styles.sheet}>
            <View style={styles.sheetHead}>
              <LocalizedText numberOfLines={1} style={styles.sheetTitle}>
                {t('document.preview.signSheetTitle')}
              </LocalizedText>
              <Pressable
                onPress={() => {
                  setSealOpen(false);
                }}
                accessibilityRole="button"
                accessibilityLabel={t('common.action.close')}
                style={styles.sheetClose}
              >
                <LocalizedText numberOfLines={1} style={styles.sheetCloseText}>
                  {t('common.action.close')}
                </LocalizedText>
              </Pressable>
            </View>

            <LocalizedText numberOfLines={2} style={styles.sheetDesc}>
              {place === null
                ? t('document.preview.signNoPosition')
                : t('document.preview.signSheetDesc', { pageNo: place.pageNo })}
            </LocalizedText>

            {signaturesLoading ? (
              <View style={styles.signLoading}>
                <ActivityIndicator size="small" color={color.brand.primary} />
                <LocalizedText numberOfLines={1} style={styles.signLoadingText}>
                  {t('common.state.loading')}
                </LocalizedText>
              </View>
            ) : null}

            {signatureError === null ? null : (
              <View style={styles.signError}>
                <LocalizedText numberOfLines={3} style={styles.signErrorText}>
                  {errorText(signatureError)}
                </LocalizedText>
                <PrimaryButton
                  label={t('common.state.retry')}
                  variant="outline"
                  onPress={() => {
                    void loadSignatures();
                  }}
                  testID="docPreview.sign.retry"
                />
              </View>
            )}

            {!signaturesLoading && signatureError === null && signatures.length === 0 ? (
              <View style={styles.signEmpty}>
                <LocalizedText numberOfLines={2} style={styles.signEmptyText}>
                  {t('document.preview.signNoSignature')}
                </LocalizedText>
                <PrimaryButton
                  label={t('document.preview.signManage')}
                  variant="outline"
                  onPress={() => {
                    setSealOpen(false);
                    router.push(SEALS_ROUTE as Href);
                  }}
                  testID="docPreview.sign.manage"
                />
              </View>
            ) : null}

            {signatures.length === 0 ? null : (
              <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.signList}>
                {signatures.map((item, index) => {
                  const selected = item.id === pickedSignatureId;
                  return (
                    <Pressable
                      key={item.id}
                      testID="docPreview.sign.signature"
                      accessibilityRole="button"
                      accessibilityState={{ selected }}
                      onPress={() => {
                        setPickedSignatureId(item.id);
                      }}
                      style={[styles.signCard, selected ? styles.signCardSelected : null]}
                    >
                      <Image
                        source={{ uri: item.imageUrl }}
                        style={styles.signImage}
                        contentFit="contain"
                        transition={0}
                        accessibilityLabel={t('document.seal.itemTitle', { index: index + 1 })}
                      />
                      <LocalizedText numberOfLines={1} style={styles.signMeta}>
                        {formatServerDateTime(item.createdAt)}
                      </LocalizedText>
                    </Pressable>
                  );
                })}
              </ScrollView>
            )}

            <LocalizedText numberOfLines={3} style={styles.signWarn}>
              {t('document.preview.signLockedHint')}
            </LocalizedText>

            <PrimaryButton
              label={signing ? t('document.preview.signing') : t('document.preview.signConfirm')}
              onPress={() => {
                void confirmSign();
              }}
              disabled={signing || pickedSignatureId === null || place === null}
              testID="docPreview.sign.confirm"
            />
          </View>
        </Modal>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
  },
  stateCard: {
    marginTop: spacing.xxl,
    paddingVertical: spacing.xxl,
    paddingHorizontal: spacing.xl,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    gap: spacing.md,
  },
  stateTitle: {
    ...typography.h2,
    color: color.text.primary,
    textAlign: 'center',
  },
  stateDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'center',
  },
  stateMeta: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
  },
  ready: {
    gap: spacing.md,
  },
  docHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  docTitle: {
    ...typography.h3,
    color: color.text.primary,
    flex: 1,
  },
  pages: {
    gap: spacing.lg,
  },
  paper: {
    position: 'relative',
    width: '100%',
    borderRadius: radius.xs,
    backgroundColor: color.background.surface,
    overflow: 'hidden',
    // 预览页是图像区域：坐标恒定 LTR（选落款位置靠像素比例，不能跟 App 的 RTL 镜像）
    direction: 'ltr',
    ...shadow.card,
  },
  pageImage: {
    width: '100%',
    height: '100%',
  },
  marker: {
    position: 'absolute',
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
    borderRadius: radius.xs,
  },
  pageBadge: {
    position: 'absolute',
    top: spacing.sm,
    end: spacing.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    backgroundColor: color.overlay.scrim,
  },
  pageBadgeText: {
    ...typography.micro,
    color: color.text.onBrand,
  },
  readonlyTip: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    paddingHorizontal: spacing.lg,
  },
  actionRow: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  actionButton: {
    flex: 1,
  },
  placingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  placingText: {
    ...typography.bodySmall,
    color: color.text.secondary,
    flex: 1,
  },
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
  },
  sheet: {
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.md,
    borderTopEndRadius: radius.md,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxl,
    gap: spacing.md,
  },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sheetTitle: {
    ...typography.h2,
    color: color.text.primary,
  },
  sheetClose: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
  },
  sheetCloseText: {
    ...typography.caption,
    color: color.text.muted,
  },
  sheetDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  exportItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    minHeight: size.buttonHeight + spacing.md,
    padding: spacing.md,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: color.border.outline,
    backgroundColor: color.background.surface,
  },
  exportItemPressed: {
    backgroundColor: color.background.canvas,
  },
  exportItemDisabled: {
    opacity: 0.5,
  },
  exportIcon: {
    width: 38,
    height: 38,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  exportBody: {
    flex: 1,
    gap: spacing.xxs,
  },
  exportName: {
    ...typography.body,
    color: color.text.primary,
  },
  exportHint: {
    ...typography.caption,
    color: color.text.muted,
  },
  signLoading: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
  },
  signLoadingText: {
    ...typography.caption,
    color: color.text.muted,
  },
  signError: {
    gap: spacing.sm,
    paddingVertical: spacing.sm,
  },
  signErrorText: {
    ...typography.caption,
    color: color.state.danger,
  },
  signEmpty: {
    gap: spacing.sm,
    paddingVertical: spacing.sm,
  },
  signEmptyText: {
    ...typography.caption,
    color: color.text.muted,
  },
  signList: {
    gap: spacing.md,
    paddingVertical: spacing.xxs,
  },
  signCard: {
    width: 92,
    padding: spacing.sm,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: color.border.outline,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    gap: spacing.xxs,
  },
  signCardSelected: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  signImage: {
    width: '100%',
    height: 44,
  },
  signMeta: {
    ...typography.micro,
    color: color.text.muted,
  },
  signWarn: {
    ...typography.caption,
    color: color.state.warning,
  },
});
