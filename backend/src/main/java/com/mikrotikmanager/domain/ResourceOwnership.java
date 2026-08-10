package com.mikrotikmanager.domain;

/**
 * Conservative ownership classification for a RouterOS resource observed by
 * the panel.
 *
 * <p>{@link #MANAGED} is allowed only after an exact expected ownership
 * identifier matches. {@link #FOREIGN} means a non-empty, different ownership
 * comment was observed for the resource under evaluation. {@link #UNKNOWN}
 * means ownership cannot be proven, including resources without a comment.</p>
 */
public enum ResourceOwnership {
    MANAGED,
    FOREIGN,
    UNKNOWN
}
