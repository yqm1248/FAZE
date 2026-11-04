/**
 * 隐私政策页。
 *
 * 职责：G1 的隐私政策页——正文从 `sys_config.privacy_policy_content` 读，页面只渲染。
 * 调用方：路由壳 `app/privacy.tsx`（注册页协议链接、实名认证页同意链接）。
 * 数据来源：`GET /api/v1/pub/config/privacy_policy_content`（见 `features/auth/api/legalDoc.ts`）。
 * 注意：
 *   **不许写死正文**（G1 裁决 #12）。后端该接口当前缺失，缺口见交付报告。
 */

import { AppHeader } from '@/shared/components/AppHeader';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';

import { LegalDocView } from '../components/LegalDocView';

/**
 * 隐私政策页。
 *
 * @returns 隐私政策正文页
 */
export function PrivacyPage() {
  const { t } = useI18n();
  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('auth.privacy.title')} />
      <LegalDocView
        kind="privacy"
        title={t('auth.privacy.title')}
        updatedAtLabel={(date) => t('auth.privacy.updatedAt', { date })}
        notPublishedLabel={t('auth.privacy.notPublished')}
      />
    </Screen>
  );
}
