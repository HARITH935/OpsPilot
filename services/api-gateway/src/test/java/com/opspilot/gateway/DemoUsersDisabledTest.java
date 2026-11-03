package com.opspilot.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The default, as in the cloud: DEMO_USERS_ENABLED is not set, so there is no demo login at all
@SpringBootTest
@AutoConfigureMockMvc
class DemoUsersDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theDemoLoginDoesNotExist() throws Exception {
        mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"whatever-you-try\"}"))
                .andExpect(status().isNotFound());   // not "wrong password": there is nothing to try
    }

    @Test
    void thePublicKeyIsStillPublished() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))   // GitHub login (Phase 12) will sign tokens with it too
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());   // "d" would be the private exponent
    }
}
