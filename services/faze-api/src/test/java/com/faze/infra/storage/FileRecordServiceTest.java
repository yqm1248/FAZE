/*
 * 职责：FileRecordService 的单测（段 4 A3 的自查清单）。
 * 调用方：`mvn test`。
 * 数据来源：Mock 的 FileRecordMapper 与 FileStorage（不碰真实磁盘、不碰库）。
 * 注意：
 *   1. 这里验的是**编排**：key 是否符合存储规则、put 有没有被调用、登记行的字段对不对。
 *      真实落盘与真实 SQL 由端到端验收（4-A1/4-A3）覆盖。
 *   2. key 必须落在 `private/{scene}/` 下且扩展名正确——写错前缀不会报错，
 *      但 `StorageKeyRules.isValid` 会在下载时拒，表现为「文件生成成功却打不开」。
 */
package com.faze.infra.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.entity.FileRecord;
import com.faze.infra.storage.mapper.FileRecordMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 文件登记服务单测。
 */
@ExtendWith(MockitoExtension.class)
class FileRecordServiceTest {

    @Mock
    private FileRecordMapper fileRecordMapper;

    @Mock
    private FileStorage fileStorage;

    @InjectMocks
    private FileRecordService fileRecordService;

    /**
     * 服务端生成文书 PDF：key 前缀正确、put 被调用、登记行字段正确。
     */
    @Test
    void storeGeneratedDocumentPdf() {
        when(fileRecordMapper.insert(any(FileRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, FileRecord.class).setId(7L);
            return 1;
        });
        byte[] pdf = "%PDF-1.4 fake".getBytes();

        String key = fileRecordService.storeGenerated(
                FileRecordService.BIZ_DOCUMENT, 42L, 1L, "pdf", pdf, "application/pdf");

        assertTrue(key.startsWith("private/document/"), "key 前缀不对：" + key);
        assertTrue(key.endsWith(".pdf"), "key 扩展名不对：" + key);
        verify(fileStorage).put(eq(key), any(), eq((long) pdf.length));

        ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordMapper).insert(captor.capture());
        FileRecord row = captor.getValue();
        assertEquals(FileRecordService.BIZ_DOCUMENT, row.getBizType());
        assertEquals(42L, row.getBizId());
        assertEquals(1L, row.getUploaderId());
        assertEquals("application/pdf", row.getContentType());
        assertEquals(Integer.valueOf(1), row.getIsPrivate());
        assertEquals("active", row.getStatus());
        assertEquals(32, row.getMd5().length(), "MD5 应为 32 位十六进制");
    }

    /**
     * 模板预览图传 ownerUser=0（运营侧产物），也要能生成合法 key。
     */
    @Test
    void storeGeneratedTemplatePreviewWithZeroOwner() {
        when(fileRecordMapper.insert(any(FileRecord.class))).thenReturn(1);
        String key = fileRecordService.storeGenerated(
                FileRecordService.BIZ_DOC_TEMPLATE, 3L, 0L, "png", new byte[]{1, 2, 3}, "image/png");
        assertTrue(key.startsWith("private/doc_template/"), "key 前缀不对：" + key);
    }

    /**
     * 非法 bizType → 506001，且不落盘、不登记。
     */
    @Test
    void storeGeneratedRejectsUnknownBizType() {
        BusinessException ex = assertThrows(BusinessException.class, () -> fileRecordService.storeGenerated(
                "unknown", 1L, 1L, "pdf", new byte[]{1}, "application/pdf"));
        assertEquals(ErrorCode.DOCUMENT_GENERATE_FAILED, ex.getErrorCode());
        verify(fileStorage, never()).put(any(), any(), anyInt());
        verify(fileRecordMapper, never()).insert(any(FileRecord.class));
    }

    /**
     * 空内容 → 506001（不能产出一个 0 字节的"成功"文件）。
     */
    @Test
    void storeGeneratedRejectsEmptyContent() {
        BusinessException ex = assertThrows(BusinessException.class, () -> fileRecordService.storeGenerated(
                FileRecordService.BIZ_DOCUMENT, 1L, 1L, "pdf", new byte[0], "application/pdf"));
        assertEquals(ErrorCode.DOCUMENT_GENERATE_FAILED, ex.getErrorCode());
    }

    /**
     * 签名图是客户端直传的：只登记，**不 put**（否则会覆盖客户端刚传上来的对象）。
     */
    @Test
    void recordSignatureImageDoesNotPut() {
        when(fileRecordMapper.insert(any(FileRecord.class))).thenReturn(1);
        fileRecordService.recordSignatureImage(1L, "private/signature/202611/1/abc.png", 2048L, null);
        verify(fileStorage, never()).put(any(), any(), anyInt());
        ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordMapper).insert(captor.capture());
        assertEquals(FileRecordService.BIZ_SIGNATURE, captor.getValue().getBizType());
        assertEquals("private/signature/202611/1/abc.png", captor.getValue().getOssKey());
    }

    /**
     * 下载地址每次现签（不缓存）。
     */
    @Test
    void presignDownloadDelegatesToStorage() {
        when(fileStorage.presignDownload("k")).thenReturn(new FileStorage.PresignedUrl("http://x", 60));
        assertEquals("http://x", fileRecordService.presignDownload("k"));
    }

    /**
     * 三个 bizType 常量与存储场景白名单一致（改一处漏一处会表现为 PUT 403）。
     */
    @Test
    void bizTypesAreAcceptedByStorageKeyRules() {
        for (String biz : List.of(FileRecordService.BIZ_DOCUMENT,
                FileRecordService.BIZ_DOC_TEMPLATE, FileRecordService.BIZ_SIGNATURE)) {
            assertTrue(StorageKeyRules.ALLOWED_SCENES.contains(biz), "StorageKeyRules 没放行：" + biz);
        }
    }
}
