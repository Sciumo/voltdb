/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.voltcore.messaging.TransactionInfoBaseMessage;
import org.voltcore.messaging.VoltMessage;

import org.voltdb.messaging.CompleteTransactionMessage;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.messaging.Iv2InitiateTaskMessage;

/**
 * The repair log stores messages received from a PI in case they need to be
 * shared with less informed RIs should the PI shed its mortal coil.
 */
public class RepairLog
{
    private static final boolean IS_SP = true;
    private static final boolean IS_MP = false;

    // last seen spHandle
    // Initialize to Long MAX_VALUE to prevent feeding a newly joined node
    // transactions it should never have seen
    long m_lastSpHandle = Long.MAX_VALUE;
    long m_lastMpHandle = Long.MAX_VALUE;

    // is this a partition leader?
    boolean m_isLeader = false;

    // want voltmessage as payload with message-independent metadata.
    static class Item
    {
        final VoltMessage m_msg;
        final long m_handle;
        final boolean m_type;

        Item(boolean type, VoltMessage msg, long handle)
        {
            m_type = type;
            m_msg = msg;
            m_handle = handle;
        }

        long getSpHandle()
        {
            return m_handle;
        }

        VoltMessage getMessage()
        {
            return m_msg;
        }

        boolean isSP()
        {
            return m_type == IS_SP;
        }

        boolean isMP()
        {
            return m_type == IS_MP;
        }
    }

    // log storage.
    final List<Item> m_log;

    RepairLog()
    {
        m_log = new ArrayList<Item>();
    }

    // leaders log differently
    void setLeaderState(boolean isLeader)
    {
        m_isLeader = isLeader;
        // If we're the leader, wipe out the old repair log.
        // This call to setLeaderState() to promote us to the leader shouldn't
        // happen until after log repair is complete.
        if (m_isLeader) {
            truncate(Long.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    // Offer a new message to the repair log. This will truncate
    // the repairLog if the message includes a truncation hint.
    public void deliver(VoltMessage msg)
    {
        if (msg instanceof FragmentTaskMessage) {
            final TransactionInfoBaseMessage m = (TransactionInfoBaseMessage)msg;
            truncate(m.getTruncationHandle(), Long.MIN_VALUE);
            // only log the first fragment of a procedure (and handle 1st case)
            if (m.getTxnId() > m_lastMpHandle || m_lastMpHandle == Long.MAX_VALUE) {
                m_log.add(new Item(IS_MP, m, m.getTxnId()));
                m_lastMpHandle = m.getTxnId();
            }
        }
        else if (msg instanceof CompleteTransactionMessage) {
            final TransactionInfoBaseMessage m = (TransactionInfoBaseMessage)msg;
            truncate(m.getTruncationHandle(), Long.MIN_VALUE);
            m_log.add(new Item(IS_MP, m, m.getTxnId()));
            m_lastMpHandle = m.getTxnId();
        }
        else if (!m_isLeader && msg instanceof Iv2InitiateTaskMessage) {
            final Iv2InitiateTaskMessage m = (Iv2InitiateTaskMessage)msg;
            m_lastSpHandle = m.getSpHandle();
            truncate(Long.MIN_VALUE, m.getTruncationHandle());
            m_log.add(new Item(IS_SP, m, m.getSpHandle()));
        }
    }

    // trim unnecessary log messages.
    private void truncate(long mpHandle, long spHandle)
    {
        // MIN signals no truncation work to do.
        if (spHandle == Long.MIN_VALUE && mpHandle == Long.MIN_VALUE) {
            return;
        }

        Iterator<RepairLog.Item> it = m_log.iterator();
        while (it.hasNext()) {
            RepairLog.Item item = it.next();
            if (item.isSP() && item.m_handle <= spHandle) {
                it.remove();
            }
            else if (item.isMP() && item.m_handle <= mpHandle) {
                it.remove();
            }
        }
    }

    // return the last seen SP handle
    public long getLastSpHandle()
    {
        return m_lastSpHandle;
    }

    // produce the contents of the repair log.
    public List<Item> contents()
    {
        List<Item> response = new LinkedList<Item>();
        Iterator<Item> it = m_log.iterator();
        while (it.hasNext()) {
            response.add(it.next());
        }
        return response;
    }
}
