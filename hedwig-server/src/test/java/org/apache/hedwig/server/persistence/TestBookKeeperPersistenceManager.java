/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig.server.persistence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.bookkeeper.versioning.Version;
import org.apache.bookkeeper.versioning.Versioned;

import org.apache.hedwig.HelperMethods;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol;
import org.apache.hedwig.protocol.PubSubProtocol.LedgerRange;
import org.apache.hedwig.protocol.PubSubProtocol.LedgerRanges;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.meta.MetadataManagerFactory;
import org.apache.hedwig.server.meta.SubscriptionDataManager;
import org.apache.hedwig.server.meta.TopicOwnershipManager;
import org.apache.hedwig.server.meta.TopicPersistenceManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.server.topics.TrivialOwnAllTopicManager;
import org.apache.hedwig.util.Callback;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

@RunWith(Parameterized.class)
public class TestBookKeeperPersistenceManager extends TestCase {
    static Logger logger = LoggerFactory.getLogger(TestPersistenceManagerBlackBox.class);

    BookKeeperTestBase bktb;
    private final int numBookies = 3;
    private final long readDelay = 2000L;

    ServerConfiguration conf;
    ScheduledExecutorService scheduler;

    TopicManager tm;
    BookkeeperPersistenceManager manager;
    PubSubException failureException = null;
    TestMetadataManagerFactory metadataManagerFactory;
    TopicPersistenceManager tpManager;

    boolean removeStartSeqId;

    static class TestMetadataManagerFactory extends MetadataManagerFactory {

        final MetadataManagerFactory factory;
        int serviceDownCount = 0;

        TestMetadataManagerFactory(ServerConfiguration conf, ZooKeeper zk) throws Exception {
            factory = MetadataManagerFactory.newMetadataManagerFactory(conf, zk);
        }

        public void setServiceDownCount(int count) {
            this.serviceDownCount = count;
        }

        @Override
        public int getCurrentVersion() {
            return factory.getCurrentVersion();
        }

        @Override
        protected MetadataManagerFactory initialize(
            ServerConfiguration cfg, ZooKeeper zk, int version) throws IOException {
            // do nothing
            return factory;
        }

        @Override
        public void shutdown() throws IOException {
            factory.shutdown();
        }

        @Override
        public Iterator<ByteString> getTopics() throws IOException {
            return factory.getTopics();
        }

        @Override
        public TopicPersistenceManager newTopicPersistenceManager() {
            final TopicPersistenceManager manager = factory.newTopicPersistenceManager();
            return new TopicPersistenceManager() {

                @Override
                public void close() throws IOException {
                    manager.close();
                }

                @Override
                public void readTopicPersistenceInfo(ByteString topic,
                                                     Callback<Versioned<LedgerRanges>> callback, Object ctx) {
                    if (serviceDownCount > 0) {
                        --serviceDownCount;
                        callback.operationFailed(ctx, new PubSubException.ServiceDownException("Metadata Store is down"));
                        return;
                    }
                    manager.readTopicPersistenceInfo(topic, callback, ctx);
                }
                @Override
                public void writeTopicPersistenceInfo(ByteString topic, LedgerRanges ranges, Version version,
                                                      Callback<Version> callback, Object ctx) {
                    if (serviceDownCount > 0) {
                        --serviceDownCount;
                        callback.operationFailed(ctx, new PubSubException.ServiceDownException("Metadata Store is down"));
                        return;
                    }
                    manager.writeTopicPersistenceInfo(topic, ranges, version, callback, ctx);
                }
                @Override
                public void deleteTopicPersistenceInfo(ByteString topic, Version version,
                                                       Callback<Void> callback, Object ctx) {
                    if (serviceDownCount > 0) {
                        --serviceDownCount;
                        callback.operationFailed(ctx, new PubSubException.ServiceDownException("Metadata Store is down"));
                        return;
                    }
                    manager.deleteTopicPersistenceInfo(topic, version, callback, ctx);
                }
            };
        }

        @Override
        public SubscriptionDataManager newSubscriptionDataManager() {
            return factory.newSubscriptionDataManager();
        }

        @Override
        public TopicOwnershipManager newTopicOwnershipManager() {
            return factory.newTopicOwnershipManager();
        }
    }

    public TestBookKeeperPersistenceManager(boolean removeStartSeqId) {
        this.removeStartSeqId = removeStartSeqId;
    }

