package com.example.capstoneserver.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

    private static final String FRONT = "https://demo-site-mu-liart.vercel.app";

    @Autowired
    MockMvc mockMvc;

    @Test
    void 프론트_주소의_사전_요청을_허용한다() throws Exception {
        mockMvc.perform(options("/api/simulate")
                        .header("Origin", FRONT)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONT));
    }

    @Test
    void 프론트_주소의_요청에_허용_헤더를_붙인다() throws Exception {
        mockMvc.perform(get("/api/examples").header("Origin", FRONT))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONT));
    }

    @Test
    void 다른_주소는_막는다() throws Exception {
        mockMvc.perform(options("/api/simulate")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
