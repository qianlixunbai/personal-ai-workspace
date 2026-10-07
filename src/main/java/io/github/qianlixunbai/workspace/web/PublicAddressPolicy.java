package io.github.qianlixunbai.workspace.web;

import java.net.*;

/** Application-owned byte/CIDR classification; no lookup, reverse lookup or probes. */
final class PublicAddressPolicy {
    // IANA special-purpose IPv4 registry (reviewed 2026-10-08). Reject whole allocations,
    // including globally reachable protocol/anycast exceptions, for this conservative policy.
    private static final long[][] IPV4_DENY = {
            {0x00000000L, 8}, {0x0a000000L, 8}, {0x64400000L, 10}, {0x7f000000L, 8},
            {0xa9fe0000L, 16}, {0xac100000L, 12}, {0xc0000000L, 24}, {0xc0000200L, 24},
            {0xc01fc400L, 24}, // 192.31.196.0/24 AS112
            {0xc034c100L, 24}, // 192.52.193.0/24 AMT
            {0xc0586300L, 24}, {0xc0a80000L, 16},
            {0xc0af3000L, 24}, // 192.175.48.0/24 AS112
            {0xc6120000L, 15}, {0xc6336400L, 24}, {0xcb007100L, 24},
            {0xe0000000L, 4}, {0xf0000000L, 4}
    };
    // Prefix bytes are explicit; only 2000::/3 is eligible in the first place.
    private static final byte[][] IPV6_DENY = {
            {0x20, 0x01, 0x00},                 // 2001::/23 protocol assignments (includes transition/ORCHID)
            {0x20, 0x01, 0x0d, (byte) 0xb8},   // 2001:db8::/32 documentation
            {0x20, 0x02},                      // 2002::/16 6to4
            {0x26, 0x20, 0x00, 0x4f, (byte) 0x80, 0x00}, // 2620:4f:8000::/48 AS112
            {0x3f, (byte) 0xff, 0x00}          // 3fff::/20 documentation
    };
    private static final int[] IPV6_BITS = {23, 32, 16, 48, 20};

    static boolean isPublic(InetAddress address) {
        if (address == null) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            long value = 0;
            for (byte b : bytes) value = (value << 8) | (b & 255);
            for (long[] cidr : IPV4_DENY) {
                long mask = (0xffffffffL << (32 - cidr[1])) & 0xffffffffL;
                if ((value & mask) == cidr[0]) return false;
            }
            return true;
        }
        if (!(address instanceof Inet6Address ipv6) || bytes.length != 16
                || ipv6.getScopeId() != 0 || ipv6.getScopedInterface() != null || (bytes[0] & 0xe0) != 0x20) return false;
        for (int i = 0; i < IPV6_DENY.length; i++) if (prefix(bytes, IPV6_DENY[i], IPV6_BITS[i])) return false;
        return true;
    }
    private static boolean prefix(byte[] value, byte[] network, int bits) {
        for (int i = 0; i < bits / 8; i++) if (value[i] != network[i]) return false;
        int rest = bits % 8, index = bits / 8;
        return rest == 0 || ((value[index] ^ network[index]) & (0xff << (8 - rest))) == 0;
    }
    private PublicAddressPolicy() {}
}