    @Parameters
    public static Collection<Object[]> configs() {
        return Arrays.asList(new Object[][] {
            { true }, { false }
        });
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        // delay read response for 2s.
        bktb = new BookKeeperTestBase(numBookies, readDelay);
        bktb.setUp();

        conf = new ServerConfiguration();
        org.apache.bookkeeper.conf.ClientConfiguration bkClientConf =
                new org.apache.bookkeeper.conf.ClientConfiguration();
        bkClientConf.setNumWorkerThreads(1).setReadTimeout(9999)
        .setThrottleValue(3);
        conf.addConf(bkClientConf);

        metadataManagerFactory = new TestMetadataManagerFactory(conf, bktb.getZooKeeperClient());
        tpManager = metadataManagerFactory.newTopicPersistenceManager();

        scheduler = Executors.newScheduledThreadPool(1);
        tm = new TrivialOwnAllTopicManager(conf, scheduler);
        manager = new BookkeeperPersistenceManager(bktb.bk, metadataManagerFactory,
                                                   tm, conf, scheduler);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        tm.stop();
        manager.stop();
        tpManager.close();
        metadataManagerFactory.shutdown();
        scheduler.shutdown();
        bktb.tearDown();
        super.tearDown();
    }

    class RangeScanVerifier implements ScanCallback {
        LinkedList<Message> pubMsgs;
        boolean runNextScan = false;
        RangeScanRequest nextScan = null;

        public RangeScanVerifier(LinkedList<Message> pubMsgs, RangeScanRequest nextScan) {
            this.pubMsgs = pubMsgs;
            this.nextScan = nextScan;
        }

        @Override
        public void messageScanned(Object ctx, Message recvMessage) {
            logger.info("Scanned message : {}", recvMessage.getMsgId().getLocalComponent());
            if (null != nextScan && !runNextScan) {
                runNextScan = true;
                manager.scanMessages(nextScan);
            }

            if (pubMsgs.size() == 0) {
                return;
            }

            Message pubMsg = pubMsgs.removeFirst();
            if (!HelperMethods.areEqual(recvMessage, pubMsg)) {
                fail("Scanned message not equal to expected");
            }
        }

        @Override
        public void scanFailed(Object ctx, Exception exception) {
            fail("Failed to scan messages.");
        }

        @Override
        @SuppressWarnings("unchecked")
        public void scanFinished(Object ctx, ReasonForFinish reason) {
            LinkedBlockingQueue<Boolean> statusQueue = (LinkedBlockingQueue<Boolean>) ctx;
            try {
                statusQueue.put(pubMsgs.isEmpty());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private LinkedList<Message> subMessages(List<Message> msgs, int start, int end) {
        LinkedList<Message> result = new LinkedList<Message>();
        for (int i=start; i<=end; i++) {
            result.add(msgs.get(i));
        }
        return result;
    }

    @Test
    public void testScanMessagesOnClosedLedgerAfterDeleteLedger() throws Exception {
        scanMessagesAfterDeleteLedgerTest(2);
    }

    @Test
    public void testScanMessagesOnUnclosedLedgerAfterDeleteLedger() throws Exception {
        scanMessagesAfterDeleteLedgerTest(1);
    }

    private void scanMessagesAfterDeleteLedgerTest(int numLedgers) throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestScanMessagesAfterDeleteLedger");

        List<Message> msgs = new ArrayList<Message>();

        acquireTopic(topic);
        msgs.addAll(publishMessages(topic, 2));

        for (int i=0; i<numLedgers; i++) {
            releaseTopic(topic);
            // acquire topic again to force a new ledger
            acquireTopic(topic);
            msgs.addAll(publishMessages(topic, 2));
        }

        consumedUntil(topic, 2L);
        // Wait until ledger ranges is updated.
        Thread.sleep(2000L);
        releaseTopic(topic);

        // acquire topic again
        acquireTopic(topic);
        // scan messages starting from 3
        LinkedBlockingQueue<Boolean> statusQueue =
            new LinkedBlockingQueue<Boolean>();
        manager.scanMessages(new RangeScanRequest(topic, 3, 2, Long.MAX_VALUE,
                             new RangeScanVerifier(subMessages(msgs, 2, 3), null), statusQueue));
        Boolean b = statusQueue.poll(10 * readDelay, TimeUnit.MILLISECONDS);
        assertTrue("Should succeed to scan messages after deleted consumed ledger.", b);
    }

    @Test
    public void testScanMessagesOnEmptyLedgerAfterDeleteLedger() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestScanMessagesOnEmptyLedgerAfterDeleteLedger");

        List<Message> msgs = new ArrayList<Message>();

        acquireTopic(topic);
        msgs.addAll(publishMessages(topic, 2));
        releaseTopic(topic);

        // acquire topic again to force a new ledger
        acquireTopic(topic);
        logger.info("Consumed messages.");
        consumedUntil(topic, 2L);
        // Wait until ledger ranges is updated.
        Thread.sleep(2000L);
        logger.info("Released topic with an empty ledger.");
        // release topic to force an empty ledger
        releaseTopic(topic);

        // publish 2 more messages, these message expected to be id 3 and 4
        acquireTopic(topic);
        logger.info("Published more messages.");
        msgs.addAll(publishMessages(topic, 2));
        releaseTopic(topic);

        // acquire topic again
        acquireTopic(topic);
        // scan messages starting from 3
        LinkedBlockingQueue<Boolean> statusQueue =
            new LinkedBlockingQueue<Boolean>();
        long startSeqId = removeStartSeqId ? 1 : 3;
        manager.scanMessages(new RangeScanRequest(topic, startSeqId, 2, Long.MAX_VALUE,
                             new RangeScanVerifier(subMessages(msgs, 2, 3), null), statusQueue));
        Boolean b = statusQueue.poll(10 * readDelay, TimeUnit.MILLISECONDS);
        assertTrue("Should succeed to scan messages after deleted consumed ledger.", b);
    }

