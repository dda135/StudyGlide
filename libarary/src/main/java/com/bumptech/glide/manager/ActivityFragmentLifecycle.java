package com.bumptech.glide.manager;

import com.bumptech.glide.util.Util;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A {@link com.bumptech.glide.manager.Lifecycle} implementation for tracking and notifying listeners of
 * {@link android.app.Fragment} and {@link android.app.Activity} lifecycle events.
 * Activity和Fragment用的生命周期回调
 */
class ActivityFragmentLifecycle implements Lifecycle {
    //生命周期注册的监听(回调)者，这里通过弱应用进行自动回收
    private final Set<LifecycleListener> lifecycleListeners =
            Collections.newSetFromMap(new WeakHashMap<LifecycleListener, Boolean>());
    private boolean isStarted;
    private boolean isDestroyed;

    /**
     * Adds the given listener to the list of listeners to be notified on each lifecycle event.
     *
     * <p>
     *     The latest lifecycle event will be called on the given listener synchronously in this method. If the
     *     activity or fragment is stopped, {@link LifecycleListener#onStop()}} will be called, and same for onStart and
     *     onDestroy.
     * </p>
     *
     * <p>
     *     Note - {@link com.bumptech.glide.manager.LifecycleListener}s that are added more than once will have their
     *     lifecycle methods called more than once. It is the caller's responsibility to avoid adding listeners
     *     multiple times.
     * </p>
     */
    @Override
    public void addListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);
        //因为不确定添加监听的时机
        //这里是尽可能的进行回调，使用的时候稍微注意
        //在一个场景下，比方说addListener在onStart之后调用
        //但是实际上要启到的作用就是开启RequestTracker
        //所以这种情况下也要进行处理
        if (isDestroyed) {
            listener.onDestroy();
        } else if (isStarted) {
            listener.onStart();
        } else {
            listener.onStop();
        }
    }

    /**
     * 下面只是在指定时间回调指定的监听
     */


    void onStart() {
        isStarted = true;
        for (LifecycleListener lifecycleListener : Util.getSnapshot(lifecycleListeners)) {
            lifecycleListener.onStart();
        }
    }

    void onStop() {
        isStarted = false;
        for (LifecycleListener lifecycleListener : Util.getSnapshot(lifecycleListeners)) {
            lifecycleListener.onStop();
        }
    }

    void onDestroy() {
        isDestroyed = true;
        for (LifecycleListener lifecycleListener : Util.getSnapshot(lifecycleListeners)) {
            lifecycleListener.onDestroy();
        }
    }
}
