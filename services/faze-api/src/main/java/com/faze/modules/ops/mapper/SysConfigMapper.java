/*
 * 职责：sys_config 的数据访问（只读为主）。
 * 调用方：modules/ops/service/impl/SysConfigQueryServiceImpl。
 * 数据来源：faze.sys_config。
 * 注意：本 Mapper 目前只用于查询；写入（PC A-08 保存参数）属 F 组，
 *      到那时再单独加方法，不要在这里提前开放全量增删改。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.SysConfig;

/**
 * 平台参数 Mapper。
 */
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
