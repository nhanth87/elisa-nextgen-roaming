package et.elisa.iwf.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DialogBindingRegistryTest {

    private DialogBindingRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DialogBindingRegistry();
    }

    @Test
    void bindAndLookup() {
        registry.bind("4520402000000001", "sess-1", 1L, 100L);
        Optional<DialogBindingRegistry.Binding> b = registry.lookup("4520402000000001");
        assertTrue(b.isPresent());
        assertEquals("4520402000000001", b.orElseThrow().imsi());
        assertEquals("sess-1", b.orElseThrow().diameterSessionId());
        assertEquals(1L, b.orElseThrow().diameterHopByHopId());
        assertEquals(100L, b.orElseThrow().mapDialogId());
    }

    @Test
    void replaceExistingBinding() {
        registry.bind("4520402000000001", "sess-1", 1L, 100L);
        registry.bind("4520402000000001", "sess-2", 2L, 200L);
        var b = registry.lookup("4520402000000001").orElseThrow();
        assertEquals("sess-2", b.diameterSessionId());
        assertEquals(200L, b.mapDialogId());
    }

    @Test
    void unbindRemovesEntry() {
        registry.bind("4520402000000001", "sess-1", 1L, 100L);
        registry.unbind("4520402000000001");
        assertFalse(registry.lookup("4520402000000001").isPresent());
    }

    @Test
    void lookupReturnsEmptyForBlankImsi() {
        assertFalse(registry.lookup("").isPresent());
        assertFalse(registry.lookup(null).isPresent());
    }

    @Test
    void bindIgnoresBlankImsi() {
        registry.bind("", "sess-1", 1L, 100L);
        registry.bind(null, "sess-1", 1L, 100L);
        assertEquals(0, registry.size());
    }

    @Test
    void freshBindingIsNotExpired() {
        registry.bind("4520402000000001", "sess-1", 1L, 100L);
        var b = registry.lookup("4520402000000001").orElseThrow();
        assertFalse(b.expired(), "fresh binding should not be expired");
    }

    @Test
    void sizeCountsOnlyActiveBindings() {
        registry.bind("4520402000000001", "sess-1", 1L, 100L);
        registry.bind("4520402000000002", "sess-2", 2L, 200L);
        assertEquals(2, registry.size());
        registry.unbind("4520402000000001");
        assertEquals(1, registry.size());
    }

    @Test
    void bindingWithNullMapDialogIdIsAllowed() {
        registry.bind("4520402000000001", "sess-1", 1L, null);
        var b = registry.lookup("4520402000000001").orElseThrow();
        assertEquals(null, b.mapDialogId(), "map dialog id absent until MAP leg wired");
    }
}
