package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link EntityRedactor} builder and records (pure Java, no native dependency). */
class EntityRedactorTest {

    @Test
    void builderCreatesRedactorWithDefaults() {
        try (var redactor = EntityRedactor.builder()
                .addEntity("John Smith", "PERSON")
                .build()) {
            assertNotNull(redactor);
        }
    }

    @Test
    void builderAddMultipleEntities() {
        try (var redactor = EntityRedactor.builder()
                .addEntity("John Smith", "PERSON")
                .addEntity("Acme Corp", "ORGANIZATION")
                .addEntities(java.util.List.of("Jane Doe", "Bob Jones"), "PERSON")
                .build()) {
            assertNotNull(redactor);
        }
    }

    @Test
    void builderCoreferenceWindow() {
        try (var redactor = EntityRedactor.builder()
                .addEntity("test", "TEST")
                .coreferenceWindow(5)
                .build()) {
            assertNotNull(redactor);
        }
    }

    @Test
    void builderCustomPronouns() {
        try (var redactor = EntityRedactor.builder()
                .addEntity("test", "TEST")
                .setCoreferencePronouns(java.util.List.of("it", "that"))
                .addCoreferencePronouns("this")
                .build()) {
            assertNotNull(redactor);
        }
    }

    @Test
    void closeIsIdempotent() {
        var redactor = EntityRedactor.builder()
                .addEntity("test", "TEST")
                .build();
        redactor.close();
        redactor.close(); // second close should not throw
    }

    @Test
    void useAfterCloseThrows() {
        var redactor = EntityRedactor.builder()
                .addEntity("test", "TEST")
                .build();
        redactor.close();
        assertThrows(IllegalStateException.class, () -> redactor.analyze(null));
    }

    @Test
    void entityMatchRecord() {
        var match = new EntityRedactor.EntityMatch(0, 10, 20, "John Smith", "PERSON");
        assertEquals(0, match.pageIndex());
        assertEquals(10, match.start());
        assertEquals(20, match.end());
        assertEquals("John Smith", match.text());
        assertEquals("PERSON", match.label());
    }

    @Test
    void redactionTargetRecord() {
        var target = new EntityRedactor.RedactionTarget(1, 5, 15, "some text",
                EntityRedactor.RedactionReason.ENTITY_MATCH, "PERSON");
        assertEquals(1, target.pageIndex());
        assertEquals(5, target.start());
        assertEquals(15, target.end());
        assertEquals("some text", target.text());
        assertEquals(EntityRedactor.RedactionReason.ENTITY_MATCH, target.reason());
        assertEquals("PERSON", target.detail());
    }

    @Test
    void resultRecord() {
        var entities = java.util.List.of(
                new EntityRedactor.EntityMatch(0, 0, 10, "test", "TEST"));
        var targets = java.util.List.of(
                new EntityRedactor.RedactionTarget(0, 0, 10, "test",
                        EntityRedactor.RedactionReason.ENTITY_MATCH, "TEST"));
        var result = new EntityRedactor.Result(entities, targets);
        assertEquals(1, result.entityCount());
        assertEquals(1, result.targetCount());
    }

    @Test
    void redactionReasonEnumValues() {
        var values = EntityRedactor.RedactionReason.values();
        assertEquals(3, values.length);
        assertNotNull(EntityRedactor.RedactionReason.valueOf("ENTITY_MATCH"));
        assertNotNull(EntityRedactor.RedactionReason.valueOf("PATTERN_MATCH"));
        assertNotNull(EntityRedactor.RedactionReason.valueOf("COREFERENCE_CONTEXT"));
    }
}
