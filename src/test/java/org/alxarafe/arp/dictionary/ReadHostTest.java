package org.alxarafe.arp.dictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core publication regressions use only neutral ports: no JDBC, provider, network or Docker.
 */
class ReadHostTest
{
    private static final class CatalogPort implements ReadCatalog
    {
        private final CdsReadResult snapshot = new CdsReadResult(Map.of("valid", true, "projections", List.of(
                Map.of("id", "public", "operations", List.of("LIST", "GET"),
                       "fields", List.of(Map.of("name", "sku"))))));
        private boolean current = true;

        public CdsReadResult resolve()
        {
            return snapshot;
        }

        public Optional<CdsReadResult> current()
        {
            return current ? Optional.of(snapshot) : Optional.empty();
        }

        public long generation()
        {
            return 1;
        }

        public synchronized <T> T publishRead(CdsReadResult expected, Supplier<T> action)
        {
            if (!current || expected != snapshot) {
                throw new IllegalStateException();
            }
            return action.get();
        }
    }

    private static final class MemoryReader implements RecordReader
    {
        final List<String> events = new ArrayList<>();
        Runnable commit = () -> {
        };
        boolean failClose;

        public ReadPlan.Prepared prepare(CdsReadResult snapshot, String projection, Map<String, Object> equalities)
        {
            return new ReadPlan.Prepared(List.of(new ReadPlan.Column("sku", ScalarType.STRING, false)),
                                         List.of(new ReadPlan.Column("id", ScalarType.INT64, false)), equalities)
            {
            };
        }

        public ReadAttempt open(ReadPlan plan, java.util.function.BooleanSupplier current)
        {
            assertTrue(current.getAsBoolean());
            events.add("open");
            return new ReadAttempt()
            {
                public List<ReadPlan.Row> rows()
                {
                    return List.of(new ReadPlan.Row(List.of("1"), Map.of("sku", "item")));
                }

                public void complete()
                {
                    events.add("commit");
                    commit.run();
                }

                public void close()
                {
                    events.add("guard-close");
                    if (failClose) {
                        throw new ReadFailure("READ_FAILED");
                    }
                }
            };
        }
    }

    private static final ListGetHost.Context CONTEXT = new ListGetHost.Context("reader", "policy");
    private static final byte[] LIST = "{\"target\":\"catalog\",\"operation\":\"LIST\",\"inputs\":{}}".getBytes(
            java.nio.charset.StandardCharsets.UTF_8);
    private static final Map<String, ListGetHost.Target> ACTIVE = Map.of("catalog",
                                                                         new ListGetHost.Target("public",
                                                                                                2,
                                                                                                3,
                                                                                                Set.of("sku")));

    private static ListGetHost host(
            CatalogPort catalog, MemoryReader reader,
            java.util.concurrent.atomic.AtomicBoolean allow)
    {
        return new ListGetHost(catalog, reader, ACTIVE, (_, _, _) ->
                new ListGetHost.Access(allow.get(), Set.of("sku"), Map.of()));
    }

    private static void failure(Map<String, Object> response, String code)
    {
        assertFalse(response.containsKey("output"));
        assertTrue(JsonSupport.write(response).contains("CDS_LISTGET_" + code));
    }

    @Test
    void successReturnsOnlyAfterMandatoryCompletionAndClose()
    {
        var reader = new MemoryReader();
        var host = host(new CatalogPort(), reader, new java.util.concurrent.atomic.AtomicBoolean(true));
        var response = host.invoke(LIST, CONTEXT);
        reader.events.add("returned");
        assertEquals("success", response.get("status"));
        assertEquals(List.of("open", "commit", "guard-close", "returned"), reader.events);
    }

    @Test
    void completionAndCloseFailuresCannotPublish()
    {
        var reader = new MemoryReader();
        var host = host(new CatalogPort(), reader, new java.util.concurrent.atomic.AtomicBoolean(true));
        reader.commit = () -> {
            throw new ReadFailure("READ_FAILED");
        };
        failure(host.invoke(LIST, CONTEXT), "READ_FAILED");
        assertEquals(List.of("open", "commit", "guard-close"), reader.events);
        reader.commit = () -> {
        };
        reader.failClose = true;
        failure(host.invoke(LIST, CONTEXT), "READ_FAILED");
    }

    @Test
    void commitReentrantInvalidationCannotPublish()
    {
        var reader = new MemoryReader();
        var catalog = new CatalogPort();
        var host = host(catalog, reader, new java.util.concurrent.atomic.AtomicBoolean(true));
        reader.commit = () -> catalog.current = false;
        failure(host.invoke(LIST, CONTEXT), "GENERATION_CHANGED");
    }

    @Test
    void commitReentrantRevocationCannotPublish()
    {
        var reader = new MemoryReader();
        var allow = new java.util.concurrent.atomic.AtomicBoolean(true);
        var host = host(new CatalogPort(), reader, allow);
        reader.commit = () -> host.changeAccess(ACTIVE, () -> allow.set(false));
        failure(host.invoke(LIST, CONTEXT), "UNAVAILABLE");
    }
}