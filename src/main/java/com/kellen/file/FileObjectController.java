package com.kellen.file;

import com.kellen.auth.AiWechatUserEntity;
import com.kellen.auth.WechatAuthService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * AI 小程序文件接口控制器。
 *
 * <p>接口沿用 renting-house 的“后端签名、小程序直传”模式，但不返回公共 OSS URL。
 * 文件展示统一走后端 token 地址，GET 时 302 到短时签名 OSS URL，HEAD 时返回
 * Content-Type 供多模态校验器判断附件类型。</p>
 */
@RestController // 声明 REST Controller，返回 JSON 或跳转响应。
@RequestMapping({"/api/v1/files", "/api/files"}) // 兼容版本化路径和简短路径。
public class FileObjectController {

    private final WechatAuthService authService; // 复用微信 Bearer token 登录态。
    private final OssFileService fileService; // 私有 OSS 文件服务。

    /**
     * 构造器注入文件接口依赖。
     *
     * @param authService 微信认证服务。
     * @param fileService OSS 文件服务。
     */
    public FileObjectController(WechatAuthService authService, OssFileService fileService) {
        this.authService = authService;
        this.fileService = fileService;
    }

    /**
     * 获取小程序直传 OSS 的表单策略。
     *
     * @param authorization Authorization Header。
     * @param fileType      文件业务类型。
     * @param mimeType      MIME 类型。
     * @param fileName      原始文件名。
     * @return 上传策略。
     */
    @GetMapping(value = "/upload-policy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileUploadPolicyResponse> uploadPolicy(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                 @RequestParam(value = "fileType", required = false) String fileType,
                                                                 @RequestParam(value = "mimeType", required = false) String mimeType,
                                                                 @RequestParam(value = "fileName", required = false) String fileName) {
        try {
            AiWechatUserEntity user = authService.currentUser(authorization);
            return ResponseEntity.ok(fileService.createUploadPolicy(user, fileType, mimeType, fileName));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = authError(exception) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(FileUploadPolicyResponse.error(exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(FileUploadPolicyResponse.error(exception.getMessage()));
        }
    }

    /**
     * 小程序直传成功后的完成登记接口。
     *
     * @param authorization Authorization Header。
     * @param request       完成登记请求。
     * @return 文件 ID、展示 token 和展示 URL。
     */
    @PostMapping(value = "/complete", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileObjectResponse> complete(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestBody FileUploadCompleteRequest request) {
        try {
            AiWechatUserEntity user = authService.currentUser(authorization);
            OssFileService.CompletedFile completed = fileService.completeUpload(user, request);
            String displayUrl = displayUrl(completed.entity().getId(), completed.displayToken());
            return ResponseEntity.ok(FileObjectResponse.ok(completed.entity(), completed.displayToken(), displayUrl));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = authError(exception) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(FileObjectResponse.error(exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(FileObjectResponse.error(exception.getMessage()));
        }
    }

    /**
     * 查询文件元数据。
     *
     * @param authorization Authorization Header。
     * @param fileId        文件 ID。
     * @return 文件元数据，不返回展示 token。
     */
    @GetMapping(value = "/{fileId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileObjectResponse> metadata(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @PathVariable Long fileId) {
        try {
            AiWechatUserEntity user = authService.currentUser(authorization);
            AiFileObjectEntity entity = fileService.metadata(user, fileId);
            return ResponseEntity.ok(FileObjectResponse.ok(entity, null, null));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = authError(exception) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(FileObjectResponse.error(exception.getMessage()));
        }
    }

    /**
     * 刷新文件展示 token。
     *
     * @param authorization Authorization Header。
     * @param fileId        文件 ID。
     * @return 新展示 token 和展示 URL。
     */
    @PostMapping(value = "/{fileId}/view-token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileObjectResponse> refreshViewToken(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                               @PathVariable Long fileId) {
        try {
            AiWechatUserEntity user = authService.currentUser(authorization);
            OssFileService.CompletedFile completed = fileService.refreshDisplayToken(user, fileId);
            String displayUrl = displayUrl(completed.entity().getId(), completed.displayToken());
            return ResponseEntity.ok(FileObjectResponse.ok(completed.entity(), completed.displayToken(), displayUrl));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = authError(exception) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(FileObjectResponse.error(exception.getMessage()));
        }
    }

    /**
     * 令牌化文件展示接口。
     *
     * <p>GET 返回 302 到短时 OSS 签名 URL；HEAD 直接返回文件 Content-Type，供
     * {@link MediaInputValidator} 校验多模态附件。</p>
     *
     * @param fileId  文件 ID。
     * @param token   展示 token。
     * @param request HTTP 请求。
     * @return 展示响应。
     */
    @RequestMapping(value = "/{fileId}/view", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Void> view(@PathVariable Long fileId,
                                     @RequestParam("token") String token,
                                     HttpServletRequest request) {
        try {
            AiFileObjectEntity entity = fileService.viewableFile(fileId, token);
            if ("HEAD".equalsIgnoreCase(request.getMethod())) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType(entity.getMimeType()));
                if (entity.getFileSizeBytes() != null && entity.getFileSizeBytes() > 0) {
                    headers.setContentLength(entity.getFileSizeBytes());
                }
                headers.setCacheControl(CacheControl.noStore());
                return new ResponseEntity<>(headers, HttpStatus.OK);
            }
            URI signedUri = fileService.signedViewUri(entity);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(signedUri)
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).build();
        }
    }

    private String displayUrl(Long fileId, String displayToken) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/files/{fileId}/view")
                .queryParam("token", displayToken)
                .buildAndExpand(fileId)
                .toUriString();
    }

    private boolean authError(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        return message.contains("未登录") || message.contains("登录已过期");
    }
}
