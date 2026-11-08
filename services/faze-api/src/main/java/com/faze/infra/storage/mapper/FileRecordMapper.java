/*
 * 职责：file_record 的数据访问（段 4 A3/D1/E4/F1）。
 * 调用方：infra/storage/FileRecordService。
 * 数据来源：faze.file_record。
 * 注意：
 *   1. 本表**禁止 `delete` 物理删除**：文件登记是审计链（`status` 是状态不是删除）。
 *   2. **XML 禁 `${}`** 同样适用于注解 SQL：这里全部是 `#{}` 绑定参数，没有字符串拼接。
 *      `LIKE` 用 `CONCAT(#{prefix}, '%')` 而不是拼进 SQL 文本。
 *   3. 包名必须落在 `**.mapper` 下——`@MapperScan("com.faze.**.mapper")` 才扫得到。
 *   4. 三个查询各有明确用途：
 *      - `selectLatestKey`：列表缩略图取最新一张 `generated.png`；
 *      - `selectKeysByOriginalNamePrefix`：预览图按「同一份 PDF」的缓存前缀批量取；
 *      - `markOrphan`：签名被用户删除后，把图片标成孤儿（文件留待段 7 清理）。
 */
package com.faze.infra.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.infra.storage.entity.FileRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文件登记 Mapper。
 */
public interface FileRecordMapper extends BaseMapper<FileRecord> {

    /**
     * 取某个业务对象最新一条登记的对象键。
     *
     * @param bizType     业务类型
     * @param bizId       业务主键
     * @param originalName 原始文件名（本段用固定名区分产物种类，如 `generated.png`）
     * @return 对象键；没有时返回 null
     */
    @Select("SELECT oss_key FROM file_record WHERE biz_type = #{bizType} AND biz_id = #{bizId}"
            + " AND original_name = #{originalName} AND status = 'active'"
            + " ORDER BY id DESC LIMIT 1")
    String selectLatestKey(@Param("bizType") String bizType,
                           @Param("bizId") Long bizId,
                           @Param("originalName") String originalName);

    /**
     * 按原始文件名的前缀取登记行（按写入顺序升序）。
     *
     * <p>预览图缓存的键形如 `pv{短哈希}:{页号}.png`：短哈希来自 PDF 对象键，
     * 所以重新生成后 PDF 变了，前缀也变了，旧缓存自然查不到（无需额外失效逻辑）。
     * 返回整行而不是只返回 keys：调用方要用 `original_name` 解析页号。</p>
     *
     * @param bizType 业务类型
     * @param bizId   业务主键
     * @param prefix  原始文件名前缀（本方法用 CONCAT 补 `%`）
     * @return 登记行列表（可能为空）
     */
    @Select("SELECT * FROM file_record WHERE biz_type = #{bizType} AND biz_id = #{bizId}"
            + " AND original_name LIKE CONCAT(#{prefix}, '%') AND status = 'active'"
            + " ORDER BY id ASC")
    List<FileRecord> selectByOriginalNamePrefix(@Param("bizType") String bizType,
                                               @Param("bizId") Long bizId,
                                               @Param("prefix") String prefix);

    /**
     * 取某业务对象最新一条登记的主键（段 5 F2 上传出款凭证后回写 `voucher_file_id` 用）。
     *
     * <p>为什么按「业务对象」而不是按「原始文件名」：凭证每次上传的文件名都不同
     * （财务随手命名的截图），只能按 `biz_type + biz_id` 取最新一条。</p>
     *
     * @param bizType 业务类型
     * @param bizId   业务主键
     * @return 登记行主键；没有时返回 null
     */
    @Select("SELECT id FROM file_record WHERE biz_type = #{bizType} AND biz_id = #{bizId}"
            + " AND status = 'active' ORDER BY id DESC LIMIT 1")
    Long selectLatestId(@Param("bizType") String bizType, @Param("bizId") Long bizId);

    /**
     * 把文件标成孤儿（`status='orphan'`），供段 7 的清理任务回收。
     *
     * @param ossKey 对象键
     * @return 受影响行数
     */
    @Update("UPDATE file_record SET status = 'orphan' WHERE oss_key = #{ossKey} AND status = 'active'")
    int markOrphan(@Param("ossKey") String ossKey);
}
