/**
 * 用户服务协议页。
 *
 * 职责：G1 的协议页——正文从 `sys_config.user_agreement_content` 读，页面只渲染。
 * 调用方：路由壳 `app/agreement.tsx`（注册页协议链接、入驻状态页提示都指向这里）。
 * 数据来源：`GET /api/v1/pub/config/user_agreement_content`（见 `features/auth/api/legalDoc.ts`）。
 * 注意：
 *   **不许写死正文**（G1 裁决 #12）：真实法务文本上线前补，改文本只改配置、不发版。
 *   后端该接口当前缺失，缺口见交付报告；页面本身无需再改。
 */

import { AppHeader } from '@/shared/components/AppHeader';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';

import { LegalDocView } from '../components/LegalDocView';

/**
 * 用户服务协议页。
 *
 * @returns 协议正文页
 */
export function AgreementPage() {
  const { t } = useI18n();
  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('auth.agreement.title')} />
      <LegalDocView
        kind="agreement"
        title={t('auth.agreement.title')}
        updatedAtLabel={(date) => t('auth.agreement.updatedAt', { date })}
        notPublishedLabel={t('auth.agreement.notPublished')}
      />
    </Screen>
  );
}
