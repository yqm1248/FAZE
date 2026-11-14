/*
 * 职责：上传凭证的签发与下载地址生成（第 1 段 C 组）。
 * 调用方：modules/account/controller/UploadController（C 端本人看自己的影像）、
 *         modules/account/admin/service/AdminVerificationService 与 AdminLawyerApplicationService
 *         （管理端审核看别人的影像，走 downloadUrlForAdmin，不做归属校验但路径已鉴权 + 审计）。
 * 数据来源：infra/storage/FileStorage（本地目录实现，段 4 换 OSS）。
 * 注意：
 *   1. **key 一律由服务端生成**，客户端只提交 scene + 文件名（只用来取扩展名）。
 *      让客户端传路径是存储层最经典的漏洞来源。
 *   2. 下载地址做**归属校验**：key 里的 userId 必须等于当前用户（**头像场景例外，见第 4 条**）。
 *      没有这一步，任何登录用户改一个 key 就能下载别人的身份证影像。
 *   3. 段 1 只有「本人看自己的影像」这一种场景；审核员看别人的影像走 PC 管理端
 *      （F2），那条路的鉴权与审计在 F 组单独做，不要在这里开口子。
 *   4. **头像 key 允许跨用户签发**（`downloadUrl` 对 `private/avatar/` 前缀跳过属主校验）：
 *      会话列表 / 律师列表详情 / 订单详情都要显示「对方」的头像，属主校验会把对端头像
 *      一律打成 403。key 由业务接口下发且带 UUID（不可枚举），桶仍私有、地址仍现签，
 *      放行的只是「签发」不是「公开读」。
 *      **这只是读侧例外**：写侧（如 `WorkbenchServiceImpl.updateProfile`）仍必须校验归属，
 *      只能改自己的头像；`realname` / `identity` / `license` / `signature` / `chat` /
 *      `case_evidence` / `complaint` / `feedback` 等隐私或证据场景**绝不能照抄这个例外**。
 */
package com.faze.modules.account.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.FileStorage;
import com.faze.infra.storage.StorageKeyRules;
import com.faze.modules.account.bo.UploadBO;
import com.faze.modules.account.vo.UploadTokenVO;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 上传服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private static final DateTimeFormatter YYYY_MM = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 头像对象 key 的前缀。
     *
     * <p>**只允许这一个场景跨用户签发下载地址**（见 {@link #downloadUrl(String, Long)}）。
     * 刻意写成单个常量而不是「场景白名单集合」：集合式写法太容易被后来人顺手扩充，
     * 而这张表里每一项都可能变成越权读取。</p>
     */
    private static final String AVATAR_KEY_PREFIX = "private/avatar/";

    private final FileStorage fileStorage;

    /**
     * 签发上传凭证。
     *
     * <p>前置：scene 与扩展名都在白名单内，size 不超过上限。</p>
     * <p>副作用：无（只生成签名，不落盘）。</p>
     *
     * @param request 上传申请
     * @param userId  当前用户
     * @return 上传凭证
     * @throws BusinessException 401001 场景/扩展名/大小非法
     */
    public UploadTokenVO presign(UploadBO request, Long userId) {
        // 任务 1：生成 key（场景与扩展名在 StorageKeyRules 里白名单校验）。
        String key;
        try {
            key = StorageKeyRules.build(request.getScene(), userId,
                    LocalDate.now(ZoneOffset.UTC).format(YYYY_MM),
                    StorageKeyRules.extensionOf(request.getFileName()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        // 任务 2：签发上传地址。
        FileStorage.PresignedUpload presigned;
        try {
            presigned = fileStorage.presignUpload(key, request.getContentType(), request.getSize());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        UploadTokenVO vo = new UploadTokenVO();
        vo.setKey(key);
        vo.setUploadUrl(presigned.url());
        vo.setExpiresIn(presigned.expiresIn());
        log.info("签发上传凭证 userId={} scene={} key={}", userId, request.getScene(), key);
        return vo;
    }

    /**
     * 生成短时下载地址（**管理端审核专用**，不做归属校验）。
     *
     * <p>前置：调用方已通过 `/api/v1/admin/**` 的 ROLE_ADMIN 鉴权，且会把这次访问
     * 写进 operation_log（见 A-04 / A-05 的详情接口）。</p>
     *
     * <p>与 {@link #downloadUrl(String, Long)} 的差异：本方法对**任何场景**都不校验 key 归属
     * （C 端那条只在 avatar 场景放行）。管理员必须能看别人提交的证件影像，否则审核无法进行；
     * 代价是把「谁能看」完全交给调用路径的鉴权，所以本方法只能被管理端 Service 调用，
     * **不得**暴露给 C 端任何 Controller。</p>
     *
     * @param key 对象 key；null/空白时返回 null（草稿可能还没上传）
     * @return 60 秒签名下载地址；key 不合法（脏数据）时返回 null 并记 warn
     */
    public String downloadUrlForAdmin(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (!StorageKeyRules.isValid(key)) {
            // 不打印 key 内容：影像对象 key 属 L4，日志里连完整 key 都不打（规范 §10.2）
            log.warn("管理端影像 key 格式非法，已忽略该字段");
            return null;
        }
        return fileStorage.presignDownload(key).url();
    }

    /**
     * 生成短时下载地址。
     *
     * <p>前置：key 合法，且满足下列之一：</p>
     * <ul>
     *   <li>`private/avatar/` 场景：**允许跨用户**签发。头像是对外展示信息——会话列表、
     *       律师列表/详情、订单详情本来就要显示「对方」的头像；key 由业务接口下发、
     *       含 `UUID.randomUUID()` 不可枚举；桶仍私有、地址仍每次现签，放行的是「签发」
     *       而不是把文件变成公开 URL。</li>
     *   <li>其余场景：**必须属于当前用户**（key 里的 userId == 当前 userId）。</li>
     * </ul>
     *
     * <p><b>绝不能照抄 avatar 例外的场景</b>：`realname` / `identity` / `license`（证件影像）、
     * `signature`（手写签名）、`chat`（聊天附件）、`case_evidence`（立案证据）、
     * `complaint` / `feedback`（投诉反馈凭证）——这些是隐私或证据类材料，跨用户读取就是越权。
     * 头像能例外，只因为它**本身就是公开给别人看的**，与上述场景性质不同。</p>
     *
     * @param key    对象 key
     * @param userId 当前用户
     * @return 下载地址与有效期
     * @throws BusinessException 401001 key 非法 / 403 不是本人的文件（avatar 场景不抛 403）
     */
    public FileStorage.PresignedUrl downloadUrl(String key, Long userId) {
        // 任务 1：格式校验先行——非法 key 直接 401001，不进入任何归属判断。
        if (!StorageKeyRules.isValid(key)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        // 任务 2：归属校验。**只有 avatar 场景例外**（读侧需求，见方法头注释）；
        // 其余场景一律必须属主本人：realname / identity / license / signature / chat /
        // case_evidence / complaint / feedback 等隐私与证据类材料不得照抄本例外。
        if (!key.startsWith(AVATAR_KEY_PREFIX)) {
            Long owner = StorageKeyRules.ownerOf(key);
            if (owner == null || !owner.equals(userId)) {
                throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
            }
        }
        return fileStorage.presignDownload(key);
    }
}
