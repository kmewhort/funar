package com.kmewhort.funar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPIterator;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.properties.XMPPropertyInfo;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.xmp.XmpDirectory;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static android.bluetooth.BluetoothHidDeviceAppQosSettings.MAX;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static java.lang.Math.sqrt;
import static org.opencv.core.Core.NORM_MINMAX;
import static org.opencv.core.Core.normalize;
import static org.opencv.core.Core.split;
import static org.opencv.core.CvType.CV_16UC1;
import static org.opencv.core.CvType.CV_8U;
import static org.opencv.core.CvType.CV_8UC3;
import static org.opencv.imgproc.Imgproc.medianBlur;

public class CalibrateProjectionAreaProcessor implements ImageProcessor {
    private byte[] mImageData;
    private Bitmap mRgbBitmap;
    private Mat mRgbMat;

    private int mProcessingStartTime;
    private int mDepthProjectStartTime;

    private MatOfPoint2f mQuad;
    private Mat mWarpMat;

    public CalibrateProjectionAreaProcessor() {
        mProcessingStartTime = -1;
        mDepthProjectStartTime = -1;
        mQuad = null;
        mWarpMat = null;
    }

    public Bitmap process(Image img) {
        // TODO: flag to turn UI for calibration on/off

        if (mProcessingStartTime < 0)
            mProcessingStartTime = (int) (System.currentTimeMillis());

        decodeImage(img);

        // Phase 1: search for the brightest quadrilateral until found AND 5 seconds have past
        if (mQuad == null || ((int) (System.currentTimeMillis()) - mProcessingStartTime) < 8000) {
            findLargestBrightestQuad();
            return highlightedQuad(mRgbMat);
        }

        if (mDepthProjectStartTime < 0)
            mDepthProjectStartTime = (int) (System.currentTimeMillis());

        // Phase 2: show the depth image from depth JPEG, with the quadrilateral shown
        if ((int) (System.currentTimeMillis()) - mDepthProjectStartTime < 5000) {
            return highlightedQuad(depthMatrix());
        }

        // TODO: For faster depth imaging, feature match against the depth image in the Depth16
        // image and get a transform between the two

        // Phase 3: Zoom/warp just the projected screen and show the depth there
        Mat depth = depthMatrix();
        if(mWarpMat == null)
            calculatePerspectiveTransform(depth.width(), depth.height());
        Mat warped = new Mat();
        Imgproc.warpPerspective(depth, warped, mWarpMat, depth.size());

        Bitmap resultBmp = Bitmap.createBitmap(warped.width(), warped.height(), ARGB_8888);
        Utils.matToBitmap(warped, resultBmp);
        return resultBmp;
    }

    public int requiredInputFormat() {
        return ImageFormat.DEPTH_JPEG;
    }

    private void decodeImage(Image img) {
        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        mImageData = new byte[buffer.remaining()];
        buffer.get(mImageData);
        mRgbBitmap = BitmapFactory.decodeByteArray(mImageData, 0, mImageData.length);
        img.close();

        // TODO: there may be a way to go straight to Mat through OpenCV decode
        mRgbMat = new Mat(mRgbBitmap.getHeight(), mRgbBitmap.getWidth(), CvType.CV_8UC3);
        Utils.bitmapToMat(mRgbBitmap, mRgbMat);
    }

