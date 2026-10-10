package com.example.airline;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiErrorHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new StreamingErrors())
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void unusableStreamIsHandledWithoutWritingAnErrorBody() throws Exception {
        var result = mvc.perform(get("/test/disconnected"))
                .andExpect(content().string("")).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
    }

    @Test
    void committedUnusableStreamWritesNothing() throws Exception {
        var result = mvc.perform(get("/test/committed"))
                .andExpect(content().string("")).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
    }

    @Test
    void errorBeforeStreamCommitUsesJsonRatherThanEventStream() throws Exception {
        mvc.perform(get("/test/before-stream"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("THREAD_NOT_FOUND"));
    }

    @RestController
    static class StreamingErrors {
        @GetMapping("/test/disconnected")
        void disconnected(HttpServletResponse response) throws AsyncRequestNotUsableException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new AsyncRequestNotUsableException("Response not usable after client disconnect");
        }

        @GetMapping("/test/committed")
        void committed(HttpServletResponse response) throws java.io.IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.flushBuffer();
            throw new AsyncRequestNotUsableException("Response not usable after client disconnect");
        }

        @GetMapping("/test/before-stream")
        void beforeStream(HttpServletResponse response) {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new ApiException(404, "THREAD_NOT_FOUND", "会话不存在");
        }
    }
}
