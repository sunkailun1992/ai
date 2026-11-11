package com.kellen.exception;

import com.kellen.conversation.AiChatResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局 API 异常处理器。
 *
 * <p>把 Controller 抛出的异常统一转换为安全的 JSON 响应：
 * 只返回可读的错误摘要，不暴露内部堆栈、密钥或底层服务地址。</p>
 */
@RestControllerAdvice // 对所有 REST Controller 全局生效。
public class ApiExceptionHandler {

    /** MVC 路由或静态资源不存在时保留 404；不回显请求路径、查询串或异常原文。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public AiChatResponse notFound() {
        return AiChatResponse.error("AI_RESOURCE_NOT_FOUND");
    }

    /**
     * 处理入参校验和业务边界错误，返回 400。
     *
     * @param exception 入参校验抛出的异常。
     * @return 包含可读错误信息的统一响应。
     */
    @ExceptionHandler(IllegalArgumentException.class) // 捕获入参校验错误。
    @ResponseStatus(HttpStatus.BAD_REQUEST) // 入参错误返回 400。
    public AiChatResponse badRequest(IllegalArgumentException exception) {
        return AiChatResponse.error(exception.getMessage()); // 返回可读错误，不暴露堆栈。
    }

    /**
     * 兜底处理未知异常，返回 500。
     *
     * @return 只含固定错误码的统一响应，不暴露异常、内部地址、SQL 或凭据。
     */
    @ExceptionHandler(Exception.class) // 兜底捕获未知异常。
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // 未知错误返回 500。
    public AiChatResponse serverError() {
        return AiChatResponse.error("AI_REQUEST_FAILED");
    }
}
