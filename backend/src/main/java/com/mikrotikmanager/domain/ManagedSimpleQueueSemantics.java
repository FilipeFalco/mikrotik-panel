package com.mikrotikmanager.domain;

import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import java.util.Set;

/** One fail-closed definition of the queue shape MTMGR may manage. */
public final class ManagedSimpleQueueSemantics {
    private static final Set<String> SAFE_UNKNOWN_OPERATIONAL_FIELDS = Set.of("bytes", "packets", "rate", "packet-rate", "queued-bytes", "queued-packets", "dropped", "borrows", "lends", "pcq-queues", "total-rate", "total-packet-rate", "total-bytes", "total-packets", "total-queued-bytes", "total-queued-packets", "total-dropped", "total-borrows", "total-lends", "total-pcq-queues");
    private ManagedSimpleQueueSemantics() { }
    public static boolean isOwnedPort(RouterSimpleQueue q, String iface) { return q != null && ManagedResourceIdentifier.isOwnedByPort(q.comment(), iface); }
    public static boolean isOwnedDevice(RouterSimpleQueue q, String mac) { return q != null && ManagedResourceIdentifier.isOwnedByDevice(q.comment(), mac); }
    public static boolean isSafeManagedQueue(RouterSimpleQueue q) {
        return q != null && !q.disabled() && !q.dynamic() && !q.invalid() && q.maxLimit() != null
                && inactive(q.limitAt()) && inactive(q.burstLimit()) && inactive(q.burstThreshold()) && inactive(q.burstTime()) && inactive(q.time()) && inactive(q.packetMarks()) && inactive(q.dstAddress())
                && bucketDefault(q.bucketSize()) && queueDefault(q.queue()) && priorityDefault(q.priority())
                && inactive(q.totalLimitAt()) && inactive(q.totalMaxLimit()) && priorityDefault(q.totalPriority()) && queueDefault(q.totalQueue())
                && inactive(q.totalBurstLimit()) && inactive(q.totalBurstThreshold()) && inactive(q.totalBurstTime()) && bucketDefault(q.totalBucketSize())
                && q.unknownFields().stream().allMatch(SAFE_UNKNOWN_OPERATIONAL_FIELDS::contains);
    }
    public static boolean matchesDesired(RouterSimpleQueue q, ManagedSimpleQueue d) { return q != null && d != null && d.name().equals(q.name()) && d.comment().equals(q.comment()) && d.target().equals(q.target()) && d.parent().equals(parent(q)) && d.maxLimit().equals(q.maxLimit()) && isSafeManagedQueue(q); }
    public static String parent(RouterSimpleQueue q) { return q.parent() == null || q.parent().isBlank() ? "none" : q.parent(); }
    private static boolean inactive(String v) { return v == null || v.isBlank() || "0".equals(v) || "0/0".equals(v) || "0s/0s".equals(v); }
    private static boolean bucketDefault(String v) { return v == null || v.isBlank() || "0.1".equals(v) || "0.1/0.1".equals(v); }
    private static boolean queueDefault(String v) { return v == null || v.isBlank() || "default/default".equals(v) || "default-small/default-small".equals(v); }
    private static boolean priorityDefault(String v) { return v == null || v.isBlank() || "8".equals(v) || "8/8".equals(v); }
}
