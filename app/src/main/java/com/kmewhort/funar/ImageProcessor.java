package com.kmewhort.funar;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;

import org.opencv.core.Mat;

public interface ImageProcessor {
    Mat process(Image img);
    Mat process(Mat mat);

    void recallibrate();
    boolean isCallibrated();
    void setAutoCallibrate(boolean enable);
    boolean getAutoCallibrate();

    int requiredInputFormat();
}
