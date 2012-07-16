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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import org.apache.zookeeper_voltpatches.KeeperException;
import org.apache.zookeeper_voltpatches.ZooKeeper;

import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONObject;

import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.VoltMessage;

import org.voltcore.zk.MapCache;
import org.voltcore.zk.MapCacheWriter;

import org.voltdb.VoltDB;

public class StartupAlgo implements RepairAlgo
{
    VoltLogger tmLog = new VoltLogger("TM");
    private final String m_whoami;

    private final InitiatorMailbox m_mailbox;
    private final int m_partitionId;
    private final ZooKeeper m_zk;
    private final CountDownLatch m_missingStartupSites;
    private final String m_mapCacheNode;

    // Each Term can process at most one promotion; if promotion fails, make
    // a new Term and try again (if that's your big plan...)
    private final InaugurationFuture m_promotionResult = new InaugurationFuture();

    /**
     * Setup a new StartupAlgo but don't take any action to take responsibility.
     */
    public StartupAlgo(CountDownLatch missingStartupSites, ZooKeeper zk,
            int partitionId, InitiatorMailbox mailbox,
            String zkMapCacheNode, String whoami)
    {
        m_zk = zk;
        m_partitionId = partitionId;
        m_mailbox = mailbox;

        if (missingStartupSites != null) {
            m_missingStartupSites = missingStartupSites;
        }
        else {
            m_missingStartupSites = new CountDownLatch(0);
        }

        m_whoami = whoami;
        m_mapCacheNode = zkMapCacheNode;
    }

    @Override
    public Future<Boolean> start()
    {
        try {
            prepareForStartup();
        } catch (Exception e) {
            tmLog.error(m_whoami + "failed leader promotion:", e);
            m_promotionResult.setException(e);
            m_promotionResult.done();
        }
        return m_promotionResult;
    }

    @Override
    public boolean cancel()
    {
        // Can't cancel startup this way, Ned.
        return false;
    }

    /** Block until all replicas are present. */
    void prepareForStartup()
        throws InterruptedException
    {
        tmLog.info(m_whoami +
                "starting leader promotion.  Waiting for " +
                m_missingStartupSites.getCount() + " more for configured k-safety.");

        // block here until the babysitter thread provides all replicas.
        // then initialize the mailbox's replica set and proceed as leader.
        m_missingStartupSites.await();
        declareReadyAsLeader();
    }

    /** Process a new repair log response */
    @Override
    public void deliver(VoltMessage message)
    {
        throw new RuntimeException("Dude, shouldn't get these here.");
    }

    // with leadership election complete, update the master list
    // for non-initiator components that care.
    void declareReadyAsLeader()
    {
        try {
            MapCacheWriter iv2masters = new MapCache(m_zk, m_mapCacheNode);
            iv2masters.put(Integer.toString(m_partitionId),
                    new JSONObject("{hsid:" + m_mailbox.getHSId() + "}"));
            m_promotionResult.done();
        } catch (KeeperException e) {
            VoltDB.crashLocalVoltDB("Bad news: failed to declare leader.", true, e);
        } catch (InterruptedException e) {
            VoltDB.crashLocalVoltDB("Bad news: failed to declare leader.", true, e);
        } catch (JSONException e) {
            VoltDB.crashLocalVoltDB("Bad news: failed to declare leader.", true, e);
        }
    }
}