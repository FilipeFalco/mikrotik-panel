package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.persistence.ManagedDeviceRepository;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Seeds only an empty local database in mock mode; it never changes RouterOS. */
@Component
@ConditionalOnProperty(name = "mikrotik.mock-mode", havingValue = "true", matchIfMissing = true)
class MockDataSeeder implements ApplicationRunner {
    private final ManagedPortRepository managedPortRepository;
    private final ManagedDeviceRepository managedDeviceRepository;

    MockDataSeeder(ManagedPortRepository managedPortRepository, ManagedDeviceRepository managedDeviceRepository) {
        this.managedPortRepository = managedPortRepository;
        this.managedDeviceRepository = managedDeviceRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (managedPortRepository.count() > 0) {
            return;
        }
        Instant now = Instant.now();
        // Fixture roles are explicit local metadata, never inferred by the runtime from an interface name.
        managedPortRepository.save(new ManagedPort(0, "ether1", "Internet", "Link principal", null, null,
                ManagedPortRole.WAN, false, now, now));
        managedPortRepository.save(new ManagedPort(0, "ether2", "Cliente João", "Casa João", "10.10.10.0/24", "dhcp-joao",
                ManagedPortRole.CLIENT, true, now, now));
        managedPortRepository.save(new ManagedPort(0, "ether3", "Cliente Maria", "Casa Maria", "10.10.20.0/24", "dhcp-maria",
                ManagedPortRole.CLIENT, true, now, now));
        managedPortRepository.save(new ManagedPort(0, "ether4", "Escritório", "Rede do escritório", "10.10.30.0/24", "dhcp-escritorio",
                ManagedPortRole.CLIENT, true, now, now));
        managedDeviceRepository.save("AA:BB:CC:DD:EE:01", "Galaxy S25", "Celular principal");
        managedDeviceRepository.save("AA:BB:CC:DD:EE:02", "Notebook João", "");
        managedDeviceRepository.save("AA:BB:CC:DD:EE:03", "Samsung TV", "Sala");
        managedDeviceRepository.save("AA:BB:CC:DD:EF:01", "Notebook Maria", "Home office");
        managedDeviceRepository.save("AA:BB:CC:DD:F0:01", "PC Recepção", "Recepção");
    }
}
