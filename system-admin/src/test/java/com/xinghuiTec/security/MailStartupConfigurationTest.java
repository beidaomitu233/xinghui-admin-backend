package com.xinghuiTec.security;

import com.xinghuiTec.mail.config.MailConfig;
import com.xinghuiTec.mail.config.MailProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailStartupConfigurationTest {

    @Test
    void enabledMailRejectsMissingSettingsWithoutConnecting() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MailConfig().mailAccount(new MailProperties()));
        assertEquals("Enabled mail requires host, user, pass and from", error.getMessage());
    }
}
