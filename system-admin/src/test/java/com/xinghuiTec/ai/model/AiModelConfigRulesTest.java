package com.xinghuiTec.ai.model;

import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiModelConfigRulesTest {
    private final AiModelConfigRules rules = new AiModelConfigRules();

    @Test
    void acceptsPublicHttpsDnsAndPublicIpEndpointsWithoutMakingRequests() {
        assertEquals("https://api.example.com/v1", rules.endpointUrl(" https://api.example.com/v1 "));
        assertEquals("https://203.0.114.8/v1", rules.endpointUrl("https://203.0.114.8/v1"));
        assertEquals("OPENAI_API_KEY", rules.credentialEnvName(" OPENAI_API_KEY "));
        assertNull(rules.credentialEnvName("  "));
    }

    @Test
    void rejectsNonHttpsCredentialsFragmentsQueriesAndLocalHosts() {
        List<String> endpoints = List.of(
                "http://api.example.com/v1",
                "https://user:pass@api.example.com/v1",
                "https://api.example.com/v1?token=secret",
                "https://api.example.com/v1#fragment",
                "https://localhost/v1",
                "https://service.internal/v1",
                "https://host.local/v1",
                "https://10.1.2.3/v1",
                "https://172.20.0.1/v1",
                "https://192.168.1.3/v1",
                "https://127.0.0.1/v1",
                "https://169.254.169.254/latest",
                "https://100.64.0.1/v1",
                "https://0177.0.0.1/v1",
                "https://2130706433/v1",
                "https://[::1]/v1",
                "https://[fc00::1]/v1",
                "https://[fe80::1]/v1",
                "https://[2001:db8::1]/v1");
        for (String endpoint : endpoints) {
            assertThrows(ServiceException.class, () -> rules.endpointUrl(endpoint), endpoint);
        }
    }

    @Test
    void acceptsOnlyEnvironmentVariableNamesAndBoundedTimeouts() {
        for (String value : List.of("sk-test-secret", "OpenAI-Key", "KEY=secret", "api.key", "has space",
                "AKIAIOSFODNN7EXAMPLE", "APIKEY")) {
            assertThrows(ServiceException.class, () -> rules.credentialEnvName(value));
        }
        for (Integer value : List.of(0, -1, 301)) {
            assertThrows(ServiceException.class, () -> rules.timeoutSeconds(value));
        }
        assertEquals(300, rules.timeoutSeconds(300));
    }
}
