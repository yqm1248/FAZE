/*
 * 职责：上传接口的出参——上传凭证与下载地址。
 * 调用方：modules/account/controller/UploadController。
 * 数据来源：infra/storage 的实现。
 * 注意：
 *   1. `key` 必须原样回给客户端并作为后续业务字段（如 realname.idCardFront）提交，
 *      客户端**不许自己拼路径**——拼出来的路径服务端会在校验时拒绝。
 *   2. `uploadUrl` 是一次性的短时地址，不要缓存到本地存储里。
 */
package com.faze.modules.account.vo;

import lombok.Data;

/**
 * 上传凭证。
 */
@Data
public class UploadTokenVO {

    /** 对象 key，提交业务时原样回传。 */
    private String key;

    /** 可 PUT 的上传地址。 */
    private String uploadUrl;

    /** 有效期（秒）。 */
    private Long expiresIn;
}
