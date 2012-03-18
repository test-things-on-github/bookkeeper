/*
 *
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

package org.apache.bookkeeper.bookie;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements a ledger inside a bookie. In particular, it implements operations
 * to write entries to a ledger and read entries from a ledger.
 *
 */
public class LedgerDescriptorImpl extends LedgerDescriptor {
    final static Logger LOG = LoggerFactory.getLogger(LedgerDescriptor.class);
    LedgerCache ledgerCache;
    private long ledgerId;
    EntryLogger entryLogger;
    // TODO: is refCnt even needed?
    private int refCnt;
    volatile private boolean fenced = false;
    final byte[] masterKey;

    LedgerDescriptorImpl(byte[] masterKey, long ledgerId, EntryLogger entryLogger, LedgerCache ledgerCache) {
        this.masterKey = masterKey;
        this.ledgerId = ledgerId;
        this.entryLogger = entryLogger;
        this.ledgerCache = ledgerCache;
    }

    @Override
    void checkAccess(byte masterKey[]) throws BookieException, IOException {
        if (!Arrays.equals(this.masterKey, masterKey)) {
            throw BookieException.create(BookieException.Code.UnauthorizedAccessException);
        }
    }

    @Override
    public long getLedgerId() {
        return ledgerId;
    }

    @Override
    synchronized public void incRef() {
        refCnt++;
    }

    @Override
    synchronized public void decRef() {
        refCnt--;
    }

    @Override
    void setFenced() throws IOException {
        fenced = true;
    }

    @Override
    boolean isFenced() {
        return fenced;
    }

    @Override
    long addEntry(ByteBuffer entry) throws IOException {
        long ledgerId = entry.getLong();

        if (ledgerId != this.ledgerId) {
            throw new IOException("Entry for ledger " + ledgerId + " was sent to " + this.ledgerId);
        }
        long entryId = entry.getLong();
        entry.rewind();

        /*
         * Log the entry
         */
        long pos = entryLogger.addEntry(ledgerId, entry);


        /*
         * Set offset of entry id to be the current ledger position
         */
        ledgerCache.putEntryOffset(ledgerId, entryId, pos);
        return entryId;
    }

    @Override
    ByteBuffer readEntry(long entryId) throws IOException {
        long offset;
        /*
         * If entryId is -1, then return the last written.
         */
        if (entryId == -1) {
            entryId = ledgerCache.getLastEntry(ledgerId);
        }

        offset = ledgerCache.getEntryOffset(ledgerId, entryId);
        if (offset == 0) {
            throw new Bookie.NoEntryException(ledgerId, entryId);
        }
        return ByteBuffer.wrap(entryLogger.readEntry(ledgerId, entryId, offset));
    }
}