package com.mikrotikmanager.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Process-local serialization for operations targeting the same device or port. */
public class OperationLockManager {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String key, Supplier<T> operation) {
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(String key, Runnable operation) {
        withLock(key, () -> {
            operation.run();
            return null;
        });
    }
}
