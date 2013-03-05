/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.benchmark;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import java.io.IOException;

import org.apache.zookeeper.KeeperException;

import com.google.protobuf.ByteString;
import org.apache.bookkeeper.proto.BookkeeperProtocol.AddRequest;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.proto.BookieProtocol;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.conf.ClientConfiguration;
import java.nio.ByteBuffer;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.cli.ParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BenchBookie {
    static Logger LOG = LoggerFactory.getLogger(BenchBookie.class);

    static class LatencyCallback implements WriteCallback {
        boolean complete;
        @Override
        synchronized public void writeComplete(int rc, long ledgerId, long entryId,
                InetSocketAddress addr, Object ctx) {
            if (rc != 0) {
                LOG.error("Got error " + rc);
            }
            complete = true;
            notifyAll();
        }
        synchronized public void resetComplete() {
            complete = false;
        }
        synchronized public void waitForComplete() throws InterruptedException {
            while(!complete) {
                wait();
            }
        }
    }

    static class ThroughputCallback implements WriteCallback {
        int count;
        int waitingCount = Integer.MAX_VALUE;
        synchronized public void writeComplete(int rc, long ledgerId, long entryId,
                InetSocketAddress addr, Object ctx) {
            if (rc != 0) {
                LOG.error("Got error " + rc);
            }
            count++;
            if (count >= waitingCount) {
                notifyAll();
            }
        }
        synchronized public void waitFor(int count) throws InterruptedException {
            while(this.count < count) {
                waitingCount = count;
                wait(1000);
            }
            waitingCount = Integer.MAX_VALUE;
        }
    }

    private static long getValidLedgerId(String zkServers)
            throws IOException, BKException, KeeperException, InterruptedException {
        BookKeeper bkc = null;
        LedgerHandle lh = null;
        long id = 0;
        try {
            bkc =new BookKeeper(zkServers);
            lh = bkc.createLedger(1, 1, BookKeeper.DigestType.CRC32,
                                  new byte[20]);
            id = lh.getId();
            return id;
        } finally {
            if (lh != null) { lh.close(); }
            if (bkc != null) { bkc.close(); }
        }
    }
    /**
     * @param args
     * @throws InterruptedException
     */
    public static void main(String[] args)
            throws InterruptedException, ParseException, IOException,
            BKException, KeeperException {
        Options options = new Options();
        options.addOption("host", true, "Hostname or IP of bookie to benchmark");
        options.addOption("port", true, "Port of bookie to benchmark (default 3181)");
        options.addOption("zookeeper", true, "Zookeeper ensemble, (default \"localhost:2181\")");
        options.addOption("size", true, "Size of message to send, in bytes (default 1024)");
        options.addOption("help", false, "This message");

        CommandLineParser parser = new PosixParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.hasOption("help") || !cmd.hasOption("host")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("BenchBookie <options>", options);
            System.exit(-1);
        }

        String addr = cmd.getOptionValue("host");
        int port = Integer.valueOf(cmd.getOptionValue("port", "3181"));
        int size = Integer.valueOf(cmd.getOptionValue("size", "1024"));
        String servers = cmd.getOptionValue("zookeeper", "localhost:2181");



        ClientSocketChannelFactory channelFactory
            = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors
                                                .newCachedThreadPool());
        OrderedSafeExecutor executor = new OrderedSafeExecutor(1);

        ClientConfiguration conf = new ClientConfiguration();
        BookieClient bc = new BookieClient(conf, channelFactory, executor);
        LatencyCallback lc = new LatencyCallback();

        ThroughputCallback tc = new ThroughputCallback();
        int warmUpCount = 999;

        long ledger = getValidLedgerId(servers);

        AddRequest.Builder builder = AddRequest.newBuilder()
            .setLedgerId(ledger).setMasterKey(ByteString.copyFrom(new byte[20]));
        byte[] data = new byte[size];
        for(long entry = 0; entry < warmUpCount; entry++) {
            ByteBuffer toSend = ByteBuffer.allocate(size + 16);
            toSend.putLong(ledger);
            toSend.putLong(entry);
            toSend.put(data);
            toSend.flip();
            builder.setData(ByteString.copyFrom(data));
            bc.addEntry(new InetSocketAddress(addr, port),
                        builder.setEntryId(entry).build(),
                        tc, null);
        }
        LOG.info("Waiting for warmup");
        tc.waitFor(warmUpCount);

        ledger = getValidLedgerId(servers);
        LOG.info("Benchmarking latency");
        int entryCount = 5000;
        long startTime = System.nanoTime();
        for(long entry = 0; entry < entryCount; entry++) {
            ByteBuffer toSend = ByteBuffer.allocate(size + 16);
            toSend.putLong(ledger);
            toSend.putLong(entry);
            toSend.put(data);
            toSend.flip();
            builder.setData(ByteString.copyFrom(data));

            bc.addEntry(new InetSocketAddress(addr, port),
                        builder.setEntryId(entry).build(),
                        lc, null);
            lc.waitForComplete();
        }
        long endTime = System.nanoTime();
        LOG.info("Latency: " + (((double)(endTime-startTime))/((double)entryCount))/1000000.0);

        entryCount = 50000;

        ledger = getValidLedgerId(servers);
        LOG.info("Benchmarking throughput");
        startTime = System.currentTimeMillis();
        tc = new ThroughputCallback();
        for(long entry = 0; entry < entryCount; entry++) {
            ByteBuffer toSend = ByteBuffer.allocate(size + 16);
            toSend.putLong(ledger);
            toSend.putLong(entry);
            toSend.put(data);
            toSend.flip();
            builder.setData(ByteString.copyFrom(data));

            bc.addEntry(new InetSocketAddress(addr, port),
                        builder.setEntryId(entry).build(),
                        tc, null);
        }
        tc.waitFor(entryCount);
        endTime = System.currentTimeMillis();
        LOG.info("Throughput: " + ((long)entryCount)*1000/(endTime-startTime));

        bc.close();
        channelFactory.releaseExternalResources();
        executor.shutdown();
    }

}
