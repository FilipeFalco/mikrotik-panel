package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.RouterSnapshot;

/**
 * Optional read-only gateway capability for obtaining one batched RouterOS
 * snapshot. It has no mutation operation.
 */
public interface RouterSnapshotReader {
    RouterSnapshot captureSnapshot();
}
