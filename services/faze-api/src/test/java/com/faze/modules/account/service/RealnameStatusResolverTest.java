/*
 * 职责：`RealnameStatusResolver` 的单元测试（DRIFT-015）——派生口径的总值性与四态映射。
 * 调用方：mvn test。
 * 数据来源：`RealnameAuthMapper` 被 Mockito 替换；不碰数据库、不启动 Spring 容器。
 * 注意：
 *   1. **重点是「永不返回第五种值」**：派生器的返回值会被 App 端与 PC 端直接当枚举用，
 *      一旦漏出 `null` 或脏值，下游 `switch` 会静默走进 default 分支——
 *      表现就是「实名页白屏」或「我的页显示未实名」，而且日志里什么都没有。
 *      所以「无记录」「status=null」「status=脏值」三种情况都必须落成 `none`。
 *   2. **返回值一律小写**：大写是 Converter 的展示口径（NONE/PENDING/…），
 *      本类不承担展示转换。两侧混用会让 `"APPROVED".equals(status)` 这类判断失效。
 *   3. 批量方法要保证「每个入参都有值」——调用方（后台列表）直接 `map.get(id)`，
 *      漏填就会显示成 null 而不是 none。
 */
package com.faze.modules.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.faze.modules.account.entity.RealnameAuth;
import com.faze.modules.account.mapper.RealnameAuthMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 实名状态派生器单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealnameStatusResolverTest {

    private static final Long USER_ID = 9416L;

    @Mock
    private RealnameAuthMapper realnameAuthMapper;

    @InjectMocks
    private RealnameStatusResolver resolver;

    /**
     * 构造一条实名记录。
     *
     * @param status 库内状态（可为 null）
     * @return 实体
     */
    private RealnameAuth auth(String status) {
        RealnameAuth row = new RealnameAuth();
        row.setUserId(USER_ID);
        row.setStatus(status);
        return row;
    }

    @Test
    @DisplayName("userId 为 null：不查库，直接 none")
    void nullUserReturnsNone() {
        assertThat(resolver.resolve(null)).isEqualTo(RealnameStatusResolver.STATUS_NONE);
        verifyNoInteractions(realnameAuthMapper);
    }

    @Test
    @DisplayName("从未提交（查不到记录）：none")
    void noRecordReturnsNone() {
        when(realnameAuthMapper.selectOne(any())).thenReturn(null);
        assertThat(resolver.resolve(USER_ID)).isEqualTo(RealnameStatusResolver.STATUS_NONE);
    }

    @Test
    @DisplayName("三态原样返回（小写）")
    void mapsThreeStates() {
        when(realnameAuthMapper.selectOne(any())).thenReturn(auth("pending"));
        assertThat(resolver.resolve(USER_ID)).isEqualTo(RealnameStatusResolver.STATUS_PENDING);

        when(realnameAuthMapper.selectOne(any())).thenReturn(auth("approved"));
        assertThat(resolver.resolve(USER_ID)).isEqualTo(RealnameStatusResolver.STATUS_APPROVED);

        when(realnameAuthMapper.selectOne(any())).thenReturn(auth("rejected"));
        assertThat(resolver.resolve(USER_ID)).isEqualTo(RealnameStatusResolver.STATUS_REJECTED);
    }

    @Test
    @DisplayName("status 为 null 或脏值：归 none，不返回原值")
    void dirtyStatusFallsBackToNone() {
        when(realnameAuthMapper.selectOne(any())).thenReturn(auth(null));
        assertThat(resolver.resolve(USER_ID)).isEqualTo(RealnameStatusResolver.STATUS_NONE);

        when(realnameAuthMapper.selectOne(any())).thenReturn(auth("PASSED"));
        assertThat(resolver.resolve(USER_ID)).isEqualTo(RealnameStatusResolver.STATUS_NONE);
    }

    @Test
    @DisplayName("库内大写也能识别（大小写不敏感），但输出仍是小写")
    void upperCaseInputStillLowercaseOutput() {
        assertThat(resolver.resolveFromAuth(auth("APPROVED")))
                .isEqualTo(RealnameStatusResolver.STATUS_APPROVED);
    }

    @Test
    @DisplayName("批量：每个 userId 都有值，无记录的填 none")
    void batchFillsEveryId() {
        RealnameAuth approved = new RealnameAuth();
        approved.setUserId(1L);
        approved.setStatus("approved");
        when(realnameAuthMapper.selectList(any())).thenReturn(List.of(approved));

        Map<Long, String> result = resolver.resolveBatch(List.of(1L, 2L, 3L));

        assertThat(result).hasSize(3);
        assertThat(result.get(1L)).isEqualTo(RealnameStatusResolver.STATUS_APPROVED);
        assertThat(result.get(2L)).isEqualTo(RealnameStatusResolver.STATUS_NONE);
        assertThat(result.get(3L)).isEqualTo(RealnameStatusResolver.STATUS_NONE);
    }

    @Test
    @DisplayName("批量：空集合不查库")
    void batchEmptyReturnsEmptyMap() {
        assertThat(resolver.resolveBatch(List.of())).isEmpty();
        verifyNoInteractions(realnameAuthMapper);
    }
}
