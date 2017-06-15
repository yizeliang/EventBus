/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * 主线程时间处理类
 */
final class HandlerPoster extends Handler {

    /**
     * 事件队列
     */
    private final PendingPostQueue queue;
    /**
     * 其实就是执行一个事件的事件,如果大于一定值,则认为这个事件处理失败
     */
    private final int maxMillisInsideHandleMessage;
    private final EventBus eventBus;
    /**
     * 是否可以处理活动
     * true:可以,false,正在处理
     */
    private boolean handlerActive;

    HandlerPoster(EventBus eventBus, Looper looper, int maxMillisInsideHandleMessage) {
        super(looper);
        this.eventBus = eventBus;
        this.maxMillisInsideHandleMessage = maxMillisInsideHandleMessage;
        queue = new PendingPostQueue();
    }

    /**
     * 添加一个事件到队列
     *
     * @param subscription
     * @param event
     */
    void enqueue(Subscription subscription, Object event) {
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            queue.enqueue(pendingPost);
            if (!handlerActive) {
                //正在处理事件
                handlerActive = true;
                if (!sendMessage(obtainMessage())) {
                    //发送失败
                    throw new EventBusException("Could not send handler message");
                }
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        boolean rescheduled = false;
        try {
            long started = SystemClock.uptimeMillis();
            while (true) {
                PendingPost pendingPost = queue.poll();
                if (pendingPost == null) {
                    synchronized (this) {
                        // Check again, this time in synchronized
                        pendingPost = queue.poll();
                        if (pendingPost == null) {
//                            只能同时处理一个事件
                            handlerActive = false;
                            return;
                        }
                    }
                }
                eventBus.invokeSubscriber(pendingPost);
                //时间上的判断,是否超时,这个有啥用...执行之后再统计?
                long timeInMethod = SystemClock.uptimeMillis() - started;
                if (timeInMethod >= maxMillisInsideHandleMessage) {
                    if (!sendMessage(obtainMessage())) {
                        throw new EventBusException("Could not send handler message");
                    }
                    rescheduled = true;
                    return;
                }
            }
        } finally {
            handlerActive = rescheduled;
        }
    }
}