    @Test
    public void testFailedToDeleteLedger1() throws Exception {
        failedToDeleteLedgersTest(1);
    }

    @Test
    public void testFailedToDeleteLedger2() throws Exception {
        // succeed to delete second ledger
        failedToDeleteLedgersTest(2);
    }

    private void failedToDeleteLedgersTest(int numLedgers) throws Exception {
        final ByteString topic = ByteString.copyFromUtf8("TestFailedToDeleteLedger");
        final int serviceDownCount = 1;

        List<Message> msgs = new ArrayList<Message>();

        for (int i=0; i<numLedgers; i++) {
            acquireTopic(topic);
            msgs.addAll(publishMessages(topic, 2));
            releaseTopic(topic);
        }

        // acquire topic again to force a new ledger
        acquireTopic(topic);
        logger.info("Consumed messages.");
        metadataManagerFactory.setServiceDownCount(serviceDownCount);
        // failed consumed
        consumedUntil(topic, 2L * numLedgers);
        // Wait until ledger ranges is updated.
        Thread.sleep(2000L);
        logger.info("Released topic with an empty ledger.");
        // release topic to force an empty ledger
        releaseTopic(topic);

        // publish 2 more messages, these message expected to be id 3 and 4
        acquireTopic(topic);
        logger.info("Published more messages.");
        msgs.addAll(publishMessages(topic, 2));
        releaseTopic(topic);

        // acquire topic again
        acquireTopic(topic);
        LinkedBlockingQueue<Boolean> statusQueue =
            new LinkedBlockingQueue<Boolean>();
        manager.scanMessages(new RangeScanRequest(topic, numLedgers * 2 + 1, 2, Long.MAX_VALUE,
                             new RangeScanVerifier(subMessages(msgs, numLedgers * 2, numLedgers * 2 + 1), null), statusQueue));
        Boolean b = statusQueue.poll(10 * readDelay, TimeUnit.MILLISECONDS);
        assertTrue("Should succeed to scan messages after deleted consumed ledger.", b);

        // consumed 
        consumedUntil(topic, (numLedgers + 1) * 2L);
        // Wait until ledger ranges is updated.
        Thread.sleep(2000L);

        Semaphore latch = new Semaphore(1);
        latch.acquire();
        tpManager.readTopicPersistenceInfo(topic, new Callback<Versioned<LedgerRanges>>() {
            @Override
            public void operationFinished(Object ctx, Versioned<LedgerRanges> ranges) {
                if (null == ranges || ranges.getValue().getRangesList().size() > 1) {
                    failureException = new PubSubException.NoTopicPersistenceInfoException("Invalid persistence info found for topic " + topic.toStringUtf8());
                    ((Semaphore)ctx).release();
                    return;
                }
                failureException = null;
                ((Semaphore)ctx).release();
            }
            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                failureException = exception;
                ((Semaphore)ctx).release();
            }
        }, latch);
        latch.acquire();
        latch.release();
        assertNull("Should not fail with exception.", failureException);
    }

    @Test
    public void testScanMessagesOnTwoLedgers() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestScanMessagesOnTwoLedgers");

        List<Message> msgs = new ArrayList<Message>();

        acquireTopic(topic);
        msgs.addAll(publishMessages(topic, 1));
        releaseTopic(topic);

        // acquire topic again to force a new ledger
        acquireTopic(topic);
        msgs.addAll(publishMessages(topic, 3));

