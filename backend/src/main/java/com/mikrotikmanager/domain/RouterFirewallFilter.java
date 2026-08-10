package com.mikrotikmanager.domain;

/** Read-only firewall filter state. */
public record RouterFirewallFilter(
        String id,
        String action,
        String chain,
        String comment,
        boolean disabled,
        boolean dynamic,
        String srcAddress,
        String srcAddressList
) {
    /** Compatibility constructor for fixtures that only need FastTrack state. */
    public RouterFirewallFilter(String id, String action, String chain, String comment, boolean disabled, boolean dynamic) {
        this(id, action, chain, comment, disabled, dynamic, null, null);
    }

    public boolean isActiveFastTrack() {
        return !disabled && "fasttrack-connection".equalsIgnoreCase(action);
    }

    /**
     * A deliberately narrow read-only signal used only to flag an existing
     * external block candidate for client traffic traversing the router.
     *
     * <p>Only {@code chain=forward} {@code drop}/{@code reject} rules are
     * considered. A {@code chain=input} drop protects the MikroTik itself and
     * must never be interpreted as a client block, even when it references the
     * device IP or address list. This deliberately does not infer blocking
     * intent from {@code input}, {@code output}, {@code raw}, {@code mangle}
     * or {@code nat} chains, and it does not decide what a future block
     * strategy should create.</p>
     */
    public boolean isActiveForwardBlockingAction() {
        return !disabled && !dynamic
                && "forward".equalsIgnoreCase(chain)
                && ("drop".equalsIgnoreCase(action) || "reject".equalsIgnoreCase(action));
    }

    /**
     * Compatibility alias for callers of the original narrow predicate. The
     * chain restriction is intentionally retained; this is not a generic
     * drop/reject detector.
     */
    @Deprecated
    public boolean isActiveBlockingAction() {
        return isActiveForwardBlockingAction();
    }
}
