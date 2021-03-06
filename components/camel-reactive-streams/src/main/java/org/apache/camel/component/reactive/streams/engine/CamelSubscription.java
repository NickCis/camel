/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.reactive.streams.engine;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.camel.Exchange;
import org.apache.camel.component.reactive.streams.ReactiveStreamsBackpressureStrategy;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a contract between a Camel published and an external subscriber.
 * It manages backpressure in order to deal with slow subscribers.
 */
public class CamelSubscription implements Subscription {

    private static final Logger LOG = LoggerFactory.getLogger(CamelSubscription.class);

    private ExecutorService workerPool;

    private CamelPublisher publisher;

    private ReactiveStreamsBackpressureStrategy backpressureStrategy;

    private Subscriber<? super StreamPayload<Exchange>> subscriber;

    /**
     * The lock is used just for the time necessary to read/write shared variables.
     */
    private Lock mutex = new ReentrantLock(true);

    private LinkedList<StreamPayload<Exchange>> buffer = new LinkedList<>();

    /**
     * The current number of exchanges requested by the subscriber.
     */
    private long requested;

    /**
     * Indicates that a cancel operation is to be performed.
     */
    private boolean terminating;

    /**
     * Indicates that the subscription is end.
     */
    private boolean terminated;

    /**
     * Indicates that a thread is currently sending items downstream.
     * Items must be sent downstream by a single thread for each subscription.
     */
    private boolean sending;


    public CamelSubscription(ExecutorService workerPool, CamelPublisher publisher, ReactiveStreamsBackpressureStrategy backpressureStrategy, Subscriber<? super StreamPayload<Exchange>> subscriber) {
        this.workerPool = workerPool;
        this.publisher = publisher;
        this.backpressureStrategy = backpressureStrategy;
        this.subscriber = subscriber;
    }

    @Override
    public void request(long l) {
        LOG.debug("Requested {} events from subscriber", l);
        if (l <= 0) {
            // wrong argument
            mutex.lock();
            terminated = true;
            mutex.unlock();

            publisher.unsubscribe(this);
            subscriber.onError(new IllegalArgumentException("3.9"));
        } else {
            mutex.lock();
            requested += l;
            mutex.unlock();

            checkAndFlush();
        }
    }

    protected void checkAndFlush() {
        mutex.lock();
        boolean shouldFlush = !terminated && !sending && requested > 0 && buffer.size() > 0;
        if (shouldFlush) {
            sending = true;
        }
        mutex.unlock();

        if (shouldFlush) {
            workerPool.execute(() -> {

                this.flush();

                mutex.lock();
                sending = false;
                mutex.unlock();

                // try again to flush
                checkAndFlush();
            });
        } else {
            mutex.lock();
            boolean shouldComplete = terminating && !terminated;
            if (shouldComplete) {
                terminated = true;
            }
            mutex.unlock();

            if (shouldComplete) {
                this.publisher.unsubscribe(this);
                this.subscriber.onComplete();
                discardBuffer(this.buffer);
            }
        }
    }

    protected void flush() {
        LinkedList<StreamPayload<Exchange>> sendingQueue = null;
        try {
            mutex.lock();

            if (this.terminated) {
                return;
            }

            int amount = (int) Math.min(requested, (long) buffer.size());
            if (amount > 0) {
                this.requested -= amount;
                sendingQueue = new LinkedList<>();
                while (amount > 0) {
                    sendingQueue.add(buffer.removeFirst());
                    amount--;
                }
            }

        } finally {
            mutex.unlock();
        }

        if (sendingQueue != null) {
            LOG.debug("Sending {} events to the subscriber", sendingQueue.size());
            for (StreamPayload<Exchange> data : sendingQueue) {
                // TODO what if the subscriber throws an exception?
                this.subscriber.onNext(data);

                mutex.lock();
                boolean shouldStop = this.terminated;
                mutex.unlock();

                if (shouldStop) {
                    break;
                }
            }
        }
    }

    public void signalCompletion() throws Exception {
        mutex.lock();
        terminating = true;
        mutex.unlock();

        checkAndFlush();
    }

    @Override
    public void cancel() {
        publisher.unsubscribe(this);

        mutex.lock();
        this.terminated = true;
        List<StreamPayload<Exchange>> bufferCopy = new LinkedList<>(buffer);
        this.buffer.clear();
        mutex.unlock();

        discardBuffer(bufferCopy);
    }

    protected void discardBuffer(List<StreamPayload<Exchange>> remaining) {
        for (StreamPayload<Exchange> data : remaining) {
            data.getCallback().processed(data.getItem(), new IllegalStateException("Cannot process the exchange " + data.getItem() + ": subscription cancelled"));
        }
    }

    public void publish(StreamPayload<Exchange> message) {
        Map<StreamPayload<Exchange>, String> discardedMessages = null;
        try {
            mutex.lock();
            if (!this.terminating && !this.terminated) {
                Collection<StreamPayload<Exchange>> discarded = this.backpressureStrategy.update(buffer, message);
                if (discarded.iterator().hasNext()) {
                    discardedMessages = new HashMap<>();
                    for (StreamPayload<Exchange> ex : discarded) {
                        discardedMessages.put(ex, "Exchange " + ex.getItem() + " discarded by backpressure strategy " + this.backpressureStrategy);
                    }
                }
            } else {
                // acknowledge
                discardedMessages = Collections.singletonMap(message, "Exchange " + message.getItem() + " discarded: subscription closed");
            }
        } finally {
            mutex.unlock();
        }

        // discarding outside of mutex scope
        if (discardedMessages != null) {
            for (Map.Entry<StreamPayload<Exchange>, String> discarded : discardedMessages.entrySet()) {
                StreamPayload<Exchange> m = discarded.getKey();
                m.getCallback().processed(m.getItem(), new IllegalStateException(discarded.getValue()));
            }
        }

        checkAndFlush();
    }

    public void setBackpressureStrategy(ReactiveStreamsBackpressureStrategy backpressureStrategy) {
        mutex.lock();
        this.backpressureStrategy = backpressureStrategy;
        mutex.unlock();
    }
}
