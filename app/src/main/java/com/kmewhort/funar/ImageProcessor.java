package com.kmewhort.funar;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;

public interface ImageProcessor {
    Bitmap process(Image img);
    int requiredInputFormat();
}
