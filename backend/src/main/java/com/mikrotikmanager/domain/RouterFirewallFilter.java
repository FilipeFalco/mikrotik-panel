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
     * external block candidate. It does not infer what a future block
     * strategy should create.
     */
    public boolean isActiveBlockingAction() {
        return !disabled && !dynamic
                && ("drop".equalsIgnoreCase(action) || "reject".equalsIgnoreCase(action));
    }
}
