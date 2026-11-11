package com.kellen.file;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 既有私有文件的 token 展示入口；不提供登录、上传登记或 owner 管理接口。 */
@RestController
@RequestMapping({"/api/v1/files", "/api/files"})
public class FileObjectController {
    private final OssFileService fileService;

    public FileObjectController(OssFileService fileService) {
        this.fileService = fileService;
    }

    /** GET 返回短时签名地址，HEAD 返回真实 MIME；展示 token 始终先校验。 */
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

}
