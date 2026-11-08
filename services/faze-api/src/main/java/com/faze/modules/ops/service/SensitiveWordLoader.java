/*
 * 职责：从 DB 全量加载启用中的敏感词，灌进 SensitiveWordEngine。
 * 调用方：Spring 启动（@PostConstruct）、SensitiveWordReloadListener（Pub/Sub 触发）、
 *         管理端改词后的本地即时重载。
 * 数据来源：faze.sensitive_word（`enabled=1`，按 id 升序）。
 * 注意：
 *   1. **启动时加载失败会让服务起不来**——这是有意的：引擎空树 = 所有内容都不拦，
 *      一个「起来但不起作用」的审核系统比起不来更危险（fail fast）。
 *   2. **reload 方法加 synchronized**：多实例场景每个实例各自重载，但单实例内可能同时
 *      收到 Pub/Sub 与管理端本地触发，串行化避免两次整树构建互相覆盖版本号。
 *   3. 重载是「查全量 → 构建新树 → 原子替换」，**不做增量**：词库规模小（万级以内），
 *      增量带来的状态一致性成本不划算。
 *   4. 本类在 modules/ops（业务域），引擎在 infra——方向是 modules → infra，符合分层。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.infra.sensitive.SensitiveWordEngine;
import com.faze.infra.sensitive.WordEntry;
import com.faze.modules.ops.entity.SensitiveWord;
import com.faze.modules.ops.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 敏感词库加载器。
 */
@Slf4j
@Service
public class SensitiveWordLoader {

    /** 词库数据访问。 */
    private final SensitiveWordMapper sensitiveWordMapper;

    /** Trie 引擎。 */
    private final SensitiveWordEngine engine;

    /**
     * 构造加载器。
     *
     * @param sensitiveWordMapper 词库 Mapper
     * @param engine              Trie 引擎
     */
    public SensitiveWordLoader(SensitiveWordMapper sensitiveWordMapper, SensitiveWordEngine engine) {
        this.sensitiveWordMapper = sensitiveWordMapper;
        this.engine = engine;
    }

    /**
     * 启动时加载一次。
     *
     * <p>异常不吞：加载失败说明词表或 DB 有问题，让启动失败比「静默不拦截」安全。</p>
     */
    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 从 DB 全量重载词库到引擎。
     *
     * <p>副作用：替换引擎内的整棵树并让版本号 +1；会写一条 INFO 日志
     * （`sensitive word loaded: version=... count=... cost=...ms`）。</p>
     */
    public synchronized void reload() {
        List<SensitiveWord> rows = sensitiveWordMapper.selectList(
                Wrappers.<SensitiveWord>lambdaQuery()
                        .eq(SensitiveWord::getEnabled, 1)
                        .orderByAsc(SensitiveWord::getId));
        List<WordEntry> entries = new ArrayList<>(rows.size());
        for (SensitiveWord row : rows) {
            entries.add(new WordEntry(row.getId(), row.getWord(), row.getCategory(),
                    row.getLevel(), row.getLang()));
        }
        engine.reload(entries);
    }

    /**
     * 当前引擎里的词条数（给健康检查/日志用）。
     *
     * @return 词条数
     */
    public int loadedCount() {
        return engine.wordCount();
    }
}
