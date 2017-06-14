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

import java.lang.reflect.Method;

/**
 * Used internally by EventBus and generated subscriber indexes.
 * 订阅方法类
 */
public class SubscriberMethod {
    /**
     * 方法
     */
    final Method method;
    /**
     * 线程类型
     */
    final ThreadMode threadMode;
    /**
     * 事件类型,其实就是Event.post(new Object.class);
     */
    final Class<?> eventType;
    /**
     * 优先级
     */
    final int priority;
    /**
     * !!!
     */
    final boolean sticky;
    /**
     * 用于高效的比较?不知道啥意思
     * Used for efficient comparison
     */
    String methodString;

    public SubscriberMethod(Method method, Class<?> eventType, ThreadMode threadMode, int priority, boolean sticky) {
        this.method = method;
        this.threadMode = threadMode;
        this.eventType = eventType;
        this.priority = priority;
        this.sticky = sticky;
    }

    /**
     * 比较
     *
     * @param other
     * @return
     */
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        } else if (other instanceof SubscriberMethod) {
            checkMethodString();
            SubscriberMethod otherSubscriberMethod = (SubscriberMethod) other;
            otherSubscriberMethod.checkMethodString();
            // Don't use method.equals because of http://code.google.com/p/android/issues/detail?id=7811#c6
            //之所以不使用Method.equals,是因为该方法会浪费大量的时间
            //所以采用拼接方法字符串的方法来完成对比
            return methodString.equals(otherSubscriberMethod.methodString);
        } else {
            return false;
        }
    }

    /**
     * 拼接方法信息<br/>
     * className#methodName(eventTypeName)
     */
    private synchronized void checkMethodString() {
        if (methodString == null) {
            // Method.toString has more overhead, just take relevant parts of the method
            StringBuilder builder = new StringBuilder(64);
            builder.append(method.getDeclaringClass().getName());
            builder.append('#').append(method.getName());
            builder.append('(').append(eventType.getName());
            methodString = builder.toString();
        }
    }

    @Override
    public int hashCode() {
        return method.hashCode();
    }
}