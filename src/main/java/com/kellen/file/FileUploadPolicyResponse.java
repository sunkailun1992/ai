package com.kellen.file;

import java.time.LocalDateTime;
import java.util.Map;
import com.kellen.aliyun.oss.token.OssUploadPolicy;

/**
 * OSS 直传策略响应。
 *
 * @param success        是否成功生成上传策略。
 * @param accessId       OSS AccessKeyId，仅用于本次 PostObject 表单，不包含 Secret。
 * @param policy         Base64 编码后的 PostObject policy。
 * @param signature      PostObject 表单签名。
 * @param host           OSS 上传 host。
 * @param expire         策略过期时间戳，单位秒。
 * @param objectKey      后端生成的 OSS objectKey，小程序必须按该 key 上传。
 * @param fileType       标准化文件类型。
 * @param mimeType       标准化 MIME 类型。
 * @param maxUploadBytes 单文件最大上传字节数。
 * @param securityToken  STS 临时令牌；当前 AccessKey 签名模式为空，字段预留。
 * @param error          错误说明。
 * @param timestamp      响应时间。
 */
public record FileUploadPolicyResponse(
        boolean success,
        String accessId,
        String policy,
        String signature,
        String host,
        String expire,
        String objectKey,
        String fileType,
        String mimeType,
        long maxUploadBytes,
        String securityToken,
        String error,
        LocalDateTime timestamp,
        Map<String, String> formFields,
        String storagePlatform
) {

    /**
     * 构造成功响应。
     */
    public static FileUploadPolicyResponse ok(String accessId,
                                              String policy,
                                              String signature,
                                              String host,
                                              String expire,
                                              String objectKey,
                                              String fileType,
                                              String mimeType,
                                              long maxUploadBytes) {
        return new FileUploadPolicyResponse(true, accessId, policy, signature, host, expire, objectKey,
                fileType, mimeType, maxUploadBytes, null, null, LocalDateTime.now(), Map.of(), null);
    }

    /** 统一表单字段与平台标识只用于本次直传，旧 OSS 字段继续返回。 */
    public static FileUploadPolicyResponse ok(OssUploadPolicy policy, String platform) {
        return new FileUploadPolicyResponse(true, policy.accessId(), policy.policy(), policy.signature(),
                policy.host(), policy.expire(), policy.objectKey(), policy.fileType(), policy.mimeType(),
                policy.maxUploadBytes(), policy.securityToken(), null, LocalDateTime.now(),
                policy.formFields(), platform);
    }

    /**
     * 构造失败响应。
     */
    public static FileUploadPolicyResponse error(String error) {
        return new FileUploadPolicyResponse(false, null, null, null, null, null, null, null,
                null, 0, null, error, LocalDateTime.now(), Map.of(), null);
    }
}
