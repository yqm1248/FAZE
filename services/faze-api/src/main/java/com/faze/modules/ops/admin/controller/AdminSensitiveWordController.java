/*
 * 职责：A-25 左栏「词库」的全部接口（列表 / 新增 / 修改 / 删除 / 批量导入 / 导出）。
 * 调用方：PC 后台「敏感词管理」页（src/views/biz/ops/SensitiveWord.vue）。
 * 数据来源：AdminSensitiveWordService → faze.sensitive_word。
 * 注意：
 *   1. **写接口全部在 Service 上标 `@OperationLog`**，Controller 不标
 *      （标在 Controller 会在参数校验失败时也记一条「已执行」的假审计）。
 *   2. 导入用 `multipart/form-data` 直传（≤2MB），本段不经 OSS——文件小、链路短。
 *   3. 导出返回 `ResponseEntity<byte[]>`，不是 `R<T>`（文件下载例外）。
 *   4. 路径在 `/api/v1/admin/**` 下，由 SecurityConfig 的 `hasRole('ADMIN')` 保护，
 *      Controller 自己不再写鉴权。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.SensitiveWordQueryBO;
import com.faze.modules.ops.admin.bo.SensitiveWordSaveBO;
import com.faze.modules.ops.admin.service.AdminSensitiveWordService;
import com.faze.modules.ops.admin.vo.ImportResultVO;
import com.faze.modules.ops.admin.vo.SensitiveWordVO;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端敏感词库接口。
 */
@RestController
@RequestMapping("/api/v1/admin/sensitive-words")
@RequiredArgsConstructor
@Validated
public class AdminSensitiveWordController {

    /** 导入文件大小上限：2MB（本段不经 OSS，直传）。 */
    private static final long IMPORT_MAX_BYTES = 2L * 1024 * 1024;

    /** 词库服务。 */
    private final AdminSensitiveWordService sensitiveWordService;

    /**
     * 分页查询词库。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<SensitiveWordVO>> page(SensitiveWordQueryBO query) {
        return R.ok(sensitiveWordService.page(query));
    }

    /**
     * 新增词条。
     *
     * @param bo 入参
     * @return 新词条 id
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody SensitiveWordSaveBO bo) {
        return R.ok(sensitiveWordService.create(bo));
    }

    /**
     * 修改词条（全量覆盖）。
     *
     * @param id 词条 id
     * @param bo 入参
     * @return 空
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody SensitiveWordSaveBO bo) {
        sensitiveWordService.update(id, bo);
        return R.ok(null);
    }

    /**
     * 删除词条（物理删，审计留 before）。
     *
     * @param id 词条 id
     * @return 空
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sensitiveWordService.delete(id);
        return R.ok(null);
    }

    /**
     * 批量导入 CSV（表头 word,category,level,lang）。
     *
     * @param file CSV 文件
     * @return 导入结果（全对才写）
     * @throws IOException 读取失败
     */
    @PostMapping("/import")
    public R<ImportResultVO> importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return R.ok(new ImportResultVO());
        }
        if (file.getSize() > IMPORT_MAX_BYTES) {
            // 超过 2MB 直接按格式错误处理（前端也会拦，这里是服务端兜底）
            throw new com.faze.common.exception.BusinessException(
                    com.faze.common.exception.ErrorCode.OPS_IMPORT_FORMAT, "文件超过 2MB");
        }
        return R.ok(sensitiveWordService.importCsv(file.getBytes()));
    }

    /**
     * 导出词库 CSV。
     *
     * @param query 筛选条件（分页参数忽略）
     * @return CSV 文件流
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(SensitiveWordQueryBO query) {
        byte[] body = sensitiveWordService.export(query);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("sensitive-words.csv", StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
