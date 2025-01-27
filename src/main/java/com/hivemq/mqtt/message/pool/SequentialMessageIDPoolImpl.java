/*
 * Copyright 2019-present HiveMQ GmbH
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
package com.hivemq.mqtt.message.pool;

import com.google.common.primitives.Ints;
import com.hivemq.extension.sdk.api.annotations.ThreadSafe;
import com.hivemq.mqtt.message.pool.exception.NoMessageIdAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Implementation note: Benchmarks revealed that the implementation with synchronized
 * methods is fast enough for our use case
 *
 * @author Dominik Obermaier
 */
@ThreadSafe
public class SequentialMessageIDPoolImpl implements MessageIDPool {

    private static final Logger log = LoggerFactory.getLogger(SequentialMessageIDPoolImpl.class);

    private static final int MIN_MESSAGE_ID = 0;
    private static final int MAX_MESSAGE_ID = 65_535;
    //we can cache the exception, because we are not interested in any stack trace
    private static final NoMessageIdAvailableException NO_MESSAGE_ID_AVAILABLE_EXCEPTION = new NoMessageIdAvailableException();

    static {
        //Clear the stack trace, otherwise we harden debugging unnecessary
        NO_MESSAGE_ID_AVAILABLE_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }

    /**
     * The set with all already used ids. These ids must not be reused until they are returned.
     */
    private final Set<Integer> usedMessageIds = new HashSet<>(50);

    private int circularTicker;

    @ThreadSafe
    @Override
    public synchronized int takeNextId() throws NoMessageIdAvailableException {
        if (usedMessageIds.size() >= MAX_MESSAGE_ID) {
            throw NO_MESSAGE_ID_AVAILABLE_EXCEPTION;
        }

        //We're searching (sequentially) until we hit a message id which is not used already
        do {
            circularTicker += 1;
            if (circularTicker > MAX_MESSAGE_ID) {
                circularTicker = 1;
            }
        } while (usedMessageIds.contains(circularTicker));

        usedMessageIds.add(circularTicker);

        return circularTicker;
    }

    @ThreadSafe
    @Override
    public synchronized int takeIfAvailable(final int id) throws NoMessageIdAvailableException {

        checkArgument(id > MIN_MESSAGE_ID);
        checkArgument(id <= MAX_MESSAGE_ID);

        if (usedMessageIds.contains(id)) {
            return takeNextId();
        }

        usedMessageIds.add(id);

        if (id > circularTicker) {
            circularTicker = id;
        }

        return id;
    }

    /**
     * @throws IllegalArgumentException if the message id is not between 1 and 65535
     */
    @ThreadSafe
    @Override
    public synchronized void returnId(final int id) {
        checkArgument(id > MIN_MESSAGE_ID, "MessageID must be larger than 0");
        checkArgument(id <= MAX_MESSAGE_ID, "MessageID must be smaller than 65536");

        final boolean removed = usedMessageIds.remove(id);

        if (!removed) {
            log.trace("Tried to return message id {} although it was already returned. This is could mean a DUP was acked", id);
        }
    }

    /**
     * @throws IllegalArgumentException if one of the message id is not between 1 and 65535
     */
    @ThreadSafe
    @Override
    public synchronized void prepopulateWithUnavailableIds(final int... ids) {

        for (final int id : ids) {
            checkArgument(id > MIN_MESSAGE_ID);
            checkArgument(id <= MAX_MESSAGE_ID);
        }
        final List<Integer> idList = Ints.asList(ids);
        Collections.sort(idList);
        circularTicker = idList.get(idList.size() - 1);
        usedMessageIds.addAll(idList);
    }
}