        // scan messages
        LinkedBlockingQueue<Boolean> statusQueue = new LinkedBlockingQueue<Boolean>();
        RangeScanRequest nextScan = new RangeScanRequest(topic, 3, 2, Long.MAX_VALUE,
                new RangeScanVerifier(subMessages(msgs, 2, 3), null), statusQueue);
        manager.scanMessages(new RangeScanRequest(topic, 1, 2, Long.MAX_VALUE,
                new RangeScanVerifier(subMessages(msgs, 0, 1), nextScan), statusQueue));
        Boolean b = statusQueue.poll(10 * readDelay, TimeUnit.MILLISECONDS);
        if (b == null) {
            fail("One scan request doesn't finish");
        }
        b = statusQueue.poll(10 * readDelay, TimeUnit.MILLISECONDS);
        if (b == null) {
            fail("One scan request doesn't finish");
        }
    }

    class TestCallback implements Callback<PubSubProtocol.MessageSeqId> {

        @Override
        @SuppressWarnings("unchecked")
        public void operationFailed(Object ctx, PubSubException exception) {
            LinkedBlockingQueue<Boolean> statusQueue = (LinkedBlockingQueue<Boolean>) ctx;
            try {
                statusQueue.put(false);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void operationFinished(Object ctx, PubSubProtocol.MessageSeqId resultOfOperation) {
            LinkedBlockingQueue<Boolean> statusQueue = (LinkedBlockingQueue<Boolean>) ctx;
            try {
                statusQueue.put(true);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    protected List<Message> publishMessages(ByteString topic, int numMsgs) throws Exception {
        List<Message> msgs = HelperMethods.getRandomPublishedMessages(numMsgs, 1024);
        LinkedBlockingQueue<Boolean> statusQueue = new LinkedBlockingQueue<Boolean>();
        for (Message msg : msgs) {

            try {
                manager.persistMessage(new PersistRequest(topic, msg, new TestCallback(), statusQueue));
                // wait a maximum of a minute
                Boolean b = statusQueue.poll(60, TimeUnit.SECONDS);
                if (b == null) {
                    throw new RuntimeException("Publish timed out");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return msgs;
    }

    protected void acquireTopic(ByteString topic) throws Exception {
        Semaphore latch = new Semaphore(1);
        latch.acquire();
        manager.acquiredTopic(topic, new Callback<Void>() {
            @Override
            public void operationFinished(Object ctx, Void resultOfOperation) {
                failureException = null;
                ((Semaphore)ctx).release();
            }
            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                failureException = exception;
                ((Semaphore)ctx).release();
            }
        }, latch);
        latch.acquire();
        latch.release();
        if (null != failureException) {
            throw failureException;
        }
    }

    protected void releaseTopic(final ByteString topic) throws Exception {
        manager.lostTopic(topic);
        // backward testing ledger ranges without start seq id
        if (removeStartSeqId) {
            Semaphore latch = new Semaphore(1);
            latch.acquire();
            tpManager.readTopicPersistenceInfo(topic, new Callback<Versioned<LedgerRanges>>() {
                @Override
                public void operationFinished(Object ctx, Versioned<LedgerRanges> ranges) {
                    if (null == ranges) {
                        failureException = new PubSubException.NoTopicPersistenceInfoException("No persistence info found for topic " + topic.toStringUtf8());
                        ((Semaphore)ctx).release();
                        return;
                    }

                    // build a new ledger ranges w/o start seq id.
                    LedgerRanges.Builder builder = LedgerRanges.newBuilder();
                    final List<LedgerRange> rangesList = ranges.getValue().getRangesList();
                    for (LedgerRange range : rangesList) {
                        LedgerRange.Builder newRangeBuilder = LedgerRange.newBuilder();
                        newRangeBuilder.setLedgerId(range.getLedgerId());
                        if (range.hasEndSeqIdIncluded()) {
                            newRangeBuilder.setEndSeqIdIncluded(range.getEndSeqIdIncluded());
                        }
                        builder.addRanges(newRangeBuilder.build());
                    }
                    tpManager.writeTopicPersistenceInfo(topic, builder.build(), ranges.getVersion(),
                    new Callback<Version>() {
                        @Override
                        public void operationFinished(Object ctx, Version newVersion) {
                            failureException = null;
                            ((Semaphore)ctx).release();
                        }
                        @Override
                        public void operationFailed(Object ctx, PubSubException exception) {
                            failureException = exception;
                            ((Semaphore)ctx).release();
                        }
                    }, ctx);
                }
                @Override
                public void operationFailed(Object ctx, PubSubException exception) {
                    failureException = exception;
                    ((Semaphore)ctx).release();
                }
            }, latch);
            latch.acquire();
            latch.release();
            if (null != failureException) {
                throw failureException;
            }
        }
    }

    protected void consumedUntil(ByteString topic, long seqId) throws Exception {
        manager.consumedUntil(topic, seqId);
    }

}
