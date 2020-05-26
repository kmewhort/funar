package com.kmewhort.funar;

import android.media.Image;

import org.opencv.core.Mat;

public interface ImagePreprocessor extends ImageProcessor {
    Mat process(Image img);

    int requiredInputFormat();

    void recallibrate();
    boolean isCallibrated();
    void setAutoCallibrate(boolean enable);
    boolean getAutoCallibrate();
    Mat getCallibration();
    void setCallibration(Mat warpMat);
}
