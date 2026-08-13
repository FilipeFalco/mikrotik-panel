package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.JsonNode;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.ManagedDeviceBlockRule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterOsWriteClientTest {
    private static final String FIREWALL_FILTERS = "/rest/ip/firewall/filter";

    @Test
    void productionWriteClientUsesPutAndDeleteAgainstTheOrderedFakeState() {
        try (FakeRouterOsServer fake = statefulFake("""
                [
                  {".id":"*base","action":"accept"},
                  {".id":"*tail","action":"drop"}
                ]
                """)) {
            String writePassword = "write-password-not-recorded";
            RouterOsWriteClient writer = new RouterOsWriteClient(
                    writableProperties(fake.baseUri(), writePassword),
                    client(fake.baseUri(), "write-user", writePassword)
            );
            ManagedDeviceBlockRule rule = new ManagedDeviceBlockRule("AA:BB:CC:DD:EE:01");

            writer.createManagedDeviceBlockRule(rule, "*tail");

            JsonNode afterCreate = client(fake.baseUri()).get()
                    .uri(FIREWALL_FILTERS)
                    .retrieve()
                    .body(JsonNode.class);
            assertThat(filterIds(afterCreate)).hasSize(3)
                    .contains("*base", "*tail");
            JsonNode created = filterWithComment(afterCreate, rule.comment());
            String generatedId = created.path(".id").asText();
            assertThat(generatedId).startsWith("*FAKE");
            assertThat(filterIds(afterCreate)).containsExactly("*base", generatedId, "*tail");

            assertThat(fake.writeJournal()).singleElement().satisfies(write -> {
                assertThat(write.method()).isEqualTo("PUT");
                assertThat(write.body()).contains("place-before", rule.comment(), rule.srcMacAddress());
                assertThat(write.body()).doesNotContain(writePassword, "Authorization");
            });

            writer.deleteManagedDeviceBlockRule(generatedId);

            JsonNode afterDelete = client(fake.baseUri()).get()
                    .uri(FIREWALL_FILTERS)
                    .retrieve()
                    .body(JsonNode.class);
            assertThat(filterIds(afterDelete)).containsExactly("*base", "*tail");
            assertThat(fake.writeJournal()).extracting(FakeRouterOsServer.CapturedWrite::method)
                    .containsExactly("PUT", "DELETE");
        }
    }

    @Test
    void putsAFilterWithSanitizedBodyAndPreservesPlaceBeforeOrder() {
        try (FakeRouterOsServer fake = statefulFake("""
                [
                  {".id":"*base","action":"accept","comment":"base"},
                  {".id":"*tail","action":"drop","comment":"tail"}
                ]
                """)) {
            RestClient client = client(fake.baseUri());
            String password = "must-not-appear-in-the-journal";

            JsonNode response = client.put()
                    .uri(FIREWALL_FILTERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                            {
                              ".id":"caller-chosen-id",
                              "action":"drop",
                              "comment":"managed rule",
                              "place-before":"*tail",
                              "password":"%s",
                              "authorization":"Bearer %s"
                            }
                            """.formatted(password, password))
                    .retrieve()
                    .body(JsonNode.class);

            assertThat(response).isNotNull();
            String generatedId = response.path("ret").asText();
            assertThat(generatedId).startsWith("*FAKE").isNotEqualTo("caller-chosen-id");

            assertThat(fake.readJournal()).isEmpty();
            assertThat(fake.writeJournal()).singleElement().satisfies(write -> {
                assertThat(write.method()).isEqualTo("PUT");
                assertThat(write.path()).isEqualTo(FIREWALL_FILTERS);
                assertThat(write.body()).contains("place-before", "managed rule", "[REDACTED]");
                assertThat(write.body()).doesNotContain(password, "Authorization");
            });

            assertThat(filterIds(client.get().uri(FIREWALL_FILTERS).retrieve().body(JsonNode.class)))
                    .containsExactly("*base", generatedId, "*tail");
            assertThat(fake.readJournal()).singleElement()
                    .extracting(FakeRouterOsServer.CapturedRequest::method)
                    .isEqualTo("GET");
        }
    }

    @Test
    void doesNotAppendWhenPlaceBeforeIsUnknownAndDeletesOnlyAnExactId() {
        try (FakeRouterOsServer fake = statefulFake("""
                [
                  {".id":"*base","action":"accept"},
                  {".id":"*tail","action":"drop"}
                ]
                """)) {
            RestClient client = client(fake.baseUri());
            String password = "unknown-place-before-password";

            assertStatus(HttpStatus.NOT_FOUND, () -> client.put()
                    .uri(FIREWALL_FILTERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                            {"action":"drop","place-before":"*does-not-exist","password":"%s"}
                            """.formatted(password))
                    .retrieve()
                    .toBodilessEntity());
            assertThat(filterIds(client.get().uri(FIREWALL_FILTERS).retrieve().body(JsonNode.class)))
                    .containsExactly("*base", "*tail");

            assertStatus(HttpStatus.NOT_FOUND, () -> client.delete()
                    .uri(FIREWALL_FILTERS + "/*base/extra")
                    .retrieve()
                    .toBodilessEntity());
            assertThat(filterIds(client.get().uri(FIREWALL_FILTERS).retrieve().body(JsonNode.class)))
                    .containsExactly("*base", "*tail");

            assertStatus(HttpStatus.OK, () -> client.delete()
                    .uri(FIREWALL_FILTERS + "/*base")
                    .retrieve()
                    .toBodilessEntity());
            assertThat(filterIds(client.get().uri(FIREWALL_FILTERS).retrieve().body(JsonNode.class)))
                    .containsExactly("*tail");

            assertThat(fake.writeJournal()).extracting(FakeRouterOsServer.CapturedWrite::method)
                    .containsExactly("PUT", "DELETE", "DELETE");
            assertThat(fake.writeJournal().getFirst().body()).doesNotContain(password);
        }
    }

    @Test
    void rejectsPostAndPatchForTheStatefulFirewallCollection() {
        try (FakeRouterOsServer fake = statefulFake("[{\".id\":\"*base\",\"action\":\"accept\"}]")) {
            RestClient client = client(fake.baseUri());
            String password = "method-allowlist-password";
            String body = "{\"action\":\"drop\",\"password\":\"" + password + "\"}";

            assertStatus(HttpStatus.METHOD_NOT_ALLOWED, () -> client.post()
                    .uri(FIREWALL_FILTERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity());
            assertStatus(HttpStatus.METHOD_NOT_ALLOWED, () -> client.patch()
                    .uri(FIREWALL_FILTERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity());

            assertThat(filterIds(client.get().uri(FIREWALL_FILTERS).retrieve().body(JsonNode.class)))
                    .containsExactly("*base");
            assertThat(fake.writeJournal()).extracting(FakeRouterOsServer.CapturedWrite::method)
                    .containsExactly("POST", "PATCH");
            assertThat(fake.writeJournal()).allSatisfy(write ->
                    assertThat(write.body()).doesNotContain(password, "Authorization"));
        }
    }

    @Test
    void appliesConfigured401403And404ResponsesToTheStatefulCollection() {
        try (FakeRouterOsServer fake = statefulFake("[]")) {
            fake.respond(FIREWALL_FILTERS, 401, "{\"error\":401}");
            assertStatus(HttpStatus.UNAUTHORIZED, () -> client(fake.baseUri()).get()
                    .uri(FIREWALL_FILTERS)
                    .retrieve()
                    .toBodilessEntity());
        }

        try (FakeRouterOsServer fake = statefulFake("[]")) {
            fake.respond(FIREWALL_FILTERS, 403, "{\"error\":403}");
            assertStatus(HttpStatus.FORBIDDEN, () -> client(fake.baseUri()).put()
                    .uri(FIREWALL_FILTERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"action\":\"drop\"}")
                    .retrieve()
                    .toBodilessEntity());
        }

        try (FakeRouterOsServer fake = statefulFake("[]")) {
            fake.respond(FIREWALL_FILTERS, 404, "{\"error\":404}");
            assertStatus(HttpStatus.NOT_FOUND, () -> client(fake.baseUri()).get()
                    .uri(FIREWALL_FILTERS)
                    .retrieve()
                    .toBodilessEntity());
        }
    }

    private static FakeRouterOsServer statefulFake(String seed) {
        return FakeRouterOsServer.start()
                .enableStatefulFirewallFilters()
                .seedFirewallFilters(seed);
    }

    private static RestClient client(URI baseUri) {
        return client(baseUri, "test-user", "transport-test-password");
    }

    private static RestClient client(URI baseUri, String username, String password) {
        return RestClient.builder()
                .baseUrl(baseUri.toString())
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .build();
    }

    private static MikrotikProperties writableProperties(URI baseUri, String writePassword) {
        return new MikrotikProperties(
                "127.0.0.1",
                baseUri.getPort(),
                "read-user",
                "read-password",
                "write-user",
                writePassword,
                true,
                false,
                true,
                true,
                500,
                1_000
        );
    }

    private static List<String> filterIds(JsonNode collection) {
        if (collection == null || !collection.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(collection.spliterator(), false)
                .map(item -> item.path(".id").asText())
                .toList();
    }

    private static JsonNode filterWithComment(JsonNode collection, String comment) {
        return StreamSupport.stream(collection.spliterator(), false)
                .filter(item -> comment.equals(item.path("comment").asText()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertStatus(HttpStatus expected, Supplier<?> request) {
        if (expected.is2xxSuccessful()) {
            request.get();
            return;
        }
        assertThatThrownBy(request::get)
                .isInstanceOfSatisfying(HttpClientErrorException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(expected));
    }
}
