package com.azurpilot.ghio.proot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 覆盖纯函数 [resolvConfContent]：回退、归一化（剔空/无 scope link-local/IPv4
 * scope）、保序（不重排系统顺序）、去重、阿里兜底追加与 MAXNS 截断。
 *
 * Covers the pure function [resolvConfContent]: fallback, normalization
 * (blank entries, unscoped link-local IPv6, and IPv4 with a scope suffix are
 * dropped), order preservation (the system order is never re-arranged),
 * de-duplication, and the appended AliDNS fallback with the MAXNS cap.
 */
class ResolvConfTest {

    @Test
    fun `empty system dns falls back to AliDNS`() {
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(emptyList()),
        )
    }

    @Test
    fun `blank entries are ignored and fall back`() {
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(listOf("", "  ")),
        )
    }

    @Test
    fun `system servers keep the published order with fallback appended`() {
        assertEquals(
            "nameserver 211.138.106.2\nnameserver 114.114.114.114\nnameserver 223.5.5.5\n",
            resolvConfContent(listOf("211.138.106.2", "114.114.114.114")),
        )
    }

    @Test
    fun `ipv6 before ipv4 is not re-ordered`() {
        assertEquals(
            "nameserver 2001:4860:4860::8888\nnameserver 8.8.8.8\nnameserver 223.5.5.5\n",
            resolvConfContent(listOf("2001:4860:4860::8888", "8.8.8.8")),
        )
    }

    @Test
    fun `duplicates drop keeping first occurrence`() {
        assertEquals(
            "nameserver 10.0.0.1\nnameserver 10.0.0.2\nnameserver 223.5.5.5\n",
            resolvConfContent(listOf("10.0.0.1", "10.0.0.2", "10.0.0.1")),
        )
    }

    @Test
    fun `three system servers fill the cap without appended fallback`() {
        assertEquals(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n",
            resolvConfContent(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")),
        )
    }

    @Test
    fun `system publishing AliDNS de-duplicates the fallback`() {
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(listOf("223.5.5.5")),
        )
    }

    @Test
    fun `scoped link-local ipv6 is kept`() {
        assertEquals(
            "nameserver fe80::1%wlan0\nnameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(listOf("fe80::1%wlan0")),
        )
    }

    @Test
    fun `unscoped link-local is dropped`() {
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(listOf("fe80::1", "febf::2")),
        )
    }

    @Test
    fun `ipv4 with scope suffix is dropped`() {
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(listOf("192.168.1.1%1")),
        )
    }

    @Test
    fun `ipv4 link-local is kept without scope`() {
        assertEquals(
            "nameserver 169.254.1.53\nnameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(listOf("169.254.1.53")),
        )
    }

    @Test
    fun `ipv4 link-local with scope suffix is dropped`() {
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 223.6.6.6\n",
            resolvConfContent(listOf("169.254.1.53%1")),
        )
    }

    @Test
    fun `mixed set keeps usable entries in order`() {
        assertEquals(
            "nameserver 114.114.114.114\nnameserver fe80::1%wlan0\nnameserver 223.5.5.5\n",
            resolvConfContent(listOf("114.114.114.114", "fe80::1%wlan0", "fe80::1", "")),
        )
    }
}
