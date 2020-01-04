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

package org.apache.zookeeper.server.quorum;

import java.util.ArrayList;
import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ZooKeeperCriticalThread;
import org.apache.zookeeper.server.ZooKeeperServerListener;

/**
 * This RequestProcessor matches the incoming committed requests with the
 * locally submitted requests. The trick is that locally submitted requests that
 * change the state of the system will come back as incoming committed requests,
 * so we need to match them up.
 */
public class CommitProcessor extends ZooKeeperCriticalThread implements RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CommitProcessor.class);

    /**
     * Requests that we are holding until the commit comes in.
     *
     * 已经发出提议等待收到过半服务器ack的请求队列
     */
    LinkedList<Request> queuedRequests = new LinkedList<Request>();

    /**
     * Requests that have been committed.
     *
     * 已经收到过半服务器ack的请求队列，以为着该请求可以被提交了。
     */
    LinkedList<Request> committedRequests = new LinkedList<Request>();

    RequestProcessor nextProcessor;
    // 待处理的队列
    ArrayList<Request> toProcess = new ArrayList<Request>();

    /**
     * This flag indicates whether we need to wait for a response to come back from the
     * leader or we just let the sync operation flow through like a read. The flag will
     * be false if the CommitProcessor is in a Leader pipeline.
     */
    boolean matchSyncs;

    public CommitProcessor(RequestProcessor nextProcessor, String id,
            boolean matchSyncs, ZooKeeperServerListener listener) {
        super("CommitProcessor:" + id, listener);
        this.nextProcessor = nextProcessor;
        this.matchSyncs = matchSyncs;
    }

    volatile boolean finished = false;

    @Override
    public void run() {
        try {
            Request nextPending = null;            
            while (!finished) {

                // 部分1：遍历toProcess队列(非事务请求或者已经提交的事务请求),交给下一个处理器处理，清空
                int len = toProcess.size();
                for (int i = 0; i < len; i++) {
                    // 待处理队列交给下个处理器,按顺序处理
                    nextProcessor.processRequest(toProcess.get(i));
                }


                /**
                 * 在请求队列remove干净或者找到了事务请求的情况下，
                 * 如果没有提交的请求，就等待。
                 * 如果有提交的请求，取出来，看和之前记录的下一个pend的请求是否match。
                 *   match的话，进入toProcess队列，nextPending置空
                 *   不match的话,(基本上是nextPending为null，不会出现不为null且不匹配的情况),进入toProcess处理
                 */
                // 队列清空
                toProcess.clear();
                synchronized (this) {
                    // 要么 请求队列remove干净，要么从中找到一个事务请求，赋值给nextPending, 不允许size>0且nextPending == null的情况
                    if ((queuedRequests.size() == 0 || nextPending != null)
                            && committedRequests.size() == 0) { // 没有已提交事务
                        // 处理链刚刚启动完成或没哟待处理请求是阻塞在这里
                        wait();
                        continue;
                    }
                    // First check and see if the commit came in for the pending
                    // request
                    // 不允许size>0且nextPending == null的情况
                    if ((queuedRequests.size() == 0 || nextPending != null)
                            && committedRequests.size() > 0) { // 如果有 已提交的请求
                        Request r = committedRequests.remove();
                        /*
                         * We match with nextPending so that we can move to the
                         * next request when it is committed. We also want to
                         * use nextPending because it has the cnxn member set
                         * properly.
                         */
                        if (nextPending != null
                                && nextPending.sessionId == r.sessionId
                                && nextPending.cxid == r.cxid) { // 如果和nextPending匹配
                            // we want to send our version of the request.
                            // the pointer to the connection in the request
                            nextPending.hdr = r.hdr;
                            nextPending.txn = r.txn;
                            nextPending.zxid = r.zxid;
                            // 加入待处理队列
                            toProcess.add(nextPending);
                            // 下一个pend的请求清空
                            nextPending = null;
                        } else {
                            // this request came from someone else so just
                            // send the commit packet
                            // 这种情况是nextPending还没有来的及设置，nextPending==null的情况(代码应该再细分一下if else),不可能出现nextPending!=null而走到了这里的情况(算异常)
                            toProcess.add(r);
                        }
                    }
                }

                // We haven't matched the pending requests, so go back to
                // waiting


                // 部分3 如果 nextPending非空，就不用再去遍历请求队列，找到下一个事务请求(即4部分)，因此continue掉

                // 如果还有 未处理的事务请求(不含leader端的sync请求),就continue
                if (nextPending != null) {
                    continue;
                }

                // 部分4： 只要不存在pend住的事务请求并且请求队列不为空，
                // 一直遍历请求队列直到出现第一个事务请求或者队列遍历完，其间所有非事务请求全部加入toProcess队列,代表可以直接交给下一个处理器处理的
                synchronized (this) {
                    // Process the next requests in the queuedRequests
                    while (nextPending == null && queuedRequests.size() > 0) {
                        Request request = queuedRequests.remove();
                        switch (request.type) {
                        case OpCode.create:
                        case OpCode.delete:
                        case OpCode.setData:
                        case OpCode.multi:
                        case OpCode.setACL:
                        case OpCode.createSession:
                        case OpCode.closeSession:
                            // 事务请求直接赋给nextPending，然后break
                            nextPending = request;
                            break;
                        case OpCode.sync:
                            // 如果需要等leader返回,该值learner端为true
                            if (matchSyncs) {
                                nextPending = request;
                            } else {
                                // 不需要的话，直接加入待处理队列里
                                toProcess.add(request);
                            }
                            // leader端matchSyncs是false，learner端才需要等leader回复，这里也break
                            break;
                        default:
                            // 非事务请求，都直接加入待处理队列
                            toProcess.add(request);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            LOG.warn("Interrupted exception while waiting", e);
        } catch (Throwable e) {
            LOG.error("Unexpected exception causing CommitProcessor to exit", e);
        }
        LOG.info("CommitProcessor exited loop!");
    }

    synchronized public void commit(Request request) {
        if (!finished) {
            if (request == null) {
                LOG.warn("Committed a null!",
                         new Exception("committing a null! "));
                return;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Committing request:: " + request);
            }
            committedRequests.add(request);
            notifyAll();
        }
    }

    synchronized public void processRequest(Request request) {
        // request.addRQRec(">commit");
        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing request:: " + request);
        }
        
        if (!finished) {
            // 请求加入阻塞队列
            queuedRequests.add(request);
            // 唤醒CommitProcessor线程等待的线程
            notifyAll();
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        synchronized (this) {
            finished = true;
            queuedRequests.clear();
            notifyAll();
        }
        if (nextProcessor != null) {
            nextProcessor.shutdown();
        }
    }

}
