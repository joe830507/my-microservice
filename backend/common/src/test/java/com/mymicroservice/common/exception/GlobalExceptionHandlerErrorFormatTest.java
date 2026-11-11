package com.mymicroservice.common.exception;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// SEC-08：錯誤回應格式一致（ProblemDetail + code），而且不把 Spring 的原始訊息（會帶出內部類別名稱）回給用戶端
class GlobalExceptionHandlerErrorFormatTest {
    enum Color {
        RED, BLUE
    }

    record Query(Color color) {
    }

    record Body(String name) {
    }

    @RestController
    static class Probe {
        @GetMapping("/probe/{id}")
        String byId(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/probe")
        String byQuery(Query query) {
            return String.valueOf(query.color());
        }

        @org.springframework.web.bind.annotation.PostMapping("/probe-body")
        String withBody(@RequestBody Body body) {
            return body.name();
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void pathVariableTypeMismatch_isValidationFailed_withoutSpringInternals() throws Exception {
        mockMvc.perform(get("/probe/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("id: invalid value"))
                .andExpect(jsonPath("$.detail", not(containsString("Failed to convert"))))
                .andExpect(jsonPath("$.detail", not(containsString("UUID"))));
    }

    @Test
    void queryEnumBindingFailure_doesNotLeakClassNames() throws Exception {
        mockMvc.perform(get("/probe").param("color", "HACKED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("color: invalid value"))
                .andExpect(jsonPath("$.detail", not(containsString("com."))))
                .andExpect(jsonPath("$.detail", not(containsString("Color"))))
                .andExpect(jsonPath("$.detail", not(containsString("Failed to convert"))));
    }

    @Test
    void validValues_stillWork() throws Exception {
        mockMvc.perform(get("/probe/" + UUID.randomUUID())).andExpect(status().isOk());
        mockMvc.perform(get("/probe").param("color", "RED")).andExpect(status().isOk());
    }

    @Test
    void springGeneratedErrors_getACode() throws Exception {
        // 方法不支援、JSON 格式錯誤：Spring 預設的 ProblemDetail 沒有 code
        mockMvc.perform(post("/probe/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        mockMvc.perform(post("/probe-body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
