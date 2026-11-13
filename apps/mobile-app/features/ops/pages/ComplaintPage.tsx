/**
 * 投诉页（APP 57）。
 *
 * 职责：投诉对象三选 + 关联已完成订单 + 经过诉求（≤500 字）+ 截图（≤3 张）→ 提交。
 * 调用方：路由壳 `app/complaint.tsx`；入口在「我的与服务」分组与 55 客服中心页尾。
 * 数据来源：`POST /api/v1/app/complaints`（提交）、`GET /api/v1/app/complaints`（回看）、
 *           `GET /api/v1/app/orders?status=completed`（选订单）、
 *           `GET /api/v1/app/account/profile`（脱敏手机号）、
 *           `POST /api/v1/app/upload/token` + PUT（截图）。
 * 注意：
 *   1. **律师问题必须关联已完成订单**（稿面明写，后端 `ComplaintService` 也会校验
 *      `existsCompletedOrderOfUser`）。前端的禁用只是为了少一次往返，**不能替代服务端**。
 *   2. `targetType` 三值映射：律师问题 → `lawyer`、服务问题 → `platform`、
 *      订单问题 → `order`。后端还有一条约束：**非 `platform` 时必须带 `targetId`**。
 *      设计稿的「服务问题」指响应超时/服务与描述不符，属平台侧，所以不要求订单。
 *   3. 截图走三段式上传，`scene` 用 `complaint`（本轮新增到后端 `StorageKeyRules` 白名单）。
 *      选图组件 `WebFileButton` **只在 Web 生效**（Expo 依赖里没有 image-picker，
 *      而本段验收方式是浏览器）——原生端点了没反应，属已知边界，已记段 7 §十一。
 *   4. 提交成功**必须显示单号**：用户找客服时报单号最快定位。后端 `ticketNo` 可能为 null
 *      （工单降级），所以成功态要备两套文案。
 *   5. 「处理结果通知到」显示的是**脱敏手机号**（`phoneMasked`），不做可编辑——
 *      换号要走账号与安全（稿面原文），这里只做提示。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { AppCard } from '@/shared/components/AppCard';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useToast } from '@/shared/components/AppToast';
import { WebFileButton } from '@/shared/components/WebFileButton';
import { formatDate, formatDateTime } from '@/shared/i18n/datetime';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { getProfile } from '@/features/profile/api/profile';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { getOrders } from '@/features/trade/api/orders';
import type { OrderItem } from '@/features/trade/api/orders';
import { uploadFile } from '@/features/account/api/upload';
import { color, radius, size, spacing, typography, WEB_FOCUS_RESET } from '@/shared/theme';

import { submitComplaint } from '../api/complaint';
import type { ComplaintSubmitInput, ComplaintTargetType } from '../types';

/** 诉求字数上限（与后端 `@Size(max = 500)` 一致）。 */
const REASON_MAX = 500;

/** 截图上限（与后端 `@Size(max = 3)` 一致）。 */
const IMAGE_MAX = 3;

/** 订单下拉一次取多少条（已完成订单通常不多）。 */
const ORDER_PAGE_SIZE = 20;

/** 三个投诉对象。 */
const TARGETS: { value: ComplaintTargetType; labelKey: MessageKey }[] = [
  { value: 'lawyer', labelKey: 'ops.complaint.targetLawyer' },
  { value: 'platform', labelKey: 'ops.complaint.targetService' },
  { value: 'order', labelKey: 'ops.complaint.targetOrder' },
];

/**
 * 投诉页。
 *
 * 副作用：拉资料与已完成订单；上传截图；提交后写 `complaint` + `service_ticket`。
 *
 * @returns 投诉表单 / 已提交态
 */
