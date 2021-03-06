/*
 * Copyright (c) 2010-2014. Axon Framework
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

package org.axonframework.saga;

import org.axonframework.common.Assert;
import org.axonframework.common.lock.Lock;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.common.lock.PessimisticLockFactory;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Abstract implementation of the SagaManager interface that provides basic functionality required by most SagaManager
 * implementations. Provides support for Saga lifecycle management and asynchronous handling of events.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public abstract class AbstractSagaManager implements SagaManager {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSagaManager.class);

    private final SagaRepository sagaRepository;
    private final SagaFactory sagaFactory;
    private final Class<? extends Saga>[] sagaTypes;
    private final LockFactory lockFactory = new PessimisticLockFactory();
    private final Map<String, Saga> sagasInCreation = new ConcurrentHashMap<>();
    private volatile boolean suppressExceptions = true;
    private volatile boolean synchronizeSagaAccess = true;

    /**
     * Initializes the SagaManager with the given <code>sagaRepository</code>.
     *
     * @param sagaRepository The repository providing the saga instances.
     * @param sagaFactory    The factory providing new saga instances
     * @param sagaTypes      The types of Saga supported by this Saga Manager
     */
    @SafeVarargs
    public AbstractSagaManager(SagaRepository sagaRepository, SagaFactory sagaFactory,
                               Class<? extends Saga>... sagaTypes) {
        Assert.notNull(sagaRepository, "sagaRepository may not be null");
        Assert.notNull(sagaFactory, "sagaFactory may not be null");
        this.sagaRepository = sagaRepository;
        this.sagaFactory = sagaFactory;
        this.sagaTypes = sagaTypes;
    }

    @Override
    public void handle(final EventMessage event) throws Exception{
        for (Class<? extends Saga> sagaType : sagaTypes) {
            Collection<AssociationValue> associationValues = extractAssociationValues(sagaType, event);
            if (associationValues != null && !associationValues.isEmpty()) {
                boolean sagaOfTypeInvoked = invokeExistingSagas(event, sagaType, associationValues);
                SagaInitializationPolicy initializationPolicy = getSagaCreationPolicy(sagaType, event);
                if (initializationPolicy.getCreationPolicy() == SagaCreationPolicy.ALWAYS
                        || (!sagaOfTypeInvoked
                        && initializationPolicy.getCreationPolicy() == SagaCreationPolicy.IF_NONE_FOUND)) {
                    startNewSaga(event, sagaType, initializationPolicy.getInitialAssociationValue());
                }
            }
        }
    }

    private boolean invokeExistingSagas(EventMessage event, Class<? extends Saga> sagaType,
                                        Collection<AssociationValue> associationValues) throws Exception {
        Set<String> sagas = new TreeSet<>();
        for (AssociationValue associationValue : associationValues) {
            sagas.addAll(sagaRepository.find(sagaType, associationValue));
        }
        sagas.addAll(sagasInCreation.values().stream()
                .filter(sagaInCreation -> sagaType.isInstance(sagaInCreation)
                        && containsAny(sagaInCreation.getAssociationValues(), associationValues))
                .map(Saga::getSagaIdentifier).collect(Collectors.toList()));
        boolean sagaOfTypeInvoked = false;
        for (final String sagaId : sagas) {
            if (synchronizeSagaAccess) {
                Lock lock = lockFactory.obtainLock(sagaId);
                Saga invokedSaga = null;
                try {
                    invokedSaga = loadAndInvoke(event, sagaId, associationValues);
                    if (invokedSaga != null) {
                        sagaOfTypeInvoked = true;
                    }
                } finally {
                    releaseLock(lock, invokedSaga);
                }
            } else {
                loadAndInvoke(event, sagaId, associationValues);
            }
        }
        return sagaOfTypeInvoked;
    }

    private boolean containsAny(AssociationValues associationValues, Collection<AssociationValue> toFind) {
        for (AssociationValue valueToFind : toFind) {
            if (associationValues.contains(valueToFind)) {
                return true;
            }
        }
        return false;
    }

    private void startNewSaga(EventMessage event, Class<? extends Saga> sagaType, AssociationValue associationValue) throws Exception {
        Saga newSaga = sagaFactory.createSaga(sagaType);
        newSaga.getAssociationValues().add(associationValue);
        preProcessSaga(newSaga);
        sagasInCreation.put(newSaga.getSagaIdentifier(), newSaga);
        try {
            if (synchronizeSagaAccess) {
                Lock lock = lockFactory.obtainLock(newSaga.getSagaIdentifier());
                try {
                    doInvokeSaga(event, newSaga);
                } finally {
                    try {
                        sagaRepository.add(newSaga);
                    } finally {
                        releaseLock(lock, newSaga);
                    }
                }
            } else {
                try {
                    doInvokeSaga(event, newSaga);
                } finally {
                    sagaRepository.add(newSaga);
                }
            }
        } finally {
            removeEntry(newSaga.getSagaIdentifier(), sagasInCreation);
        }
    }

    private void releaseLock(Lock lock, Saga sagaInstance) {
        if (sagaInstance == null || !CurrentUnitOfWork.isStarted()) {
            lock.release();
        } else if (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().onCleanup(u -> lock.release());
        }
    }

    private void removeEntry(final String sagaIdentifier, final Map<String, ?> sagaMap) {
        if (!CurrentUnitOfWork.isStarted()) {
            sagaMap.remove(sagaIdentifier);
        } else {
            CurrentUnitOfWork.get().afterCommit(u -> sagaMap.remove(sagaIdentifier));
        }
    }

    /**
     * Returns the Saga Initialization Policy for a Saga of the given <code>sagaType</code> and <code>event</code>.
     * This policy provides the conditions to create new Saga instance, as well as the initial association of that
     * saga.
     *
     * @param sagaType The type of Saga to get the creation policy for
     * @param event    The Event that is being dispatched to Saga instances
     * @return the initialization policy for the Saga
     */
    protected abstract SagaInitializationPolicy getSagaCreationPolicy(Class<? extends Saga> sagaType,
                                                                      EventMessage event);

    /**
     * Extracts the AssociationValues from the given <code>event</code> as relevant for a Saga of given
     * <code>sagaType</code>. A single event may be associated with multiple values.
     *
     * @param sagaType The type of Saga about to handle the Event
     * @param event    The event containing the association information
     * @return the AssociationValues indicating which Sagas should handle given event
     */
    protected abstract Set<AssociationValue> extractAssociationValues(Class<? extends Saga> sagaType,
                                                                      EventMessage event);

    private Saga loadAndInvoke(EventMessage event, String sagaId, Collection<AssociationValue> associations) throws Exception {
        Saga saga = sagasInCreation.get(sagaId);
        if (saga == null) {
            saga = sagaRepository.load(sagaId);
        }

        if (saga == null || !saga.isActive() || !containsAny(saga.getAssociationValues(), associations)) {
            return null;
        }
        preProcessSaga(saga);
        try {
            doInvokeSaga(event, saga);
        } finally {
            commit(saga);
        }
        return saga;
    }

    /**
     * Perform pre-processing of sagas that have been newly created or have been loaded from the repository. This
     * method is invoked prior to invocation of the saga instance itself.
     *
     * @param saga The saga instance for pre-processing
     */
    protected void preProcessSaga(Saga saga) {
    }

    private void doInvokeSaga(EventMessage event, Saga saga) throws Exception {
        try {
            saga.handle(event);
        } catch (Exception e) {
            if (suppressExceptions) {
                logger.error(format("An exception occurred while a Saga [%s] was handling an Event [%s]:",
                                    saga.getClass().getSimpleName(),
                                    event.getPayloadType().getSimpleName()),
                             e);
            } else {
                throw e;
            }
        }
    }

    /**
     * Commits the given <code>saga</code> to the registered repository.
     *
     * @param saga the Saga to commit.
     */
    protected void commit(Saga saga) {
        sagaRepository.commit(saga);
    }

    /**
     * Sets whether or not to suppress any exceptions that are cause by invoking Sagas. When suppressed, exceptions are
     * logged. Defaults to <code>true</code>.
     *
     * @param suppressExceptions whether or not to suppress exceptions from Sagas.
     */
    public void setSuppressExceptions(boolean suppressExceptions) {
        this.suppressExceptions = suppressExceptions;
    }

    /**
     * Sets whether of not access to Saga's Event Handler should by synchronized. Defaults to <code>true</code>. Sets
     * to <code>false</code> only if the Saga managed by this manager are completely thread safe by themselves.
     *
     * @param synchronizeSagaAccess whether or not to synchronize access to Saga's event handlers.
     */
    public void setSynchronizeSagaAccess(boolean synchronizeSagaAccess) {
        this.synchronizeSagaAccess = synchronizeSagaAccess;
    }

    /**
     * Returns the set of Saga types managed by this instance.
     *
     * @return the set of Saga types managed by this instance.
     */
    @SuppressWarnings("unchecked")
    public Set<Class<? extends Saga>> getManagedSagaTypes() {
        return new HashSet<>(Arrays.asList(sagaTypes));
    }
}
