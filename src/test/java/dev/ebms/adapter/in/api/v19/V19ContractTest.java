package dev.ebms.adapter.in.api.v19;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds our v19 DTOs against the copy of the contract the clients are generated from,
 * {@code docs/compat/client-ebms-api-v18.yml}.
 *
 * <p>Our own tests assert the JSON we produce, but nothing in them would notice if the contract
 * itself said something different -- the clients deserialise into generated
 * {@code nl.clockwork.ebms.model.*} types, so a renamed or dropped field is a production
 * failure in another repository. This test is the one place that compares the two.
 *
 * <p>Field sets are compared for equality rather than containment. A field we stop sending
 * breaks a client; a field we invent is dead weight the client ignores. Either is worth a
 * failure and a decision.
 */
class V19ContractTest {

    private static final Path SPEC = Path.of("docs/compat/client-ebms-api-v18.yml");

    private static Map<String, Object> schemas;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadSpec() throws Exception {
        assertThat(Files.exists(SPEC))
                .as("vendored client contract at %s -- see docs/compat/README.md", SPEC)
                .isTrue();
        Map<String, Object> spec = YAMLMapper.builder().build()
                .readValue(Files.readString(SPEC), Map.class);
        schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
    }

    @Test
    void message_matchesTheContract() {
        assertThat(fieldsOf(V19Message.class))
                .isEqualTo(propertiesOf("Message"));
    }

    @Test
    void messageProperties_matchTheContract() {
        assertThat(fieldsOf(V19Message.MessageProperties.class))
                .isEqualTo(propertiesOf("MessageProperties"));
    }

    @Test
    void party_matchesTheContract() {
        // Note what is absent: the contract's Party has no field for a party id type, so
        // Party.partyIdType does not survive this representation.
        assertThat(fieldsOf(V19Message.Party.class))
                .isEqualTo(propertiesOf("Party"));
    }

    @Test
    void responseDataSource_matchesTheContract() {
        assertThat(fieldsOf(V19Message.DataSource.class))
                .isEqualTo(propertiesOf("DataSource"));
    }

    @Test
    void messageRequest_matchesTheContract() {
        assertThat(fieldsOf(V19MessageRequest.class))
                .isEqualTo(propertiesOf("MessageRequest"));
    }

    @Test
    void messageRequestProperties_matchTheContract() {
        assertThat(fieldsOf(V19MessageRequest.MessageRequestProperties.class))
                .isEqualTo(propertiesOf("MessageRequestProperties"));
    }

    @Test
    void requestDataSource_matchesTheContract() {
        assertThat(fieldsOf(V19MessageRequest.DataSource.class))
                .isEqualTo(propertiesOf("DataSource"));
    }

    @Test
    void messageStatus_enumMatchesTheContractExactly() {
        assertThat(Stream.of(V19MessageStatus.values()).map(Enum::name).toList())
                .isEqualTo(enumOf("MessageStatus"));
    }

    @Test
    void messageStatus_isAStringNotAnObject() {
        // ebms-core 2.20.x models this as {timestamp, status}. The clients' copy does not, and
        // theirs is the one that has to deserialise.
        assertThat(schema("MessageStatus")).containsEntry("type", "string");
    }

    @Test
    void requiredPropertiesWeMustSendArePresent() {
        // The contract's own MessageProperties.required lists fromPartyId and fromRole, which
        // are not among its properties -- they belong to MessageRequestProperties. An upstream
        // slip, so only the required names that are really properties are checked.
        Set<String> required = Set.copyOf(requiredOf("MessageProperties"));
        Set<String> declared = propertiesOf("MessageProperties");
        Set<String> realRequired = required.stream().filter(declared::contains)
                .collect(Collectors.toSet());

        assertThat(fieldsOf(V19Message.MessageProperties.class)).containsAll(realRequired);
        assertThat(required).isNotEqualTo(realRequired);
    }

    private static Set<String> fieldsOf(Class<?> record) {
        return Stream.of(record.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        assertThat(schema).as("schema %s in the vendored contract", name).isNotNull();
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> propertiesOf(String schemaName) {
        return ((Map<String, Object>) schema(schemaName).get("properties")).keySet();
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(String schemaName) {
        return (List<String>) schema(schemaName).get("required");
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumOf(String schemaName) {
        return (List<String>) schema(schemaName).get("enum");
    }
}
