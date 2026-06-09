package com.picsou.config;

import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.SetupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnableBankingConfigProviderTest {

    @Mock SetupService setupService;
    @Mock EnableBankingKeyPairService keyPairService;

    @TempDir Path tmp;

    private EnableBankingConfigProvider provider(String defaultKeyPath, String envPem, String envKeyPath) {
        when(keyPairService.privateKeyPath()).thenReturn(Path.of(defaultKeyPath));
        return new EnableBankingConfigProvider(
            setupService, keyPairService, "", "", "", envPem, envKeyPath);
    }

    @Test
    void keyId_fallsBackToApplicationId_whenNotSeparatelyConfigured() {
        // Per EB's spec the kid IS the application id; with no separate key-id
        // set (DB empty, env blank), keyId() derives from applicationId().
        when(keyPairService.privateKeyPath()).thenReturn(tmp.resolve("k.pem"));
        var p = new EnableBankingConfigProvider(
            setupService, keyPairService, "the-app-id", "", "", "", "");
        assertThat(p.keyId()).contains("the-app-id");
    }

    @Test
    void keyId_prefersExplicitlyConfiguredValue_forBackwardCompat() {
        // A legacy install that set ENABLEBANKING_KEY_ID keeps using it verbatim.
        when(keyPairService.privateKeyPath()).thenReturn(tmp.resolve("k.pem"));
        var p = new EnableBankingConfigProvider(
            setupService, keyPairService, "the-app-id", "explicit-key-id", "", "", "");
        assertThat(p.keyId()).contains("explicit-key-id");
    }

    @Test
    void privateKeyPresent_falseWhenNothingConfigured() {
        var p = provider(tmp.resolve("absent.pem").toString(), "", "");
        assertThat(p.privateKeyPresent()).isFalse();
    }

    @Test
    void privateKeyPresent_trueWhenInlineEnvPemSet() {
        var p = provider(tmp.resolve("absent.pem").toString(), "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----", "");
        assertThat(p.privateKeyPresent()).isTrue();
    }

    @Test
    void privateKeyPresent_trueWhenDefaultFileExists() throws Exception {
        Path key = tmp.resolve("enablebanking-private.pem");
        Files.writeString(key, "anything");
        var p = provider(key.toString(), "", "");
        assertThat(p.privateKeyPresent()).isTrue();
    }

    @Test
    void privateKeyPresent_doesNotParse_soAMalformedKeyStillReportsPresent() throws Exception {
        // A garbage file is still "present" — parsing (and any failure) is deferred
        // to privateKey() at actual use time, keeping status surfaces crash-free.
        Path key = tmp.resolve("garbage.pem");
        Files.writeString(key, "not a real pem");
        var p = provider(key.toString(), "", "");
        assertThat(p.privateKeyPresent()).isTrue();
    }
}
