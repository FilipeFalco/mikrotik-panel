package com.mikrotikmanager.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CidrValidatorTest {
    @Test
    void validatesIpv4Ipv6AndRejectsHostnamesWithoutDnsResolution() {
        assertThat(CidrValidator.isValid("10.10.10.0/24")).isTrue();
        assertThat(CidrValidator.isValid("192.168.1.0/24")).isTrue();
        assertThat(CidrValidator.isValid("2001:db8::/64")).isTrue();

        assertThat(CidrValidator.isValid("10.10.10.0/33")).isFalse();
        assertThat(CidrValidator.isValid("2001:db8::/129")).isFalse();
        assertThat(CidrValidator.isValid("10.10.10.0")).isFalse();
        assertThat(CidrValidator.isValid("example.com/24")).isFalse();
        assertThat(CidrValidator.isValid("localhost/24")).isFalse();
        assertThat(CidrValidator.isValid("router.local/24")).isFalse();
        assertThat(CidrValidator.isValid("999.10.10.0/24")).isFalse();
        assertThat(CidrValidator.isValid("10.10.10.0/not-a-prefix")).isFalse();
    }

    @Test
    void normalizesNetworkAddressDeterministically() {
        assertThat(CidrValidator.normalize("10.10.10.17/24")).isEqualTo("10.10.10.0/24");
        assertThat(CidrValidator.normalize("2001:db8::1/64")).isEqualTo("2001:db8::/64");
    }

    @Test
    void detectsIpv4CidrOverlapByIntervalIntersectionWithoutDnsResolution() {
        assertThat(overlaps("10.10.10.0/24", "10.10.10.0/24")).isTrue();
        assertThat(overlaps("10.10.10.0/24", "10.10.10.0/25")).isTrue();
        assertThat(overlaps("10.10.10.0/24", "10.10.10.128/25")).isTrue();
        assertThat(overlaps("10.10.10.0/24", "10.10.0.0/16")).isTrue();
        assertThat(overlaps("10.10.10.0/24", "10.10.11.0/24")).isFalse();
        assertThat(overlaps("10.10.10.0/24", "10.20.0.0/16")).isFalse();
        assertThat(overlaps("10.10.10.0/24", "10.10.10.21")).isTrue();
    }

    @Test
    void treatsHostnamesAndUnparseableTargetsAsNoOverlapRatherThanResolvingDns() {
        assertThat(overlaps("10.10.10.0/24", "example.com/24")).isFalse();
        assertThat(overlaps("10.10.10.0/24", "ether2")).isFalse();
        assertThat(overlaps("10.10.10.0/24", "")).isFalse();
        assertThat(overlaps("10.10.10.0/24", null)).isFalse();
    }

    private static boolean overlaps(String left, String right) {
        CidrRange a = CidrValidator.parseRange(left);
        CidrRange b = CidrValidator.parseRange(right);
        return a != null && b != null && a.overlaps(b);
    }
}
