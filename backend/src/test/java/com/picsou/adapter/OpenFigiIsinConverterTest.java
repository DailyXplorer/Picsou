package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFigiIsinConverterTest {

    @Test
    void isIsin_recognizesValidIsinCodes() {
        // 2-letter country prefix + 9 alphanumerics + 1 check digit = 12 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y983")).isTrue(); // iShares Core MSCI World
        assertThat(OpenFigiIsinConverter.isIsin("US0378331005")).isTrue(); // Apple
        assertThat(OpenFigiIsinConverter.isIsin("DE0007100000")).isTrue(); // Mercedes-Benz
        assertThat(OpenFigiIsinConverter.isIsin("KYG9830T1067")).isTrue(); // Xiaomi
    }

    @Test
    void isIsin_normalizesCaseAndWhitespace() {
        assertThat(OpenFigiIsinConverter.isIsin("ie00b4l5y983")).isTrue();
        assertThat(OpenFigiIsinConverter.isIsin("  IE00B4L5Y983  ")).isTrue();
    }

    @Test
    void isIsin_rejectsTickersAndNonIsinStrings() {
        assertThat(OpenFigiIsinConverter.isIsin("IWDA.AS")).isFalse(); // Yahoo ticker (has a dot)
        assertThat(OpenFigiIsinConverter.isIsin("AAPL")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("BTC")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y98")).isFalse();  // 11 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y9833")).isFalse(); // 13 chars
        assertThat(OpenFigiIsinConverter.isIsin("12345678901X")).isFalse();  // digits in country position
    }

    @Test
    void isIsin_rejectsNullAndBlank() {
        assertThat(OpenFigiIsinConverter.isIsin(null)).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("   ")).isFalse();
    }
}
