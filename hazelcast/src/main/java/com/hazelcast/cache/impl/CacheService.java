/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cache.impl;

import com.hazelcast.cache.impl.client.CacheInvalidationListener;
import com.hazelcast.cache.impl.client.CacheInvalidationMessage;
import com.hazelcast.cache.impl.operation.CacheReplicationOperation;
import com.hazelcast.nio.serialization.Data;

import com.hazelcast.spi.EventRegistration;
import com.hazelcast.spi.EventService;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.PartitionReplicationEvent;

import java.util.Collection;

/**
 * Cache Service is the main access point of JCache implementation.
 * <p>
 * This service is responsible for:
 * <ul>
 * <li>Creating and/or accessing the named {@link com.hazelcast.cache.impl.CacheRecordStore}.</li>
 * <li>Creating/Deleting the cache configuration of the named {@link com.hazelcast.cache.ICache}.</li>
 * <li>Registering/Deregistering of cache listeners.</li>
 * <li>Publish/dispatch cache events.</li>
 * <li>Enabling/Disabling statistic and management.</li>
 * <li>Data migration commit/rollback through {@link com.hazelcast.spi.MigrationAwareService}.</li>
 * </ul>
 * </p>
 * <p><b>WARNING:</b>This service is an optionally registered service which is enabled when {@link javax.cache.Caching}
 * class is found on the classpath.</p>
 * <p>
 * If registered, it will provide all the above cache operations for all partitions of the node which it
 * is registered on.
 * </p>
 * <p><b>Distributed Cache Name</b> is used for providing a unique name to a cache object to overcome cache manager
 * scoping which depends on URI and class loader parameters. It's a simple concatenation of CacheNamePrefix and
 * cache name where CacheNamePrefix is calculated by each cache manager
 * using {@link AbstractHazelcastCacheManager#cacheNamePrefix()}.
 * </p>
 */
public class CacheService extends AbstractCacheService implements ICacheService {

    protected ICacheRecordStore createNewRecordStore(String name, int partitionId) {
        return new CacheRecordStore(name, partitionId, nodeEngine, CacheService.this);
    }

    @Override
    public void reset() {
        for (String objectName : configs.keySet()) {
            destroyCache(objectName, true, null);
        }
        final CachePartitionSegment[] partitionSegments = segments;
        for (CachePartitionSegment partitionSegment : partitionSegments) {
            if (partitionSegment != null) {
                partitionSegment.clear();
            }
        }
    }

    @Override
    public void shutdown(boolean terminate) {
        if (!terminate) {
            reset();
        }
    }

    //region MigrationAwareService
    @Override
    public Operation prepareReplicationOperation(PartitionReplicationEvent event) {
        CachePartitionSegment segment = segments[event.getPartitionId()];
        CacheReplicationOperation op = new CacheReplicationOperation(segment, event.getReplicaIndex());
        return op.isEmpty() ? null : op;
    }
    //endregion

    /**
     * Registers and {@link CacheInvalidationListener} for specified <code>cacheName</code>.
     *
     * @param name      the name of the cache that {@link CacheEventListener} will be registered for
     * @param listener  the {@link CacheEventListener} to be registered for specified <code>cache</code>
     * @return the id which is unique for current registration
     */
    public String addInvalidationListener(String name, CacheEventListener listener) {
        EventService eventService = nodeEngine.getEventService();
        EventRegistration registration = eventService.registerLocalListener(SERVICE_NAME, name, listener);
        return registration.getId();
    }

    /**
     * Sends an invalidation event for given <code>cacheName</code> with specified <code>key</code>
     * from mentioned source with <code>sourceUuid</code>.
     *
     * @param name       the name of the cache that invalidation event is sent for
     * @param key        the {@link Data} represents the invalidation event
     * @param sourceUuid an id that represents the source for invalidation event
     */
    @Override
    public void sendInvalidationEvent(String name, Data key, String sourceUuid) {
        EventService eventService = nodeEngine.getEventService();
        Collection<EventRegistration> registrations = eventService.getRegistrations(SERVICE_NAME, name);
        if (!registrations.isEmpty()) {
            //TODO : fix below for client protocol
            eventService.publishEvent(SERVICE_NAME, registrations,
                    new CacheInvalidationMessage(name, key, sourceUuid), name.hashCode());

        }
    }

}
