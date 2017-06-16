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

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 在编译的类文件中,编译器可能添加了方法<br/>
 * 他们是桥接和合成方法<br/>
 * EventBus必须忽略他们<br/>
 * 这些方法在java文件中并不是public的<br/>
 * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
 * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
 * <a href="http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1">参考<a/>
 */
class SubscriberMethodFinder {
    /**
     * 由于不是public的,直接定义出来
     * {link {@link Modifier#BRIDGE}}
     */
    private static final int BRIDGE = 0x40;
    /**
     * 由于不是public的,直接定义出来
     * {link {@link Modifier#SYNTHETIC}}
     */
    private static final int SYNTHETIC = 0x1000;

    /**
     * 忽略以什么标记的类后面两个不知道啥意思,jdk文档里也没有,,好奇怪
     * 抽象,静态修饰的方法忽略
     */
    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;

    /**
     * 方法缓存<br/>
     * <a href="http://ifeve.com/ConcurrentHashMap/">ConcurrentHashMap参考<a/><br/>
     * 最多支持16个线程同时操作<br/>
     * 由于一些更新操作，如put(),remove(),putAll(),clear()只锁住操作的部分，所以在检索操作不能保证返回的是最新的结果。
     */
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();
    /**
     * 索引
     */
    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    /**
     * 是否严格的方法验证,如果方法定义不符合要求切这个变量为true,那么直接报错
     * 如果false,忽略那个方法,不会报错
     */
    private final boolean strictMethodVerification;
    /**
     * 是否忽略Apt生成的索引信息.默认false
     */
    private final boolean ignoreGeneratedIndex;
    /**
     * 线程池大小
     */
    private static final int POOL_SIZE = 4;

    /**
     * 查找封装类池
     */
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    /**
     * 查找订阅的方法
     *
     * @param subscriberClass EventType
     * @return
     */
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        //从缓存拿
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        //是否忽略了自动生成的索引
        if (ignoreGeneratedIndex) {
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            subscriberMethods = findUsingInfo(subscriberClass);
        }
        if (subscriberMethods.isEmpty()) {
            //如果没有订阅方法
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            //放入缓存
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findState.subscriberInfo = getSubscriberInfo(findState);
            if (findState.subscriberInfo != null) {
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                findUsingReflectionInSingleClass(findState);
            }
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    /**
     * 从FindState里面拿到订阅者方法集合<br/>
     * 并将笨findState对象添加到池中
     *
     * @param findState
     * @return
     */
    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        findState.recycle();
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        return subscriberMethods;
    }

    /**
     * 从池中获取一个FindState
     *
     * @return
     */
    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    private SubscriberInfo getSubscriberInfo(FindState findState) {
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * 利用反射获取
     *
     * @param subscriberClass
     * @return
     */
    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        //将订阅者传递过去
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findUsingReflectionInSingleClass(findState);
            //当没有父类的时候跳出循环
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    /**
     * 反射查找一个类总的订阅方法
     *
     * @param findState
     */
    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            //getDeclaredMethods()比getMethod方法快,getMethod查找的不仅是本类的,所有的父类的也会列出来
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            //在某些设备上会报java.lang.NoClassDefFoundError 错误
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            methods = findState.clazz.getMethods();
            //如果使用了获取所有方法,那么跳过检查父类
            findState.skipSuperClasses = true;
        }
        for (Method method : methods) {
            //获取方法修饰符
            int modifiers = method.getModifiers();
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                //方法是public的并且不是忽略方法
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    //参数为数目为1
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    if (subscribeAnnotation != null) {
                        Class<?> eventType = parameterTypes[0];
                        if (findState.checkAdd(method, eventType)) {
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    //参数数量不是1的情况
                    //参数多个并且使用了严格的方法检查,并且是使用#Subscribe注解的
                    //在这里就可以看出strictMethodVerification这个属性的作用了,就是在方法验证的时候,如果不符合要求,是否强制报错
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                //如果修饰符不是public
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    /**
     * 订阅方法查找结果的封装
     */
    static class FindState {
        //订阅方法集合
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        //eventType-FindState
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        /**
         * 方法全名-所在类
         */
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        /**
         * 其实装的是 com.yzl.ClassName>methodName
         */
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        Class<?> subscriberClass;
        /**
         * EventType对应的class文件
         */
        Class<?> clazz;
        //是否跳过父类
        boolean skipSuperClasses;
        //订阅信息
        SubscriberInfo subscriberInfo;

        /**
         * 初始化
         *
         * @param subscriberClass
         */
        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        /**
         * 重用,清除信息
         */
        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        /**
         * 二级检查,通常是一个方法只能监听一个事件,应该是这样子理解
         * 二级检查需要完整的签名
         *
         * @param method
         * @param eventType
         * @return
         */
        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
                return true;
            } else {
                if (existing instanceof Method) {
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    anyMethodByEventType.put(eventType, this);
                }
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        /**
         * 检查方法签名
         *
         * @param method
         * @param eventType
         * @return
         */
        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());

            String methodKey = methodKeyBuilder.toString();
            //获取申明类
            Class<?> methodClass = method.getDeclaringClass();
            //旧的类
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                //isAssignableFrom:判断一个类Class1和另一个类Class2是否相同或是另一个类的子类或接口
                // Only add if not already found in a sub class
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                //重新将旧对象放进去,旧的类下降了类的层次结构?????不懂
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        /**
         * 转移到父类,查找父类type的方法
         */
        void moveToSuperclass() {
            if (skipSuperClasses) {
                //不忽略父类
                clazz = null;
            } else {
                //忽略父类
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                /** 跳过系统jdk类和Android sdk 的类,提高速度*/
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") || clazzName.startsWith("android.")) {
                    clazz = null;
                }
            }
        }
    }

}
