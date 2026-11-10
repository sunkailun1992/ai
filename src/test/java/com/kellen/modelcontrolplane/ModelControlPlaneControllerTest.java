package com.kellen.modelcontrolplane;

import com.kellen.controlplane.AiControlPlaneAdministrator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 复用真实身份 gate；这里验证 HTTP 拒绝状态、调用顺序与标准响应投影。 */
class ModelControlPlaneControllerTest {
    @Test void deniedIdentityDoesNotContactTheCatalog() throws Exception {
        for (HttpStatus denied : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN}) {
            var administrator = mock(AiControlPlaneAdministrator.class);
            var service = mock(ModelControlPlaneService.class);
            doThrow(new ResponseStatusException(denied, "private-denial-canary"))
                    .when(administrator).require(any());
            var mvc = MockMvcBuilders.standaloneSetup(new ModelControlPlaneController(administrator, service)).build();
            mvc.perform(get("/api/ai/model-control-plane").header("Authorization", "Bearer synthetic-user-fixture"))
                    .andExpect(status().is(denied.value())).andExpect(jsonPath("$.success").value(false))
                    .andExpect(header().string("Cache-Control", "no-store"));
            verify(service, never()).current();
        }
    }

    @Test void approvedReadUsesDataEnvelopeAndDoesNotCallAnyModel() throws Exception {
        var administrator = mock(AiControlPlaneAdministrator.class);
        var service = new ModelControlPlaneService(new ModelControlPlaneProperties());
        var mvc = MockMvcBuilders.standaloneSetup(new ModelControlPlaneController(administrator, service)).build();
        mvc.perform(get("/api/ai/model-control-plane").header("Authorization", "Bearer synthetic-user-fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.modelCatalog").isEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(administrator).require("Bearer synthetic-user-fixture");
    }
}
