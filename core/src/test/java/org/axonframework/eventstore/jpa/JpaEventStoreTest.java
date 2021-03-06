/*
 * Copyright (c) 2010-2013. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventstore.jpa;

import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.DomainEventStream;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.eventstore.*;
import org.axonframework.eventstore.management.CriteriaBuilder;
import org.axonframework.messaging.metadata.MetaData;
import org.axonframework.commandhandling.model.ConcurrencyException;
import org.axonframework.serializer.*;
import org.axonframework.upcasting.LazyUpcasterChain;
import org.axonframework.upcasting.Upcaster;
import org.axonframework.upcasting.UpcasterChain;
import org.axonframework.upcasting.UpcastingContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/db-context.xml")
public class JpaEventStoreTest {

    private JpaEventStore testSubject;

    @PersistenceContext
    private EntityManager entityManager;

    private EntityManagerProvider entityManagerProvider;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager txManager;
    private TransactionTemplate template;

    @Before
    public void setUp() throws SQLException {
        entityManagerProvider = new SimpleEntityManagerProvider(entityManager);
        testSubject = new JpaEventStore(entityManagerProvider);
        testSubject.setDataSource(dataSource);

        template = new TransactionTemplate(txManager);
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                entityManager.createQuery("DELETE FROM DomainEventEntry").executeUpdate();
                entityManager.createQuery("DELETE FROM SnapshotEventEntry").executeUpdate();
            }
        });
    }

    @After
    public void tearDown() {
        // just to make sure
        setClock(Clock.systemDefaultZone());
    }

    @Test(expected = DataIntegrityViolationException.class)
    public void testUniqueKeyConstraintOnEventIdentifier() {
        final SimpleSerializedObject<byte[]> emptySerializedObject = new SimpleSerializedObject<>(new byte[]{},
                                                                                                  byte[].class,
                                                                                                  "test",
                                                                                                  "");

        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(
                    TransactionStatus status) {
                entityManager.persist(new DomainEventEntry(new GenericDomainEventMessage<>(
                        "a", Instant.now(), "someValue", 0, "",
                        MetaData.emptyInstance()), emptySerializedObject, emptySerializedObject));
            }
        });
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(
                    TransactionStatus status) {
                entityManager.persist(new DomainEventEntry(new GenericDomainEventMessage<>(
                        "a",
                        Instant.now(),
                        "anotherValue",
                        0,
                        "",
                        MetaData.emptyInstance()),
                                                           emptySerializedObject,
                                                           emptySerializedObject));
            }
        });
    }

    @Transactional
    @Test(expected = UnknownSerializedTypeException.class)
    public void testUnknownSerializedTypeCausesException() {
        testSubject.appendEvents(new GenericDomainEventMessage<>("id", 0, "payload"));
        entityManager.flush();
        entityManager.clear();
        entityManager.createQuery("UPDATE DomainEventEntry e SET e.payloadType = :type")
                     .setParameter("type", "unknown")
                     .executeUpdate();

        testSubject.readEvents("id");
    }

    @Transactional
    @Test
    public void testStoreAndLoadEvents() {
        assertNotNull(testSubject);
        testSubject.appendEvents(new GenericDomainEventMessage<>("id", 0, "payload"),
                                 new GenericDomainEventMessage<>("id", 1, "payload"),
                                 new GenericDomainEventMessage<>("id", 2, "payload"),
                                 new GenericDomainEventMessage<>("id", 3, "payload"));
        entityManager.flush();
        assertEquals(4L, entityManager.createQuery("SELECT count(e) FROM DomainEventEntry e").getSingleResult());

        // we store some more events to make sure only correct events are retrieved
        testSubject.appendEvents(
                new GenericDomainEventMessage<>("other", 0, "payload",
                                                singletonMap("key", (Object) "Value")));
        entityManager.flush();
        entityManager.clear();

        DomainEventStream events = testSubject.readEvents("id");
        List<DomainEventMessage> actualEvents = new ArrayList<>();
        while (events.hasNext()) {
            DomainEventMessage event = events.next();
            event.getPayload();
            event.getMetaData();
            actualEvents.add(event);
        }
        assertEquals(4, actualEvents.size());

        /// we make sure persisted events have the same MetaData alteration logic
        DomainEventStream other = testSubject.readEvents("other");
        assertTrue(other.hasNext());
        DomainEventMessage<?> messageWithMetaData = other.next();
        DomainEventMessage<?> altered = messageWithMetaData.withMetaData(singletonMap("key2", "value"));
        DomainEventMessage<?> combined = messageWithMetaData.andMetaData(singletonMap("key2", "value"));
        assertTrue(altered.getMetaData().containsKey("key2"));
        altered.getPayload();
        assertFalse(altered.getMetaData().containsKey("key"));
        assertTrue(altered.getMetaData().containsKey("key2"));
        assertTrue(combined.getMetaData().containsKey("key"));
        assertTrue(combined.getMetaData().containsKey("key2"));
        assertNotNull(messageWithMetaData.getPayload());
        assertNotNull(messageWithMetaData.getMetaData());
        assertFalse(messageWithMetaData.getMetaData().isEmpty());
    }

    @DirtiesContext
    @Test
    @Transactional
    public void testStoreAndLoadEvents_WithUpcaster() {
        assertNotNull(testSubject);
        UpcasterChain mockUpcasterChain = mock(UpcasterChain.class);
        when(mockUpcasterChain.upcast(isA(SerializedObject.class), isA(UpcastingContext.class)))
                .thenAnswer(invocation -> {
                    SerializedObject serializedObject = (SerializedObject) invocation.getArguments()[0];
                    return asList(serializedObject, serializedObject);
                });

        testSubject.appendEvents(new GenericDomainEventMessage<>("id", 0, "payload"),
                                 new GenericDomainEventMessage<>("id", 1, "payload"),
                                 new GenericDomainEventMessage<>("id", 2, "payload"),
                                 new GenericDomainEventMessage<>("id", 3, "payload"));

        testSubject.setUpcasterChain(mockUpcasterChain);
        entityManager.flush();
        assertEquals(4L, entityManager.createQuery("SELECT count(e) FROM DomainEventEntry e").getSingleResult());

        // we store some more events to make sure only correct events are retrieved
        testSubject.appendEvents(
                new GenericDomainEventMessage<>("other", 0, "payload",
                                                singletonMap("key", "Value")));
        entityManager.flush();
        entityManager.clear();

        DomainEventStream events = testSubject.readEvents("id");
        List<DomainEventMessage> actualEvents = new ArrayList<>();
        events.forEachRemaining(actualEvents::add);

        assertEquals(8, actualEvents.size());
        for (int t = 0; t < 8; t = t + 2) {
            assertEquals(actualEvents.get(t).getSequenceNumber(), actualEvents.get(t + 1).getSequenceNumber());
            assertEquals(actualEvents.get(t).getAggregateIdentifier(),
                         actualEvents.get(t + 1).getAggregateIdentifier());
            assertEquals(actualEvents.get(t).getMetaData(), actualEvents.get(t + 1).getMetaData());
            assertNotNull(actualEvents.get(t).getPayload());
            assertNotNull(actualEvents.get(t + 1).getPayload());
        }
    }

    @Test
    @Transactional
    public void testLoad_LargeAmountOfEvents() {
        DomainEventMessage[] domainEvents = new DomainEventMessage[110];
        String aggregateIdentifier = "id";
        for (int t = 0; t < 110; t++) {
            domainEvents[t] = new GenericDomainEventMessage<>(aggregateIdentifier, (long) t,
                                                              "Mock contents", MetaData.emptyInstance());
        }
        testSubject.appendEvents(asList(domainEvents));
        entityManager.flush();
        entityManager.clear();

        DomainEventStream events = testSubject.readEvents(aggregateIdentifier);
        long t = 0L;
        while (events.hasNext()) {
            DomainEventMessage event = events.next();
            assertEquals(t, event.getSequenceNumber());
            t++;
        }
        assertEquals(110L, t);
    }

    @DirtiesContext
    @Test
    @Transactional
    public void testLoad_LargeAmountOfEventsInSmallBatches() {
        testSubject.setBatchSize(10);
        testLoad_LargeAmountOfEvents();
    }

    @Test
    @Transactional
    public void testEntireStreamIsReadOnUnserializableSnapshot_WithException() {
        DomainEventMessage[] domainEvents = new DomainEventMessage[110];
        String aggregateIdentifier = "id";
        for (int t = 0; t < 110; t++) {
            domainEvents[t] = new GenericDomainEventMessage<>(aggregateIdentifier, (long) t,
                                                              "Mock contents", MetaData.emptyInstance());
        }
        testSubject.appendEvents(asList(domainEvents));
        final Serializer serializer = new Serializer() {

            private ChainingConverterFactory converterFactory = new ChainingConverterFactory();

            @SuppressWarnings("unchecked")
            @Override
            public <T> SerializedObject<T> serialize(Object object, Class<T> expectedType) {
                Assert.assertEquals(byte[].class, expectedType);
                return new SimpleSerializedObject("this ain't gonna work".getBytes(), byte[].class, "failingType", "0");
            }

            @Override
            public <T> boolean canSerializeTo(Class<T> expectedRepresentation) {
                return byte[].class.equals(expectedRepresentation);
            }

            @Override
            public <S, T> T deserialize(SerializedObject<S> serializedObject) {
                throw new UnsupportedOperationException("Not implemented yet");
            }

            @Override
            public Class classForType(SerializedType type) {
                try {
                    return Class.forName(type.getName());
                } catch (ClassNotFoundException e) {
                    return null;
                }
            }

            @Override
            public SerializedType typeForClass(Class type) {
                return new SimpleSerializedType(type.getName(), "");
            }

            @Override
            public ConverterFactory getConverterFactory() {
                return converterFactory;
            }
        };
        final DomainEventMessage<String> stubDomainEvent = new GenericDomainEventMessage<>(
                aggregateIdentifier,
                (long) 30,
                "Mock contents", MetaData.emptyInstance()
        );
        SnapshotEventEntry entry = new SnapshotEventEntry(
                stubDomainEvent,
                serializer.serialize(stubDomainEvent.getPayload(), byte[].class),
                serializer.serialize(stubDomainEvent.getMetaData(), byte[].class));
        entityManager.persist(entry);
        entityManager.flush();
        entityManager.clear();

        DomainEventStream stream = testSubject.readEvents(aggregateIdentifier);
        assertEquals(0L, stream.peek().getSequenceNumber());
    }

    @Test
    @Transactional
    public void testEntireStreamIsReadOnUnserializableSnapshot_WithError() {
        DomainEventMessage[] domainEvents = new DomainEventMessage[110];
        String aggregateIdentifier = "id";
        for (int t = 0; t < 110; t++) {
            domainEvents[t] = new GenericDomainEventMessage<>(aggregateIdentifier, (long) t,
                                                              "Mock contents", MetaData.emptyInstance());
        }
        testSubject.appendEvents(asList(domainEvents));
        final Serializer serializer = new Serializer() {

            private ConverterFactory converterFactory = new ChainingConverterFactory();

            @SuppressWarnings("unchecked")
            @Override
            public <T> SerializedObject<T> serialize(Object object, Class<T> expectedType) {
                // this will cause InstantiationError, since it is an interface
                Assert.assertEquals(byte[].class, expectedType);
                return new SimpleSerializedObject("<org.axonframework.eventhandling.EventListener />".getBytes(),
                                                  byte[].class,
                                                  "failingType",
                                                  "0");
            }

            @Override
            public <T> boolean canSerializeTo(Class<T> expectedRepresentation) {
                return byte[].class.equals(expectedRepresentation);
            }

            @Override
            public <S, T> T deserialize(SerializedObject<S> serializedObject) {
                throw new UnsupportedOperationException("Not implemented yet");
            }

            @Override
            public Class classForType(SerializedType type) {
                try {
                    return Class.forName(type.getName());
                } catch (ClassNotFoundException e) {
                    return null;
                }
            }

            @Override
            public SerializedType typeForClass(Class type) {
                return new SimpleSerializedType(type.getName(), "");
            }

            @Override
            public ConverterFactory getConverterFactory() {
                return converterFactory;
            }
        };
        final DomainEventMessage<String> stubDomainEvent = new GenericDomainEventMessage<>(
                aggregateIdentifier,
                (long) 30,
                "Mock contents", MetaData.emptyInstance()
        );
        SnapshotEventEntry entry = new SnapshotEventEntry(
                stubDomainEvent,
                serializer.serialize(stubDomainEvent.getPayload(), byte[].class),
                serializer.serialize(stubDomainEvent.getMetaData(), byte[].class));
        entityManager.persist(entry);
        entityManager.flush();
        entityManager.clear();

        DomainEventStream stream = testSubject.readEvents(aggregateIdentifier);
        assertEquals(0L, stream.peek().getSequenceNumber());
    }

    @Test
    @Transactional
    public void testLoad_LargeAmountOfEventsWithSnapshot() {
        DomainEventMessage[] domainEvents = new DomainEventMessage[110];
        String aggregateIdentifier = "id";
        for (int t = 0; t < 110; t++) {
            domainEvents[t] = new GenericDomainEventMessage<>(aggregateIdentifier, (long) t,
                                                              "Mock contents", MetaData.emptyInstance());
        }
        testSubject.appendEvents(asList(domainEvents));
        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>(aggregateIdentifier, (long) 30,
                                                                        "Mock contents",
                                                                        MetaData.emptyInstance()
        ));
        entityManager.flush();
        entityManager.clear();

        DomainEventStream events = testSubject.readEvents(aggregateIdentifier);
        long t = 30L;
        while (events.hasNext()) {
            DomainEventMessage event = events.next();
            assertEquals(t, event.getSequenceNumber());
            t++;
        }
        assertEquals(110L, t);
    }

    @Test
    @Transactional
    public void testLoadWithSnapshotEvent() {
        testSubject.appendEvents(new GenericDomainEventMessage<>("id", 0, "payload"),
                                 new GenericDomainEventMessage<>("id", 1, "payload"),
                                 new GenericDomainEventMessage<>("id", 2, "payload"),
                                 new GenericDomainEventMessage<>("id", 3, "payload"));
        entityManager.flush();
        entityManager.clear();
        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>("id", 3, "snapshot"));
        entityManager.flush();
        entityManager.clear();
        testSubject.appendEvents(new GenericDomainEventMessage<>("id", 4, "payload"));

        DomainEventStream actualEventStream = testSubject.readEvents("id");
        List<DomainEventMessage> domainEvents = new ArrayList<>();
        while (actualEventStream.hasNext()) {
            DomainEventMessage next = actualEventStream.next();
            domainEvents.add(next);
            assertEquals("id", next.getAggregateIdentifier());
        }

        assertEquals(2, domainEvents.size());
    }

    @Test(expected = EventStreamNotFoundException.class)
    @Transactional
    public void testLoadNonExistent() {
        testSubject.readEvents(UUID.randomUUID().toString());
    }

    @Transactional
    @Test
    public void testInsertDuplicateSnapshot() throws Exception {
        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>("id1", 1, "test"));
        try {
            testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>("id1", 1, "test"));
            fail("Expected concurrency exception");
        } catch (ConcurrencyException e) {
            assertTrue(e.getMessage().contains("snapshot"));
        }
    }

    @Test
    @Transactional
    public void testVisitAllEvents() {
        EventVisitor eventVisitor = mock(EventVisitor.class);
        testSubject.appendEvents(createDomainEvents(77));
        testSubject.appendEvents(createDomainEvents(23));

        testSubject.visitEvents(eventVisitor);
        verify(eventVisitor, times(100)).doWithEvent(isA(DomainEventMessage.class));
    }

    @Test
    @Transactional
    public void testVisitAllEvents_IncludesUnknownEventType() throws Exception {
        EventVisitor eventVisitor = mock(EventVisitor.class);
        testSubject.appendEvents(createDomainEvents(10));
        testSubject.appendEvents(asList(new GenericDomainEventMessage<>("test", 0, "test")));
        testSubject.appendEvents(createDomainEvents(10));
        // we upcast the event to two instances, one of which is an unknown class
        testSubject.setUpcasterChain(new LazyUpcasterChain(Arrays.<Upcaster>asList(new StubUpcaster())));
        testSubject.visitEvents(eventVisitor);

        verify(eventVisitor, times(21)).doWithEvent(isA(DomainEventMessage.class));
    }

    @Test
    @Transactional
    public void testVisitEvents_AfterTimestamp() {
        EventVisitor eventVisitor = mock(EventVisitor.class);
        setClock(ZonedDateTime.of(2011, 12, 18, 12, 59, 59, 999000000, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(11));
        ZonedDateTime onePM = ZonedDateTime.of(2011, 12, 18, 13, 0, 0, 0, ZoneOffset.UTC);
        setClock(onePM);
        testSubject.appendEvents(createDomainEvents(12));
        setClock(ZonedDateTime.of(2011, 12, 18, 14, 0, 0, 0, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(13));
        setClock(ZonedDateTime.of(2011, 12, 18, 14, 0, 0, 1000000, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(14));
        setClock(Clock.systemDefaultZone());

        CriteriaBuilder criteriaBuilder = testSubject.newCriteriaBuilder();
        testSubject.visitEvents(criteriaBuilder.property("timeStamp").greaterThan(onePM), eventVisitor);
        verify(eventVisitor, times(13 + 14)).doWithEvent(isA(DomainEventMessage.class));
    }

    @Test
    @Transactional
    public void testVisitEvents_BetweenTimestamps() {
        EventVisitor eventVisitor = mock(EventVisitor.class);
        setClock(ZonedDateTime.of(2011, 12, 18, 12, 59, 59, 999000000, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(11));
        ZonedDateTime onePM = ZonedDateTime.of(2011, 12, 18, 13, 0, 0, 0, ZoneOffset.UTC);
        setClock(onePM);
        testSubject.appendEvents(createDomainEvents(12));
        ZonedDateTime twoPM = ZonedDateTime.of(2011, 12, 18, 14, 0, 0, 0, ZoneOffset.UTC);
        setClock(twoPM);
        testSubject.appendEvents(createDomainEvents(13));
        setClock(ZonedDateTime.of(2011, 12, 18, 14, 0, 0, 1000000, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(14));
        setClock(Clock.systemDefaultZone());

        CriteriaBuilder criteriaBuilder = testSubject.newCriteriaBuilder();
        testSubject.visitEvents(criteriaBuilder.property("timeStamp").greaterThanEquals(onePM)
                                               .and(criteriaBuilder.property("timeStamp").lessThanEquals(twoPM)),
                                eventVisitor);
        verify(eventVisitor, times(12 + 13)).doWithEvent(isA(DomainEventMessage.class));
    }

    @Test
    @Transactional
    public void testVisitEvents_OnOrAfterTimestamp() {
        EventVisitor eventVisitor = mock(EventVisitor.class);
        setClock(ZonedDateTime.of(2011, 12, 18, 12, 59, 59, 999000000, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(11));
        ZonedDateTime onePM = ZonedDateTime.of(2011, 12, 18, 13, 0, 0, 0, ZoneOffset.UTC);
        setClock(onePM);
        testSubject.appendEvents(createDomainEvents(12));
        setClock(ZonedDateTime.of(2011, 12, 18, 14, 0, 0, 0, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(13));
        setClock(ZonedDateTime.of(2011, 12, 18, 14, 0, 0, 1000000, ZoneOffset.UTC));
        testSubject.appendEvents(createDomainEvents(14));
        setClock(Clock.systemDefaultZone());

        CriteriaBuilder criteriaBuilder = testSubject.newCriteriaBuilder();
        testSubject.visitEvents(criteriaBuilder.property("timeStamp").greaterThanEquals(onePM), eventVisitor);
        verify(eventVisitor, times(12 + 13 + 14)).doWithEvent(isA(DomainEventMessage.class));
    }

    @Test(expected = ConcurrencyException.class)
    @Transactional
    public void testStoreDuplicateEvent_WithSqlExceptionTranslator() {
        testSubject.appendEvents(asList(new GenericDomainEventMessage<>("123",
                                                                        0L,
                                                                        "Mock contents",
                                                                        MetaData.emptyInstance())));
        entityManager.flush();
        entityManager.clear();
        testSubject.appendEvents(asList(new GenericDomainEventMessage<>("123", 0L,
                                                                        "Mock contents", MetaData.emptyInstance())));
    }

    @DirtiesContext
    @Test
    @Transactional
    public void testStoreDuplicateEvent_NoSqlExceptionTranslator() {
        testSubject.setPersistenceExceptionResolver(null);
        try {
            testSubject.appendEvents(asList(
                    new GenericDomainEventMessage<>("123", (long) 0,
                                                    "Mock contents", MetaData.emptyInstance())));
            entityManager.flush();
            entityManager.clear();
            testSubject.appendEvents(asList(
                    new GenericDomainEventMessage<>("123", (long) 0,
                                                    "Mock contents", MetaData.emptyInstance())));
        } catch (ConcurrencyException ex) {
            fail("Didn't expect exception to be translated");
        } catch (Exception ex) {
            final StringWriter writer = new StringWriter();
            ex.printStackTrace(new PrintWriter(writer));
            assertTrue("Got the right exception, "
                               + "but the message doesn't seem to mention 'DomainEventEntry': " + ex.getMessage(),
                       writer.toString().toLowerCase().contains("domainevententry"));
        }
    }

    @DirtiesContext
    @Test
    @Transactional
    public void testPrunesSnapshotsWhenNumberOfSnapshotsExceedsConfiguredMaxSnapshotsArchived() {
        testSubject.setMaxSnapshotsArchived(1);

        testSubject.appendEvents(new GenericDomainEventMessage<>("id", 0, "payload"),
                                 new GenericDomainEventMessage<>("id", 1, "payload"),
                                 new GenericDomainEventMessage<>("id", 2, "payload"),
                                 new GenericDomainEventMessage<>("id", 3, "payload"));
        entityManager.flush();
        entityManager.clear();

        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>("id", 3, "snapshot"));
        entityManager.flush();
        entityManager.clear();

        testSubject.appendEvents(new GenericDomainEventMessage<>("id", 4, "payload"));
        entityManager.flush();
        entityManager.clear();

        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>("id", 4, "snapshot"));
        entityManager.flush();
        entityManager.clear();

        @SuppressWarnings({"unchecked"})
        List<SnapshotEventEntry> snapshots =
                entityManager.createQuery("SELECT e FROM SnapshotEventEntry e "
                                                  + "WHERE e.aggregateIdentifier = :aggregateIdentifier")
                             .setParameter("aggregateIdentifier", "id")
                             .getResultList();
        assertEquals("archived snapshot count", 1L, snapshots.size());
        assertEquals("archived snapshot sequence", 4L, snapshots.iterator().next().getSequenceNumber());
    }

    @SuppressWarnings({"PrimitiveArrayArgumentToVariableArgMethod", "unchecked"})
    @DirtiesContext
    @Test
    @Transactional
    public void testCustomEventEntryStore() {
        EventEntryStore eventEntryStore = mock(EventEntryStore.class);
        when(eventEntryStore.getDataType()).thenReturn(byte[].class);
        testSubject = new JpaEventStore(new SimpleEntityManagerProvider(entityManager), eventEntryStore);
        testSubject.appendEvents(asList(
                new GenericDomainEventMessage<>(UUID.randomUUID().toString(), (long) 0,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(UUID.randomUUID().toString(), (long) 0,
                                                "Mock contents", MetaData.emptyInstance())));
        verify(eventEntryStore, times(2)).persistEvent(isA(DomainEventMessage.class),
                                                       Matchers.<SerializedObject>any(),
                                                       Matchers.<SerializedObject>any(), same(entityManager));

        reset(eventEntryStore);
        GenericDomainEventMessage<String> eventMessage = new GenericDomainEventMessage<>(
                UUID.randomUUID().toString(), 0L, "Mock contents", MetaData.emptyInstance());
        when(eventEntryStore.fetchAggregateStream(any(), anyInt(), anyInt(),
                                                  any(EntityManager.class)))
                .thenReturn(new ArrayList(asList(new DomainEventEntry(
                        eventMessage,
                        mockSerializedObject("Mock contents".getBytes()),
                        mockSerializedObject("Mock contents".getBytes())))).iterator());
        when(eventEntryStore.loadLastSnapshotEvent(any(),
                                                   any(EntityManager.class)))
                .thenReturn(null);

        testSubject.readEvents("1");

        verify(eventEntryStore).fetchAggregateStream("1", 0, 100, entityManager);
        verify(eventEntryStore).loadLastSnapshotEvent("1", entityManager);
    }

    @Test
    @Transactional
    public void testReadPartialStream_WithoutEnd() {
        final String aggregateIdentifier = UUID.randomUUID().toString();
        testSubject.appendEvents(asList(
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 0,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 1,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 2,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 3,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 4,
                                                "Mock contents", MetaData.emptyInstance())));
        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>(aggregateIdentifier,
                                                                        (long) 3,
                                                                        "Mock contents",
                                                                        MetaData.emptyInstance()));

        entityManager.flush();
        entityManager.clear();

        DomainEventStream actual = testSubject.readEvents(aggregateIdentifier, 2);
        for (int i = 2; i <= 4; i++) {
            assertTrue(actual.hasNext());
            assertEquals(i, actual.next().getSequenceNumber());
        }
        assertFalse(actual.hasNext());
    }

    @Test
    @Transactional
    public void testReadPartialStream_WithEnd() {
        final String aggregateIdentifier = UUID.randomUUID().toString();
        testSubject.appendEvents(asList(
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 0,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 1,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 2,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 3,
                                                "Mock contents", MetaData.emptyInstance()),
                new GenericDomainEventMessage<>(aggregateIdentifier, (long) 4,
                                                "Mock contents", MetaData.emptyInstance())));

        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>(aggregateIdentifier,
                                                                        (long) 3,
                                                                        "Mock contents",
                                                                        MetaData.emptyInstance()));

        entityManager.flush();
        entityManager.clear();

        DomainEventStream actual = testSubject.readEvents(aggregateIdentifier, 2, 3);
        for (int i = 2; i <= 3; i++) {
            assertTrue(actual.hasNext());
            assertEquals(i, actual.next().getSequenceNumber());
        }
        assertFalse(actual.hasNext());
    }

    private SerializedObject<byte[]> mockSerializedObject(byte[] bytes) {
        return new SimpleSerializedObject<>(bytes, byte[].class, "java.lang.String", "0");
    }

    @SuppressWarnings("JpaQlInspection")
    @Transactional
    @Test
    public void testStoreEventsWithCustomEntity() throws Exception {
        testSubject = new JpaEventStore(entityManagerProvider,
                                        new DefaultEventEntryStore<>(new EventEntryFactory<String>() {
                                            @Override
                                            public Class<String> getDataType() {
                                                return String.class;
                                            }


                                            @Override
                                            public Object createDomainEventEntry(DomainEventMessage event,
                                                                                 SerializedObject<String> serializedPayload,
                                                                                 SerializedObject<String> serializedMetaData) {
                                                return new CustomDomainEventEntry(event,
                                                                                  event.getTimestamp(),
                                                                                  serializedPayload,
                                                                                  serializedMetaData);
                                            }

                                            @Override
                                            public Object createSnapshotEventEntry(DomainEventMessage snapshotEvent,
                                                                                   SerializedObject<String> serializedPayload,
                                                                                   SerializedObject<String> serializedMetaData) {
                                                return new CustomSnapshotEventEntry(snapshotEvent,
                                                                                    snapshotEvent.getTimestamp(),
                                                                                    serializedPayload,
                                                                                    serializedMetaData);
                                            }

                                            @Override
                                            public String getDomainEventEntryEntityName() {
                                                return "CustomDomainEventEntry";
                                            }

                                            @Override
                                            public String getSnapshotEventEntryEntityName() {
                                                return "CustomSnapshotEventEntry";
                                            }

                                            @Override
                                            public long resolveDateTimeValue(TemporalAccessor dateTime) {
                                                return Instant.from(dateTime).toEpochMilli();
                                            }
                                        }));

        testSubject.appendEvents(asList(new GenericDomainEventMessage<>("id1", 1L, "Payload")));
        testSubject.appendEvents(asList(new GenericDomainEventMessage<>("id1", 2L, "Payload2")));
        testSubject.appendSnapshotEvent(new GenericDomainEventMessage<>("id1", 1L, "Snapshot1"));

        entityManager.flush();
        entityManager.clear();

        List<CustomDomainEventEntry> list = entityManager.createQuery("SELECT e FROM CustomDomainEventEntry e",
                                                                      CustomDomainEventEntry.class)
                                                         .getResultList();
        assertEquals(2, list.size());

        DomainEventStream eventStream = testSubject.readEvents("id1");
        assertTrue(eventStream.hasNext());
        assertEquals("Snapshot1", eventStream.next().getPayload());
        assertEquals("Payload2", eventStream.next().getPayload());
        assertFalse(eventStream.hasNext());
    }


    private List<DomainEventMessage<?>> createDomainEvents(int numberOfEvents) {
        List<DomainEventMessage<?>> events = new ArrayList<>(numberOfEvents);
        final String aggregateIdentifier = UUID.randomUUID().toString();
        for (int t = 0; t < numberOfEvents; t++) {
            events.add(new GenericDomainEventMessage<>(
                    aggregateIdentifier,
                    t,
                    new StubStateChangedEvent(), MetaData.emptyInstance()
            ));
        }
        return events;
    }

    private void setClock(ZonedDateTime zonedDateTime) {
        setClock(Clock.fixed(zonedDateTime.toInstant(), zonedDateTime.getZone()));
    }
    private void setClock(Clock clock) {
        GenericEventMessage.clock = clock;
    }

}
