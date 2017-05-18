package com.bumptech.glide.manager;

import com.bumptech.glide.request.Request;
import com.bumptech.glide.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A class for tracking, canceling, and restarting in progress, completed, and failed requests.
 * 一个与Request相关，并且可以用于取消、启动等行为的Request追踪者
 */
public class RequestTracker {
    // Most requests will be for views and will therefore be held strongly (and safely) by the view via the tag.
    // However, a user can always pass in a different type of target which may end up not being strongly referenced even
    // though the user still would like the request to finish. Weak references are therefore only really functional in
    // this context for view targets. Despite the side affects, WeakReferences are still essentially required. A user
    // can always make repeated requests into targets other than views, or use an activity manager in a fragment pager
    // where holding strong references would steadily leak bitmaps and/or views.
    // 注意到RequestTracker并没有可以在请求完成后清理Request，从防止view的内存泄露的角度出发，这里使用弱应用效果最佳
    private final Set<Request> requests = Collections.newSetFromMap(new WeakHashMap<Request, Boolean>());
    // A set of requests that have not completed and are queued to be run again. We use this list to maintain hard
    // references to these requests to ensure that they are not garbage collected before they start running or
    // while they are paused. See #346.
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    // 这里注意一下因为requests是弱应用，有一定的可能导致一些暂停的Request被回收
    // 所以这里通过额外的队列保持强引用，以避免暂停之后被回收的情况
    private final List<Request> pendingRequests = new ArrayList<Request>();
    // 当前请求工厂的状态
    private boolean isPaused;

    /**
     * Starts tracking the given request.
     * 启动制定的请求
     */
    public void runRequest(Request request) {
        requests.add(request);//添加到请求集合当中
        if (!isPaused) {
            request.begin();//开始执行
        } else {
            //当前不允许开始请求，保持强应用，等待重新执行
            pendingRequests.add(request);
        }
    }

    // Visible for testing.
    void addRequest(Request request) {
        requests.add(request);
    }

    /**
     * Stops tracking the given request.
     * 移除某一个指定的请求
     */
    public void removeRequest(Request request) {
        requests.remove(request);
        pendingRequests.remove(request);
    }

    /**
     * Returns {@code true} if requests are currently paused, and {@code false} otherwise.
     */
    public boolean isPaused() {
        return isPaused;
    }

    /**
     * Stops any in progress requests.
     * 暂停所有进行中的请求
     */
    public void pauseRequests() {
        isPaused = true;//标记当前处于暂停状态
        for (Request request : Util.getSnapshot(requests)) {
            if (request.isRunning()) {//当前请求运行当中
                request.pause();//暂停当前请求
                pendingRequests.add(request);//保持强应用等待重新开始
            }
        }
    }

    /**
     * Starts any not yet completed or failed requests.
     * 恢复请求，主要是针对那些没有完成、取消并且没有处于运行中的
     */
    public void resumeRequests() {
        isPaused = false;//标记当前不处于暂停状态中
        for (Request request : Util.getSnapshot(requests)) {
            //当前请求没有完成、没有被取消并且没有处于运行当中
            if (!request.isComplete() && !request.isCancelled() && !request.isRunning()) {
                request.begin();//开始请求
            }
        }
        //请求已经全部重新开始，可以清空它们的强引用
        pendingRequests.clear();
    }

    /**
     * Cancels all requests and clears their resources.
     * 清空当前所有的请求和资源
     */
    public void clearRequests() {
        //清空所有的请求和引用
        for (Request request : Util.getSnapshot(requests)) {
            request.clear();
        }
        pendingRequests.clear();
    }

    /**
     * Restarts failed requests and cancels and restarts in progress requests.
     * 条件允许的话重新启动那些没有完成且没有被取消的请求
     */
    public void restartRequests() {
        for (Request request : Util.getSnapshot(requests)) {
            if (!request.isComplete() && !request.isCancelled()) {
                // Ensure the request will be restarted in onResume.
                //先暂停当前请求
                request.pause();
                //如果当前RequestTracker没有处于暂停状态
                //启动请求，否则就保留直到下次启动
                if (!isPaused) {
                    request.begin();
                } else {
                    pendingRequests.add(request);
                }
            }
        }
    }
}
