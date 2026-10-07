package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.net.*;
import java.util.*;

final class ValidatedAddressSet {
    private final String hostname;
    private final List<InetAddress> addresses;
    // Package-private construction is also the local TLS fixture seam; no API/config bypass.
    ValidatedAddressSet(String hostname, List<InetAddress> addresses) {
        this.hostname = hostname;
        this.addresses = List.copyOf(addresses);
        if (addresses.isEmpty()) throw new IllegalArgumentException("Empty address set");
    }
    static ValidatedAddressSet validate(String hostname, InetAddress[] answers) {
        if (answers == null || answers.length == 0 || answers.length > WebLimits.DNS_ENTRIES)
            throw new WorkspaceException(ErrorCode.WEB_DNS_FAILED, "DNS");
        for (InetAddress address : answers) {
            if (!PublicAddressPolicy.isPublic(address)) throw new WorkspaceException(ErrorCode.WEB_TARGET_NOT_PUBLIC, "DNS");
        }
        Map<String, InetAddress> unique = new LinkedHashMap<>();
        for (InetAddress address : answers) {
            byte[] bytes = address.getAddress();
            try { unique.putIfAbsent(HexFormat.of().formatHex(bytes), InetAddress.getByAddress(bytes)); }
            catch (UnknownHostException impossible) { throw new WorkspaceException(ErrorCode.WEB_DNS_FAILED, "DNS"); }
        }
        return new ValidatedAddressSet(hostname, List.copyOf(unique.values()));
    }
    String hostname() { return hostname; }
    List<InetAddress> addresses() { return addresses; }
    @Override public String toString() { return "ValidatedAddressSet[redacted]"; }
}
