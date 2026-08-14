package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.JsonNode;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.ManagedSimpleQueue;
import com.mikrotikmanager.domain.SpeedLimit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transport regression: the queue writer has no generic method or POST path. */
class RouterOsQueueWriteClientTest {
    @Test void writesOnlyTheApprovedBodiesAndValidatedPaths() {
        try (FakeRouterOsServer fake = FakeRouterOsServer.start().enableStatefulSimpleQueues().seedSimpleQueues("[]")) {
            RouterOsQueueWriteClient writer = new RouterOsQueueWriteClient(properties(fake), RestClient.builder().baseUrl(fake.baseUri().toString()).build());
            ManagedSimpleQueue queue = ManagedSimpleQueue.port("ether2", "10.10.10.0/24", new SpeedLimit(100_000_000, 20_000_000));
            writer.createManagedQueue(queue, null);
            JsonNode created = RestClient.builder().baseUrl(fake.baseUri().toString()).build().get().uri("/rest/queue/simple").retrieve().body(JsonNode.class).get(0);
            String id = created.path(".id").asText();
            writer.updateManagedQueue(id, new ManagedSimpleQueue(queue.name(), queue.comment(), queue.target(), "none", new SpeedLimit(80_000_000, 10_000_000)));
            writer.deleteManagedQueue(id);
            assertThat(fake.writeJournal()).extracting(FakeRouterOsServer.CapturedWrite::method).containsExactly("PUT", "PATCH", "DELETE");
            assertThat(fake.writeJournal().getFirst().body()).contains("name", "comment", "disabled", "target", "parent", "max-limit");
            assertThat(fake.writeJournal().get(1).body()).contains("target", "parent", "max-limit").doesNotContain("name", "comment", "burst", "packet-marks");
            assertThatThrownBy(() -> writer.deleteManagedQueue("bad/id")).isInstanceOf(RouterOsWriteClientException.class);
        }
    }

    private static MikrotikProperties properties(FakeRouterOsServer fake) {
        return new MikrotikProperties("127.0.0.1", fake.baseUri().getPort(), "read-user", "read-password", "write-user", "write-password", true, false, true, false, true, 500, 1_000);
    }
}
