// adapted from https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/HandlerExecutor.java

package com.kmewhort.funar;

/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.util.concurrent.Executor;
import android.os.Handler;
import android.util.Log;


public class HandlerExecutor implements Executor {
    private static final String TAG = "HandlerExecutor";

    private final Handler mHandler;
    public HandlerExecutor(Handler handler) {
        mHandler = handler;
    }

    @Override
    public void execute(Runnable command) {
        // run on a best effort basis
        try {
            if (!mHandler.post(command)) {
                Log.e(TAG, "Could not post (shutting down?)");
            }
        } catch(IllegalStateException e) {
            Log.e(TAG, "Could not post (IllegalStateException)");
        }
    }
}