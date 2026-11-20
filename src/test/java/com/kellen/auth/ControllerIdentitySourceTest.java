package com.kellen.auth;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份只来自资源服务器写入的登录上下文：控制器不再自己读 {@code Authorization} 请求头解析令牌。
 */
class ControllerIdentitySourceTest {

    @Test
    void controllersNoLongerReadAuthorizationHeader() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> controllers = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.kellen")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
            controllers.add(type.getSimpleName());
            for (Method method : type.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    RequestHeader header = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestHeader.class);
                    if (header != null && "Authorization".equalsIgnoreCase(header.name())) {
                        offenders.add(type.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }
        assertThat(controllers).contains("AiDialogueController", "ModelControlPlaneController",
                "StorageConfigurationController", "AiModelSelectionController");
        assertThat(offenders).isEmpty();
    }
}