export function ComplaintPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const actionBarSpace = useActionBarSpace();

  const [targetType, setTargetType] = useState<ComplaintTargetType>('lawyer');
  const [reason, setReason] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);

  const [phoneMasked, setPhoneMasked] = useState('');
  const [orders, setOrders] = useState<OrderItem[]>([]);
  const [orderPickerOpen, setOrderPickerOpen] = useState(false);
  const [selectedOrder, setSelectedOrder] = useState<OrderItem | null>(null);

  const [submitting, setSubmitting] = useState(false);
  const [submittedTicketNo, setSubmittedTicketNo] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  /** 拉脱敏手机号；失败就空着，不影响提交。 */
  useEffect(() => {
    void (async () => {
      const result = await getProfile();
      if (result.ok) {
        setPhoneMasked(result.data.phoneMasked);
      }
    })();
  }, []);

  /** 拉已完成订单：只在用户展开选择器时拉一次，避免进页面就多一个请求。 */
  const loadOrders = useCallback(async () => {
    const result = await getOrders({ status: 'completed', pageNum: 1, pageSize: ORDER_PAGE_SIZE });
    if (!result.ok) {
      toast.show(t('ops.complaint.orderEmpty'));
      return;
    }
    setOrders(result.data.list);
  }, [t, toast]);

  /**
   * 选择一张截图并上传。
   *
   * @param file 浏览器 File 对象
   */
  const handlePickImage = useCallback(
    async (file: File) => {
      if (images.length >= IMAGE_MAX) {
        return;
      }
      setUploading(true);
      const result = await uploadFile('complaint', {
        fileName: file.name,
        size: file.size,
        contentType: file.type,
        body: file,
      });
      setUploading(false);
      if (!result.ok) {
        toast.show(t('ops.complaint.uploadFailed'));
        return;
      }
      setImages((current) => (current.length >= IMAGE_MAX ? current : [...current, result.data]));
    },
    [images.length, t, toast],
  );

  /** 该对象类型是否需要关联订单（后端：非 platform 时 targetId 必填）。 */
  const needsOrder = targetType !== 'platform';

  /**
   * 校验并提交。
   *
   * 失败时用 toast 给出**具体原因**，不只说「提交失败」。
   */
  const handleSubmit = useCallback(async () => {
    if (needsOrder && selectedOrder === null) {
      toast.show(t('ops.complaint.orderRequired'));
      return;
    }
    if (reason.trim() === '') {
      toast.show(t('ops.complaint.reasonRequired'));
      return;
    }
    const payload: ComplaintSubmitInput = {
      targetType,
      targetId: needsOrder && selectedOrder !== null ? selectedOrder.id : null,
      reason: reason.trim(),
      images,
    };
    setSubmitting(true);
    const result = await submitComplaint(payload);
    setSubmitting(false);
    if (!result.ok) {
      toast.show(t('ops.complaint.submitFailed'));
      return;
    }
    setSubmittedTicketNo(result.data.ticketNo);
    setSubmitted(true);
  }, [images, needsOrder, reason, selectedOrder, t, targetType, toast]);

  // ── 提交成功态：只要单号与返回入口，不再显示表单 ──────────────────────
  if (submitted) {
    return (
      <Screen edges={['top', 'left', 'right', 'bottom']}>
        <AppHeader title={t('ops.complaint.title')} />
        <ScrollView contentContainerStyle={styles.scroll}>
          <View style={styles.successBox}>
            <Ionicons name="checkmark-circle" size={56} color={color.state.success} />
            <LocalizedText numberOfLines={2} style={styles.successTitle}>
              {submittedTicketNo === null
                ? t('ops.complaint.submitOkNoTicket')
                : t('ops.complaint.submitOk', { ticketNo: submittedTicketNo })}
            </LocalizedText>
            <LocalizedText numberOfLines={3} style={styles.successDesc}>
              {t('ops.complaint.tipTitle')}
            </LocalizedText>
          </View>
        </ScrollView>
        <ActionBar>
          {/* 提交成功后直接给「查看我的投诉」：单号、状态与后台回复都在第 69 页 */}
          <PrimaryButton
            label={t('ops.complaintList.goMine')}
            onPress={() => {
              router.push(PAGE_ROUTES.complaintList);
            }}
          />
          <PrimaryButton
            variant="outline"
            label={t('common.action.back')}
            onPress={() => {
              router.back();
            }}
          />
        </ActionBar>
      </Screen>
    );
  }

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('ops.complaint.title')} />
      <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: actionBarSpace }]}>
        {/* 顶部提示 */}
        <View style={styles.tipBlock}>
          <Ionicons name="alert-circle-outline" size={20} color={color.state.warning} />
          <View style={styles.tipBody}>
            <LocalizedText numberOfLines={2} style={styles.tipTitle}>
              {t('ops.complaint.tipTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={6} style={styles.tipDesc}>
              {t('ops.complaint.tipDesc')}
            </LocalizedText>
          </View>
        </View>

        {/* 我的投诉入口（第 69 页）：已提交过的可以随时回看进度与后台回复 */}
        <Pressable
          onPress={() => router.push(PAGE_ROUTES.complaintList)}
          accessibilityRole="button"
          accessibilityLabel={t('ops.complaintList.goMine')}
          style={({ pressed }) => [styles.mineEntry, pressed ? styles.pressed : null]}
        >
          <Ionicons name="receipt-outline" size={18} color={color.brand.primary} />
          <LocalizedText numberOfLines={1} style={styles.mineEntryText}>
            {t('ops.complaintList.goMine')}
          </LocalizedText>
          <Ionicons name="chevron-forward" size={16} color={color.text.muted} />
        </Pressable>

        {/* 投诉对象 */}
        <AppCard>
          <LocalizedText numberOfLines={1} style={styles.blockTitle}>
            {t('ops.complaint.targetTitle')}
          </LocalizedText>
          <View style={styles.pickRow}>
            {TARGETS.map((item) => {
              const active = item.value === targetType;
              return (
                <Pressable
                  key={item.value}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                  accessibilityLabel={t(item.labelKey)}
                  onPress={() => {
                    setTargetType(item.value);
                    setOrderPickerOpen(false);
                  }}
                  style={({ pressed }) => [
                    styles.pick,
                    active ? styles.pickActive : null,
                    pressed ? styles.pressed : null,
                  ]}
                >
                  <LocalizedText numberOfLines={1} style={active ? styles.pickTextActive : styles.pickText}>
                    {t(item.labelKey)}
                  </LocalizedText>
                </Pressable>
              );
            })}
          </View>
          <LocalizedText numberOfLines={3} style={styles.fieldTip}>
            {t('ops.complaint.targetTip')}
          </LocalizedText>

          {/* 订单选择：仅律师问题 / 订单问题需要 */}
          {needsOrder ? (
            <View style={styles.orderWrap}>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={
                  selectedOrder === null ? t('ops.complaint.orderPickEmpty') : selectedOrder.orderNo
                }
                onPress={() => {
                  setOrderPickerOpen((open) => !open);
                  if (!orderPickerOpen && orders.length === 0) {
                    void loadOrders();
                  }
                }}
                style={({ pressed }) => [styles.orderPick, pressed ? styles.pressed : null]}
              >
                <View style={styles.orderBody}>
                  {selectedOrder === null ? (
                    <LocalizedText numberOfLines={1} style={styles.orderPlaceholder}>
                      {t('ops.complaint.orderPickEmpty')}
                    </LocalizedText>
                  ) : (
                    <>
                      <LocalizedText numberOfLines={1} style={styles.orderName}>
                        {selectedOrder.lawyerName ?? selectedOrder.orderNo}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.orderMeta}>
                        {`${selectedOrder.orderNo} · ${selectedOrder.createdAt === '' ? '' : formatDate(new Date(`${selectedOrder.createdAt}Z`))}`}
                      </LocalizedText>
                    </>
                  )}
                </View>
                <LocalizedText numberOfLines={1} style={styles.orderChange}>
                  {t('ops.complaint.orderPickChange')}
                </LocalizedText>
              </Pressable>

              {/* 展开的订单列表 */}
              {orderPickerOpen ? (
                <View style={styles.orderList}>
                  {orders.length === 0 ? (
                    <LocalizedText numberOfLines={2} style={styles.orderEmpty}>
                      {t('ops.complaint.orderEmpty')}
                    </LocalizedText>
                  ) : (
                    orders.map((order, index) => (
                      <Pressable
                        key={order.id}
                        accessibilityRole="button"
                        accessibilityLabel={order.orderNo}
                        onPress={() => {
                          setSelectedOrder(order);
                          setOrderPickerOpen(false);
                        }}
                        style={({ pressed }) => [
                          styles.orderRow,
                          index === orders.length - 1 ? styles.orderRowLast : null,
                          pressed ? styles.pressed : null,
                        ]}
                      >
                        <LocalizedText numberOfLines={1} style={styles.orderRowName}>
                          {order.lawyerName ?? order.orderNo}
                        </LocalizedText>
                        <LocalizedText numberOfLines={1} style={styles.orderRowMeta}>
                          {`${order.orderNo} · ${formatDateTime(new Date(`${order.createdAt}Z`))}`}
                        </LocalizedText>
                      </Pressable>
                    ))
                  )}
                </View>
              ) : null}

              <LocalizedText numberOfLines={4} style={styles.fieldTip}>
                {t('ops.complaint.orderPickTip')}
              </LocalizedText>
            </View>
          ) : null}
        </AppCard>

        {/* 经过和诉求 */}
        <AppCard>
          <View style={styles.blockTitleRow}>
            <LocalizedText numberOfLines={1} style={styles.blockTitle}>
              {t('ops.complaint.reasonTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.blockTitleNote}>
              {t('ops.complaint.reasonNote')}
            </LocalizedText>
          </View>
          <View style={styles.textareaWrap}>
            <TextInput
              value={reason}
              onChangeText={setReason}
              placeholder={t('ops.complaint.reasonPlaceholder')}
              placeholderTextColor={color.text.muted}
              multiline
              maxLength={REASON_MAX}
              textAlignVertical="top"
              style={styles.textarea}
              accessibilityLabel={t('ops.complaint.reasonTitle')}
            />
            <LocalizedText numberOfLines={1} style={styles.count}>
              {t('ops.complaint.reasonCount', { count: String(reason.length), max: String(REASON_MAX) })}
            </LocalizedText>
          </View>

          {/* 截图上传 */}
          <View style={styles.uploadRow}>
            <WebFileButton
              disabled={uploading || images.length >= IMAGE_MAX}
              onPick={(file) => {
                void handlePickImage(file);
              }}
            >
              <View style={styles.uploadTile}>
                <Ionicons
                  name={images.length >= IMAGE_MAX ? 'checkmark-circle-outline' : 'image-outline'}
                  size={22}
                  color={images.length > 0 ? color.state.success : color.text.muted}
                />
                <LocalizedText numberOfLines={2} style={styles.uploadText}>
                  {images.length === 0
                    ? t('ops.complaint.uploadIdle')
                    : t('ops.complaint.uploadDone', { count: String(images.length) })}
                </LocalizedText>
              </View>
            </WebFileButton>
          </View>
          <LocalizedText numberOfLines={3} style={styles.fieldTip}>
            {t('ops.complaint.uploadTip')}
          </LocalizedText>
        </AppCard>

        {/* 处理结果通知到 */}
        <AppCard>
          <View style={styles.contactRow}>
            <LocalizedText numberOfLines={1} style={styles.contactKey}>
              {t('ops.complaint.noticeTo')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.contactVal}>
              {phoneMasked}
            </LocalizedText>
          </View>
          <LocalizedText numberOfLines={3} style={styles.fieldTip}>
            {t('ops.complaint.noticeTip')}
          </LocalizedText>
        </AppCard>
      </ScrollView>

      <ActionBar>
        <PrimaryButton
          label={t('ops.complaint.submit')}
          onPress={() => {
            void handleSubmit();
          }}
          disabled={submitting || uploading}
        />
      </ActionBar>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  tipBlock: {
    flexDirection: 'row',
    gap: spacing.sm,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.brand.primarySoft,
  },
  mineEntry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: 44,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
  },
  mineEntryText: {
    ...typography.body,
    flex: 1,
    color: color.brand.primary,
  },
  tipBody: {
    flex: 1,
    gap: spacing.xxs,
  },
  tipTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  tipDesc: {
    ...typography.caption,
    color: color.text.secondary,
  },
  blockTitleRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: spacing.sm,
    flexWrap: 'wrap',
  },
  blockTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  blockTitleNote: {
    ...typography.micro,
    color: color.text.muted,
  },
  pickRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  pick: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  pickActive: {
    backgroundColor: color.brand.primarySoft,
    borderWidth: 1,
    borderColor: color.brand.primary,
  },
  pickText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  pickTextActive: {
    ...typography.caption,
    color: color.brand.primaryPressed,
  },
  fieldTip: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.sm,
  },
  orderWrap: {
    marginTop: spacing.md,
  },
  orderPick: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  orderBody: {
    flex: 1,
    gap: spacing.xxs,
  },
  orderPlaceholder: {
    ...typography.body,
    color: color.text.muted,
  },
  orderName: {
    ...typography.body,
    color: color.text.primary,
  },
  orderMeta: {
    ...typography.micro,
    color: color.text.muted,
  },
  orderChange: {
    ...typography.caption,
    color: color.brand.primaryPressed,
  },
  orderList: {
    marginTop: spacing.xs,
    borderRadius: radius.sm,
    backgroundColor: color.background.canvas,
    overflow: 'hidden',
  },
  orderRow: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.md,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
    gap: spacing.xxs,
  },
  orderRowLast: {
    borderBottomWidth: 0,
  },
  orderRowName: {
    ...typography.body,
    color: color.text.primary,
  },
  orderRowMeta: {
    ...typography.micro,
    color: color.text.muted,
  },
  orderEmpty: {
    ...typography.caption,
    color: color.text.muted,
    padding: spacing.md,
  },
  textareaWrap: {
    marginTop: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    padding: spacing.md,
  },
  textarea: {
    ...typography.body,
    color: color.text.primary,
    minHeight: 120,
    padding: 0,
    // 去掉浏览器默认焦点环（黑框画在纯文本区、比外层灰底卡片小一圈）。
    ...WEB_FOCUS_RESET,
  },
  count: {
    ...typography.micro,
    color: color.text.muted,
    // RN 的 textAlign 没有逻辑值（'end' 会类型报错），RTL 对齐靠 flex 的 alignSelf。
    alignSelf: 'flex-end',
  },
  uploadRow: {
    flexDirection: 'row',
    marginTop: spacing.md,
  },
  uploadTile: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
  },
  uploadText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  contactRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  contactKey: {
    ...typography.body,
    color: color.text.secondary,
  },
  contactVal: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  successBox: {
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.huge,
  },
  successTitle: {
    ...typography.h2,
    color: color.text.primary,
    textAlign: 'center',
  },
  successDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'center',
  },
  pressed: {
    opacity: 0.7,
  },
});
