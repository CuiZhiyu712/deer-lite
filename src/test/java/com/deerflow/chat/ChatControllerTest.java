package com.deerflow.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 模块化：切片注解迁至 spring-boot-webmvc-test 模块（原 org.springframework.boot.test.autoconfigure.web.servlet）
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void createListAndFetchSessions() throws Exception {
        String body = mvc.perform(post("/api/sessions")
                        .contentType("application/json").content("{\"title\":\"测试会话\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(get("/api/sessions/" + id + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/sessions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("测试会话"));
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        mvc.perform(get("/api/sessions/no-such-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void runsOnUnknownSessionReturns404() throws Exception {
        mvc.perform(post("/api/sessions/no-such-id/runs")
                        .contentType("application/json").content("{\"input\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void modelsEndpointReturnsList() throws Exception {
        mvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
