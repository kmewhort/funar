package com.kmewhort.funar;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;

import org.opencv.core.Mat;

public class CalibrateShoeboxProjector implements ImageProcessor {
    public Mat process(Image img) {
        // we want to:
        // 1. Scan a red line on the screen from left to right;
        // 2. Simultaneously scan a blue line on the screen from top to bottom
        // 3. Gaussian blur the brightness values along each axis
        // 4. Normalize and threshold the brightness value
        // 5. Find the longest continuous line
        // 6. Re-determine the projection square
        return null;
    }

    public int requiredInputFormat() {
        return ImageFormat.JPEG;
    }

    public boolean isCallibrated() {
        return false;
    }
}
