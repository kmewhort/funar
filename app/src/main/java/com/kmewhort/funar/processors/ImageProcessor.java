package com.kmewhort.funar.processors;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;

public abstract class ImageProcessor {
    public ImageProcessor() { }

    abstract public Mat process(Mat mat);
}
