package com.kellen.storageconfig;

import com.kellen.controlplane.AiControlPlaneAdministrator;
import com.kellen.utils.enumeration.ReturnCode;
import com.kellen.utils.response.ApiResponse;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.kellen.storageconfig.StorageContracts.*;

/** 全局存储控制面：读取及写入都使用同一个已验证平台管理门禁。 */
@RestController
@RequestMapping("/api/ai")
public class StorageConfigurationController {
    private final AiControlPlaneAdministrator admins;
    private final StorageConfigurationService service;
    public StorageConfigurationController(AiControlPlaneAdministrator admins,StorageConfigurationService service){this.admins=admins;this.service=service;}
    private String actor(String auth){return admins.require(auth).userId();}
    @GetMapping("/storage-capabilities") public ResponseEntity<ApiResponse<Capabilities>> capabilities(@RequestHeader(value="Authorization",required=false)String auth){actor(auth);return ok(service.capabilities());}
    @GetMapping("/storage-configurations") public ResponseEntity<ApiResponse<Page>> list(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String search,
            @RequestParam(required=false)String provider,@RequestParam(required=false)Boolean uploadEnabled){actor(auth);return ok(service.list(page,size,search,provider,uploadEnabled));}
    @GetMapping("/storage-configurations/{id}") public ResponseEntity<ApiResponse<Configuration>> get(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){actor(auth);return ok(service.configuration(id));}
    @PostMapping("/storage-configurations") public ResponseEntity<ApiResponse<Operation>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Create input){return response(201,service.create(actor(auth),input));}
    @PutMapping("/storage-configurations/{id}") public ResponseEntity<ApiResponse<Operation>> update(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody Update input){return ok(service.update(actor(auth),id,input));}
    @DeleteMapping("/storage-configurations/{id}") public ResponseEntity<ApiResponse<Operation>> delete(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestParam String requestId,@RequestParam Long expectedVersion){return ok(service.delete(actor(auth),id,requestId,expectedVersion));}
    @GetMapping("/storage-settings") public ResponseEntity<ApiResponse<Settings>> settings(@RequestHeader(value="Authorization",required=false)String auth){actor(auth);return ok(service.settings());}
    @PutMapping("/storage-settings") public ResponseEntity<ApiResponse<Operation>> settings(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody UpdateSettings input){return ok(service.updateSettings(actor(auth),input));}
    @PostMapping("/storage-configurations/{id}/probes") public ResponseEntity<ApiResponse<Operation>> probe(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody Probe input){return response(202,service.probe(actor(auth),id,input));}
    @GetMapping("/storage-operations/{requestId}") public ResponseEntity<ApiResponse<Operation>> operation(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String requestId){return ok(service.operation(actor(auth),requestId));}
    private <T> ResponseEntity<ApiResponse<T>> ok(T data){return response(200,data);}
    private <T> ResponseEntity<ApiResponse<T>> response(int status,T data){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
    @ExceptionHandler(StorageFailure.class) public ResponseEntity<SafeFailure> failure(StorageFailure e){return safe(e.status(),e.code());}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<SafeFailure> identity(ResponseStatusException e){return safe(e.getStatusCode().value(),e.getStatusCode().value()==401?"STORAGE_IDENTITY_REQUIRED":"STORAGE_ADMIN_REQUIRED");}
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<SafeFailure> malformed(Exception ignored){return safe(400,"STORAGE_INVALID_REQUEST");}
    @ExceptionHandler(Exception.class) public ResponseEntity<SafeFailure> unavailable(Exception ignored){return safe(503,"STORAGE_STATE_UNAVAILABLE");}
    private ResponseEntity<SafeFailure> safe(int status,String failure){
        String code=status==401?"A0220":status==403?"A0300":status==400?"A0400":"B0001";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new SafeFailure(false,code,"文件存储操作未完成","文件存储操作未完成",System.currentTimeMillis(),failure));
    }
    public record SafeFailure(boolean success,String code,String msg,String errorMessage,long timestamp,String failureCode){}
}
