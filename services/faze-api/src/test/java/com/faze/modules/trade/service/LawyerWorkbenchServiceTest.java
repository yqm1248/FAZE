/*
 * 职责：LawyerWorkbenchService 的单测——服务设置的「部分更新」语义与看板口径（G3.4）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 LawyerOrderService / LawyerMapper / OrdersMapper。
 * 注意：
 *   1. **本类防的是一个真实踩过的缺陷**（2026-11-06 端到端验收发现）：只传图文价格时，
 *      电话与文书的价格被清成 NULL、上架开关被置 0——「没传」被当成「清空」。
 *      断言方式是捕获 Mapper 收到的 wrapper 的 SET 片段：没传的价格列**不应该出现**。
 *   2. 开关是「保持现值」不是「保持 true」：库内本来就是 false 的，修改其它字段不能把它打开。
 *   3. 0 价必须强制下架（否则用户下单直接 405009，是必然客诉）；负数价格拒绝（405006）。
 *   4. 非律师一律 401008（403），不是 404。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.trade.bo.ServiceSettingsBO;
import com.faze.modules.trade.mapper.OrdersMapper;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 律师工作台服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LawyerWorkbenchServiceTest {

    private static final Long USER_ID = 9001L;
    private static final Long LAWYER_ID = 9002L;

    @Mock
    private LawyerOrderService lawyerOrderService;

    @Mock
    private LawyerMapper lawyerMapper;

    @Mock
    private OrdersMapper ordersMapper;

    @InjectMocks
    private LawyerWorkbenchService lawyerWorkbenchService;

    /**
     * 注册 Lawyer 的 TableInfo（lambda 列名解析需要）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Lawyer.class);
    }

    /**
     * 造一个「图文 5000 / 电话 8000 / 文书 20000，三个都上架」的律师。
     *
     * @return 律师实体
     */
    private Lawyer lawyerAllOn() {
        Lawyer lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setConsultPriceCents(5000L);
        lawyer.setPhonePriceCents(8000L);
        lawyer.setDocumentPriceCents(20000L);
        lawyer.setServiceTextOn(Boolean.TRUE);
        lawyer.setServicePhoneOn(Boolean.TRUE);
        lawyer.setServiceDocOn(Boolean.TRUE);
        when(lawyerOrderService.requireLawyer(USER_ID)).thenReturn(lawyer);
        return lawyer;
    }

    /**
     * 捕获写库用的 wrapper。
     *
     * @return 捕获到的 wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Lawyer> captureWrapper() {
        org.mockito.ArgumentCaptor<Wrapper<Lawyer>> captor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(lawyerMapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<Lawyer>) captor.getValue();
    }

    /**
     * 只改图文价格与图文开关时，电话与文书的价格列**不能**出现在 SET 里。
     */
    @Test
    @DisplayName("部分更新：只传图文价格时不动电话与文书价格")
    void partialUpdateKeepsOtherPrices() {
        lawyerAllOn();
        ServiceSettingsBO request = new ServiceSettingsBO();
        request.setConsultPriceCents(6500L);
        request.setServiceTextOn(Boolean.TRUE);

        lawyerWorkbenchService.updateServiceSettings(USER_ID, request);

        String sqlSet = captureWrapper().getSqlSet();
        assertTrue(sqlSet.contains("consult_price_cents"), "应更新图文价格：" + sqlSet);
        assertFalse(sqlSet.contains("phone_price_cents"), "不该动电话价格：" + sqlSet);
        assertFalse(sqlSet.contains("document_price_cents"), "不该动文书价格：" + sqlSet);
    }

    /**
     * 没传开关时保持库内现值（true 仍然是 true）。
     */
    @Test
    @DisplayName("部分更新：没传开关时保持上架状态")
    void partialUpdateKeepsSwitchOn() {
        lawyerAllOn();
        ServiceSettingsBO request = new ServiceSettingsBO();
        request.setConsultPriceCents(6500L);

        lawyerWorkbenchService.updateServiceSettings(USER_ID, request);

        Map<String, Object> params = captureWrapper().getParamNameValuePairs();
        assertTrue(params.containsValue(Boolean.TRUE), "三个开关应保持 true：" + params);
        assertFalse(params.containsValue(Boolean.FALSE), "不该把任何开关关掉：" + params);
    }

    /**
     * 0 价必须强制下架：价格写 0，图文开关写 false。
     */
    @Test
    @DisplayName("0 元价格：强制下架该服务")
    void zeroPriceForcesSwitchOff() {
        lawyerAllOn();
        ServiceSettingsBO request = new ServiceSettingsBO();
        request.setConsultPriceCents(0L);
        request.setServiceTextOn(Boolean.TRUE);

        lawyerWorkbenchService.updateServiceSettings(USER_ID, request);

        Map<String, Object> params = captureWrapper().getParamNameValuePairs();
        assertTrue(params.containsValue(0L), "价格应写 0：" + params);
        assertTrue(params.containsValue(Boolean.FALSE), "图文开关应被强制关闭：" + params);
    }

    /**
     * 负数价格拒绝（405006）。
     */
    @Test
    @DisplayName("负数价格：405006 且不落库")
    void negativePriceRejected() {
        lawyerAllOn();
        ServiceSettingsBO request = new ServiceSettingsBO();
        request.setConsultPriceCents(-1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lawyerWorkbenchService.updateServiceSettings(USER_ID, request));
        assertEquals(ErrorCode.TRADE_AMOUNT_INVALID, ex.getErrorCode());
        verify(lawyerMapper, never()).update(isNull(), any());
    }

    /**
     * 非律师账号：401008（403），不是 404。
     */
    @Test
    @DisplayName("非律师：401008")
    void nonLawyerRejected() {
        when(lawyerOrderService.requireLawyer(USER_ID))
                .thenThrow(new BusinessException(ErrorCode.COMMON_FORBIDDEN, "当前账号不是律师"));
        ServiceSettingsBO request = new ServiceSettingsBO();
        request.setConsultPriceCents(100L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lawyerWorkbenchService.updateServiceSettings(USER_ID, request));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, ex.getErrorCode());
        verify(lawyerMapper, never()).update(isNull(), any());
    }

    /**
     * 改在线状态：只接受 online / busy / offline，非法值 401001。
     */
    @Test
    @DisplayName("在线状态：非法值 401001")
    void invalidOnlineStatus() {
        lawyerAllOn();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> lawyerWorkbenchService.updateOnlineStatus(USER_ID, "sleeping"));
        assertEquals(ErrorCode.COMMON_PARAM_INVALID, ex.getErrorCode());
        verify(lawyerMapper, never()).update(isNull(), any());
    }
}
