package com.bumptech.glide.load.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.bumptech.glide.util.Util;

/**
 * A class that can safely recycle recursive resources.
 * 资源回收者
 */
class ResourceRecycler {
    private boolean isRecycling;//当前是否处于回收中
    private final Handler handler = new Handler(Looper.getMainLooper(), new ResourceRecyclerCallback());//主线程的Handler

    public void recycle(Resource<?> resource) {
        Util.assertMainThread();

        if (isRecycling) {//当某一个资源还处于回收中，此时又有一个回收任务进来
            // If a resource has sub-resources, releasing a sub resource can cause it's parent to be synchronously
            // evicted which leads to a recycle loop when the parent releases it's children. Posting breaks this loop.
            // 为了避免一些意外的情况，将当前清除任务推到主线程的下一个任务中进行，这样可以在之前资源回收完成之后再运行
            // 从而实现同步化并且安全的回收资源
            handler.obtainMessage(ResourceRecyclerCallback.RECYCLE_RESOURCE, resource).sendToTarget();
        } else {
            isRecycling = true;
            resource.recycle();
            isRecycling = false;
        }
    }

    private static class ResourceRecyclerCallback implements Handler.Callback {
        public static final int RECYCLE_RESOURCE = 1;

        @Override
        public boolean handleMessage(Message message) {
            if (message.what == RECYCLE_RESOURCE) {
                Resource resource = (Resource) message.obj;
                resource.recycle();//尝试回收资源
                return true;
            }
            return false;
        }
    }
}
