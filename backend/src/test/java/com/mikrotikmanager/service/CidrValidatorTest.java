package com.mikrotikmanager.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CidrValidatorTest {
    @Test
    void validatesIpv4AndRejectsMalformedNetworks() {
        assertThat(CidrValidator.isValid("10.10.10.0/24")).isTrue();
        assertThat(CidrValidator.isValid("10.10.10.0/33")).isFalse();
        assertThat(CidrValidator.isValid("10.10.10.0")).isFalse();
    }
}