    private Mat depthMatrix() {
        try {
            ByteBuffer depthJpeg = (new JpegParser(mImageData)).getDepthMap();
            Bitmap bitmap = BitmapFactory.decodeByteArray(depthJpeg.array(), depthJpeg.arrayOffset(), depthJpeg.limit());

            Mat depthMat = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC3);
            Utils.bitmapToMat(bitmap, depthMat);
            return depthMat;
        } catch (JpegParser.JpegMarkerNotFound jpegMarkerNotFound) {
            jpegMarkerNotFound.printStackTrace();
        } catch (JpegParser.DepthImageNotFound depthImageNotFound) {
            depthImageNotFound.printStackTrace();
        }
        return null;
    }

    private Bitmap highlightedQuad(Mat baseImage) {
        if (mQuad == null)
            return null;

        MatOfPoint2f quad2f = scaledQuad(baseImage.width(), baseImage.height());

        MatOfPoint quad = new MatOfPoint();
        quad2f.convertTo(quad, CvType.CV_32S);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        contours.add(quad);

        Imgproc.drawContours(baseImage, contours, 0, new Scalar(0, 255, 0), 5);
        Bitmap resultBmp = Bitmap.createBitmap(baseImage.width(), baseImage.height(), ARGB_8888);
        Utils.matToBitmap(baseImage, resultBmp);
        return resultBmp;
    }

    private MatOfPoint2f scaledQuad(double width, double height) {
        MatOfPoint2f quad2f = new MatOfPoint2f();
        double xScale = width / mRgbMat.width();
        double yScale = height / mRgbMat.height();
        Core.multiply(mQuad, new Scalar(xScale, yScale), quad2f);
        return quad2f;
    }

    // based loosely on Karl Phillip: https://stackoverflow.com/questions/8667818/opencv-c-obj-c-detecting-a-sheet-of-paper-square-detection/14368605#14368605
    private MatOfPoint2f findLargestBrightestQuad() {
        // value channel from HSV works best for brightness
        Mat gray8 = this.hsvValueChannel();

        // blur and downsample
        int SIZE_REDUCTION = 4;
        Imgproc.GaussianBlur(gray8, gray8, new Size(31, 31), 0);
        Imgproc.resize(gray8, gray8, new Size(gray8.width() / SIZE_REDUCTION, gray8.height() / SIZE_REDUCTION));

        // find the quadrilaterals
        List<MatOfPoint2f> quads = findQuadContours(gray8);

        // find the biggest
        MatOfPoint2f largestSquare = null;
        double largestSquareArea = 0;
        for (int i = 0; i < quads.size(); i++) {
            MatOfPoint2f quad = quads.get(i);
            double area = Math.abs(Imgproc.contourArea(quad));
            if (area > largestSquareArea) {
                largestSquare = quad;
                largestSquareArea = area;
            }
        }

        MatOfPoint2f rescaled = new MatOfPoint2f();
        if (largestSquare != null) {
            Core.multiply(largestSquare, new Scalar(SIZE_REDUCTION, SIZE_REDUCTION), largestSquare);
        }

        mQuad = largestSquare;
        return largestSquare;
    }

    private List<MatOfPoint2f> findQuadContours(Mat gray8) {
        List<MatOfPoint2f> result = new ArrayList<MatOfPoint2f>();

        // the projection should be by far the brightest area - use two high thresholds
        int[] thresholds = new int[]{200, 250};
        for (int i = 0; i < thresholds.length; i++) {
            Imgproc.threshold(gray8, gray8, thresholds[i], 255, 0);

            List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(gray8, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            // find the contours that are actually quadrilaterals
            for (int j = 0; j < contours.size(); j++) {
                MatOfPoint2f contourf = new MatOfPoint2f(contours.get(j).toArray());

                // simplify the contour (proportional to arc length)
                double arclength = Imgproc.arcLength(new MatOfPoint2f(contourf), true);
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(
                        contourf,
                        approx,
                        arclength * 0.02,
                        true);

                if (approx.rows() == 4)
                    result.add(approx);
            }
        }
        return result;
    }

    private Mat calculatePerspectiveTransform(int targetWidth, int targetHeight) {
        // based on https://stackoverflow.com/questions/40688491/opencv-getperspectivetransform-and-warpperspective-java

        //calculate the center of mass of our contour image using moments
        MatOfPoint2f quad2f = scaledQuad(targetWidth, targetHeight);
        Moments moment = Imgproc.moments(quad2f);
        int x = (int) (moment.get_m10() / moment.get_m00());
        int y = (int) (moment.get_m01() / moment.get_m00());

        // sort points relative to the centre of mass
        Point[] sortedPoints = new Point[4];

        double[] data;
        int count = 0;
        for(int i=0; i < quad2f.rows(); i++){
            data = quad2f.get(i, 0);
            double datax = data[0];
            double datay = data[1];
            if(datax < x && datay < y){
                sortedPoints[0]=new Point(datax,datay);
                count++;
            }else if(datax > x && datay < y){
                sortedPoints[1]=new Point(datax,datay);
                count++;
            }else if (datax < x && datay > y){
                sortedPoints[2]=new Point(datax,datay);
                count++;
            }else if (datax > x && datay > y){
                sortedPoints[3]=new Point(datax,datay);
                count++;
            }
        }

        MatOfPoint2f src = new MatOfPoint2f(
                sortedPoints[0],
                sortedPoints[1],
                sortedPoints[2],
                sortedPoints[3]);

        MatOfPoint2f dst = new MatOfPoint2f(
                new Point(0, 0),
                new Point(targetWidth-1,0),
                new Point(0,targetHeight-1),
                new Point(targetWidth-1,targetHeight-1)
        );

        mWarpMat = Imgproc.getPerspectiveTransform(src,dst);
        return mWarpMat;
    }

    private Mat hsvValueChannel() {
        // convert to hsv-space, then split the channels
        Mat hsv = new Mat(mRgbMat.height(), mRgbMat.width(), CV_8UC3);
        Imgproc.cvtColor(mRgbMat, hsv, Imgproc.COLOR_BGR2HSV);
        List<Mat> splitMat = new ArrayList<Mat>(3);
        split(hsv, splitMat);
        Mat gray8 = splitMat.get(2);
        return gray8;
    }

    private double angle(Point pt1, Point pt2, Point pt0) {
        double dx1 = pt1.x - pt0.x;
        double dy1 = pt1.y - pt0.y;
        double dx2 = pt2.x - pt0.x;
        double dy2 = pt2.y - pt0.y;
        return (dx1 * dx2 + dy1 * dy2) / sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10);
    }

    private Bitmap contourDebug2f(List<MatOfPoint2f> contours2f) {
        if (contours2f.size() == 0)
            return null;

        List<MatOfPoint> contours = new ArrayList<>();

        for (int i = 0; i < contours2f.size(); i++) {
            MatOfPoint s = new MatOfPoint();
            contours2f.get(i).convertTo(s, CvType.CV_32S);
            contours.add(s);
        }

        return contourDebug(contours);
    }

    private Bitmap contourDebug(List<MatOfPoint> contours) {
        if (contours.size() == 0)
            return null;

        Bitmap resultBmp = Bitmap.createBitmap(mRgbMat.width(), mRgbMat.height(), ARGB_8888);
        //Imgproc.drawContours(mRgbMat, contours, -1, new Scalar(0, 255, 0), 30);
        for (int i = 0; i < contours.size(); i++) {
            Imgproc.drawContours(mRgbMat, contours, i, new Scalar(0, 255, 0), 30);
        }
        Utils.matToBitmap(mRgbMat, resultBmp);
        return resultBmp;
    }
}
