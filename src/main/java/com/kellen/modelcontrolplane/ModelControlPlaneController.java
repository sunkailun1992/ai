package com.kellen.modelcontrolplane;

import com.kellen.controlplane.AiControlPlaneAdministrator;
import com.kellen.utils.enumeration.ReturnCode;
import com.kellen.utils.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 平台管理员的目录聚合入口；不代理原生管理 CRUD，也不向浏览器提供管理凭据。 */
@RestController
@RequestMapping("/api/ai/model-control-plane")
public class ModelControlPlaneController {
    private final AiControlPlaneAdministrator administrators;
    private final ModelControlPlaneService service;

    public ModelControlPlaneController(AiControlPlaneAdministrator administrators, ModelControlPlaneService service) {
        this.administrators = administrators;
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ModelControlPlaneResponse>> current(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        administrators.require(authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.current()));
    }

    /** 保留身份拒绝状态；不让旧全局 advice 包装为 500 或回显异常正文。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(ResponseStatusException exception) {
        ReturnCode code = exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()
                ? ReturnCode.用户身份校验失败 : ReturnCode.无权限使用;
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(ApiResponse.fail(code));
    }
}
