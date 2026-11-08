/*
 * 职责：`modules/ops/service/ExportService` 的单元测试——同步/异步分界、异步任务执行、下载过期。
 * 调用方：`mvn test` 自动执行（F4 验收：>5000 行转异步 + taskId；任务完成后文件可下载）。
 * 数据来源：Mockito 模拟 TaskQueueService；真实 JsonMapper + 临时导出目录。
 * 注意：
 *   1. **不连数据库、不起 Spring**：本测试验证的是「分界与文件链路」，数据查询由 ExportSource 的
 *      两个测试实现代替。真实的 6000 行 curl 实测需要往日志表灌数据，不便反复执行，
 *      所以边界行为用本测试固化（阈值可配，见构造参数）。
 *   2. 用 @TempDir 隔离导出目录，测试结束自动清理，不污染 `E:\faze-export\`。
 *   3. 异步任务执行用真实的 `runTask`：生成 CSV + meta、删除 pending、可被 download 读到。
 */
package com.faze.modules.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * 导出服务测试。
 */
class ExportServiceTest {

    /** 测试用的同步阈值（真实值 5000；调小便于构造边界）。 */
    private static final int SYNC_MAX_ROWS = 5;

    /** 临时导出目录。 */
    @TempDir
    Path tempDir;

    /**
     * 同步分支：行数 ≤ 阈值时直接返回字节。
     */
    @Test
    @DisplayName("≤ 阈值同步返回 CSV 字节（含 BOM）")
    void syncWhenWithinLimit() {
        ExportService service = newService();
        ExportService.ExportResult result = service.export(new FakeSource(3), Map.of("k", "v"), "TEST");
        assertThat(result.async()).isFalse();
        assertThat(result.taskId()).isNull();
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.fileName()).matches("fake_\\d{14}\\.csv");
        assertThat(result.content()[0]).isEqualTo((byte) 0xEF);
    }

    /**
     * 异步分支：行数 > 阈值时入队并返回 taskId。
     */
    @Test
    @DisplayName("> 阈值转异步：入队 task_queue + 返回 taskId + 落 pending 参数文件")
    void asyncWhenOverLimit() {
        TaskQueueService taskQueue = mock(TaskQueueService.class);
        ExportService service = new ExportService(taskQueue,
                JsonMapper.builder().build(), tempDir.toString(), SYNC_MAX_ROWS, 24);
        ExportService.ExportResult result = service.export(new FakeSource(SYNC_MAX_ROWS + 1),
                Map.of("range", "today"), "TEST");
        assertThat(result.async()).isTrue();
        assertThat(result.taskId()).matches("EXP\\d{14}[A-Z0-9]{6}");
        assertThat(result.content()).isNull();
        verify(taskQueue).enqueue(anyString(), anyString(), any(), any());
        assertThat(Files.exists(tempDir.resolve(result.taskId() + ".pending.json"))).isTrue();
    }

    /**
     * 异步任务执行 + 下载：生成 CSV/meta、删除 pending、可下载；过期后下载失败。
     */
    @Test
    @DisplayName("异步任务执行后可下载；过期后下载被拒")
    void runTaskAndDownload() throws IOException {
        TaskQueueService taskQueue = mock(TaskQueueService.class);
        ExportService service = new ExportService(taskQueue,
                JsonMapper.builder().build(), tempDir.toString(), SYNC_MAX_ROWS, 24);
        ExportService.ExportResult async = service.export(new FakeSource(SYNC_MAX_ROWS + 2),
                Map.of("k", "v"), "TEST");
        String taskId = async.taskId();

        TaskQueue task = new TaskQueue();
        task.setBizNo(taskId);
        FakeSource source = new FakeSource(SYNC_MAX_ROWS + 2);
        service.runTask(task, List.of(source));

        assertThat(Files.exists(tempDir.resolve(taskId + ".pending.json"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(taskId + ".meta.json"))).isTrue();
        ExportService.DownloadFile file = service.download(taskId);
        assertThat(file.fileName()).matches("fake_\\d{14}\\.csv");
        String body = new String(file.content(), 3, file.content().length - 3, StandardCharsets.UTF_8);
        assertThat(body).contains("h1,h2");
        assertThat(body.lines().count()).isEqualTo(SYNC_MAX_ROWS + 3); // 表头 + 数据行

        // 把过期时间改到过去：下载必须被拒（401004 的 Service 层表现是 IOException）
        Path metaPath = tempDir.resolve(taskId + ".meta.json");
        String meta = Files.readString(metaPath, StandardCharsets.UTF_8);
        Map<String, Object> parsed = JsonMapper.builder().build().readValue(meta,
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        parsed.put("expireAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minusHours(1).toString());
        Files.writeString(metaPath, JsonMapper.builder().build().writeValueAsString(parsed),
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.download(taskId)).isInstanceOf(IOException.class);
    }

    /**
     * 非法 taskId 必须被拒（防目录穿越）。
     */
    @Test
    @DisplayName("非法 taskId（含 ../）下载被拒")
    void rejectInvalidTaskId() {
        ExportService service = newService();
        assertThatThrownBy(() -> service.download("../../etc/passwd"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.download("EXP123"))
                .isInstanceOf(IOException.class);
    }

    /**
     * 构造一个最小服务实例。
     *
     * @return 服务
     */
    private ExportService newService() {
        return new ExportService(mock(TaskQueueService.class),
                JsonMapper.builder().build(), tempDir.toString(), SYNC_MAX_ROWS, 24);
    }

    /**
     * 假数据源：按构造参数返回固定行数。
     */
    private static final class FakeSource implements ExportSource {

        /** 行数。 */
        private final int rows;

        /**
         * 构造。
         *
         * @param rows 行数
         */
        private FakeSource(int rows) {
            this.rows = rows;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public List<String> headers() {
            return List.of("h1", "h2");
        }

        @Override
        public long count(Map<String, Object> params) {
            return rows;
        }

        @Override
        public List<List<Object>> rows(Map<String, Object> params) {
            List<List<Object>> data = new ArrayList<>(rows);
            for (int i = 0; i < rows; i++) {
                Map<String, Object> unused = new LinkedHashMap<>();
                unused.put("i", i); // 让每一行有独立闭包，避免 IDE 告警
                data.add(List.of((Object) String.valueOf(i), unused.get("i")));
            }
            return data;
        }
    }
}
