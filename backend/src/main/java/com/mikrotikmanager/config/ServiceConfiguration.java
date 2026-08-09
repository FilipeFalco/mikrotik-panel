package com.mikrotikmanager.config;

import com.mikrotikmanager.service.OperationLockManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ServiceConfiguration {
    @Bean
    OperationLockManager operationLockManager() {
        return new OperationLockManager();
    }
}
