/*
 * 职责：守住 `POST /app/upload/token` 的 scene 三方一致性——
 *       `UploadBO` 的 `@Pattern` 白名单、`StorageKeyRules.ALLOWED_SCENES`、
 *       APP 侧 `UploadScene`（`features/account/api/upload.ts`）。
 * 调用方：`mvn test`。
 * 数据来源：无（纯反射 + 常量断言）。
 * 注意：这条测试是段 7 一个真实缺陷的回归护栏——
 *   `StorageKeyRules` 早已放行 `complaint` / `feedback`，而 `UploadBO` 的正则漏了这两个，
 *   表现为 APP 57 投诉页 / 58 反馈页选完截图必弹「截图上传失败」，
 *   后端只回 `401001 scene: 场景不在白名单内`。改白名单时三处必须一起改，
 *   所以这里把「客户端能传哪些场景」显式钉成一份清单再逐项断言。
 */
package com.faze.modules.account.bo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faze.infra.storage.StorageKeyRules;
import jakarta.validation.constraints.Pattern;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * 上传场景白名单的一致性测试。
 */
class UploadBOTest {

    /**
     * 客户端（APP）允许直传的场景。
     *
     * <p>与 `apps/mobile-app/features/account/api/upload.ts` 的 `UploadScene` 逐项相同。
     * 新增场景时这里要跟着加，否则本测试不会覆盖到新场景。</p>
     */
    private static final List<String> CLIENT_SCENES = List.of(
            "realname", "identity", "license", "avatar", "chat", "signature", "complaint", "feedback");

    /**
     * **只能**由服务端生成、不让客户端申请上传凭证的场景。
     *
     * <p>它们在 `StorageKeyRules.ALLOWED_SCENES` 里（服务端要能 `build()` 出来），
     * 但**不得**出现在 `UploadBO` 的正则里——否则客户端可以自己传一份公文 PDF。</p>
     */
    private static final Set<String> SERVER_ONLY_SCENES = Set.of(
            "document", "doc_template", "withdraw", "reconcile_bill");

    /**
     * 取 `UploadBO.scene` 上的 `@Pattern` 正则并编译。
     *
     * @return 编译后的正则
     * @throws NoSuchFieldException 字段被改名/删除时失败，提示测试需要同步
     */
    private java.util.regex.Pattern scenePattern() throws NoSuchFieldException, PatternSyntaxException {
        Field field = UploadBO.class.getDeclaredField("scene");
        Pattern annotation = field.getAnnotation(Pattern.class);
        assertNotNull(annotation, "UploadBO.scene 必须保留 @Pattern 白名单（删掉就等于放开任意场景）");
        return java.util.regex.Pattern.compile(annotation.regexp());
    }

    /**
     * 客户端直传的每个场景都必须能通过 Bean Validation 的白名单。
     */
    @Test
    void everyClientScenePassesUploadBOWhitelist() throws Exception {
        java.util.regex.Pattern pattern = scenePattern();
        for (String scene : CLIENT_SCENES) {
            assertTrue(pattern.matcher(scene).matches(),
                    "UploadBO 的 scene 正则没放行客户端直传场景：" + scene + "（正则为 " + pattern.pattern() + "）");
        }
    }

    /**
     * 客户端直传的每个场景也必须被存储层放行，否则会出现「凭证签发成功、PUT 403」。
     */
    @Test
    void everyClientSceneIsAcceptedByStorageKeyRules() {
        for (String scene : CLIENT_SCENES) {
            assertTrue(StorageKeyRules.ALLOWED_SCENES.contains(scene), "StorageKeyRules 没放行：" + scene);
        }
    }

    /**
     * 服务端专用场景不得出现在客户端白名单里（防止客户端自传公文）。
     */
    @Test
    void serverOnlyScenesAreRejectedByUploadBO() throws Exception {
        java.util.regex.Pattern pattern = scenePattern();
        for (String scene : SERVER_ONLY_SCENES) {
            assertTrue(StorageKeyRules.ALLOWED_SCENES.contains(scene),
                    "服务端生成场景也必须在 ALLOWED_SCENES 内：" + scene);
            assertFalse(pattern.matcher(scene).matches(),
                    "服务端专用场景不得出现在 UploadBO 白名单里：" + scene);
        }
    }

    /**
     * 任意伪造场景必须被拒（正则必须是完整匹配，不是「包含」）。
     */
    @Test
    void forgedSceneIsRejected() throws Exception {
        java.util.regex.Pattern pattern = scenePattern();
        assertFalse(pattern.matcher("../../etc").matches(), "路径片段不得通过");
        assertFalse(pattern.matcher("avatar/../../x").matches(), "带斜杠的拼接不得通过");
        assertFalse(pattern.matcher("complaint_extra").matches(), "后缀不得通过（必须整串匹配）");
    }
}
