package com.kmewhort.funar.preprocessors;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;

import com.kmewhort.funar.preprocessors.Depth16Processor;
import com.kmewhort.funar.preprocessors.DepthJpegProcessor;
import com.kmewhort.funar.preprocessors.ImagePreprocessor;
import com.kmewhort.funar.preprocessors.RgbJpegProcessor;
import com.kmewhort.funar.processors.ImageProcessor;

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

import java.util.ArrayList;
import java.util.List;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static java.lang.Math.sqrt;
import static org.opencv.core.Core.normalize;
import static org.opencv.core.Core.split;
import static org.opencv.core.CvType.CV_8UC3;

public class ProjectionAreaProcessor extends ImagePreprocessor {
    private static final int RECALIBRATE_FRAME_COUNT = 20;
    private static final int PROJECTOR_FRAME_LATENCY = 2;

    // downsample scale for finding the quad
    private static final int SIZE_REDUCTION = 2;

    private long mProcessingStartTime;
    private long mDepthProjectStartTime;

    private MatOfPoint2f mQuad;
    private MatOfPoint2f mScaledDownQuad;
    private Mat mWarpMat;

    private int mWhiteFlashCount;

    private boolean mVisualCallibration;
    private boolean mAutoCallibration;

    private int mFrameCount;

    private Mat mDepthMat;
    private Mat mRgbMat;

    boolean mUseDepth16;
    boolean mColorOutput; // vs depth
    private ImagePreprocessor mDepthBackend;

    public ProjectionAreaProcessor(boolean outputRgb, boolean useDepth16) {
        mUseDepth16 = useDepth16;
        mColorOutput = outputRgb;
        if(useDepth16)
            mDepthBackend = new Depth16Processor();
        else
            mDepthBackend = new DepthJpegProcessor();

        mVisualCallibration = true;
        mAutoCallibration = false;
        recallibrate();
    }

    public Mat process(Image img) {
        if (!isCallibrated()) {
            DepthJpegProcessor depthProcessor = new DepthJpegProcessor();
            mDepthMat = depthProcessor.process(img);
            if(mDepthMat == null) return null;

            // for visual callibration, we need the RGB image too
            if (mVisualCallibration) {
                mRgbMat = (new RgbJpegProcessor()).process(depthProcessor.getRawImageData());
            }
        } else {
            if(mColorOutput) {
                mRgbMat = (new RgbJpegProcessor()).process(img);
                if(mRgbMat == null) return null;
            } else {
                mDepthMat = mDepthBackend.process(img);
                if(mDepthMat == null) return null;
            }

        }

        return process(mDepthMat);
    }

    public Mat process(Mat mat) {
        if(++mFrameCount % RECALIBRATE_FRAME_COUNT == 0 && mAutoCallibration) {
            recallibrate();
        }

        if(!isCallibrated()) {
            if (!mVisualCallibration) {
                // start off flashing a white square
                // TODO: would be nicer to just border the regular output
                if (mWhiteFlashCount++ < PROJECTOR_FRAME_LATENCY) {
                    return whiteFlashMat();
                }

                // callibrate if we haven't figured out our projection yet
                if (mQuad == null) {
                    findLargestBrightestQuad();
                    if (mQuad == null)
                        return whiteFlashMat(); //try again next frame

                    calculatePerspectiveTransform();
                }
            } else {
                // timed stages of callibration that we sho on screen
                if (mProcessingStartTime < 0)
                    mProcessingStartTime = System.currentTimeMillis();

                // Phase 1: search for the brightest quadrilateral until found AND 6 seconds have past
                if (mQuad == null || ((System.currentTimeMillis() - mProcessingStartTime) < 6000)) {
                    findLargestBrightestQuad();
                    Mat output = addWhiteBorder(mRgbMat);
                    if (mQuad == null)
                        return output;
                    else
                        return highlightedQuad(output);
                }

                if (mDepthProjectStartTime < 0)
                    mDepthProjectStartTime = System.currentTimeMillis();

                // Phase 2: show the depth image from depth JPEG, with the quadrilateral shown
                if ((System.currentTimeMillis() - mDepthProjectStartTime) < 1000) {
                    return highlightedQuad(mDepthMat);
                }

                // TODO: For faster depth imaging, feature match against the depth image in the Depth16
                // image and get a transform between the two

                // Phase 3: Zoom/warp just the projected screen and show the depth there
                if (mWarpMat == null) {
                    calculatePerspectiveTransform();
                    // if this fails (hitting this very sporadically, stemming from a failure
                    // to find the sorted points), back to square 1
                    if (mWarpMat == null) {
                        recallibrate();
                        return null;
                    }
                }
            }
        }

        Mat warped = new Mat();
        Mat target = null;
        if(mColorOutput)
            target = mRgbMat;
        else
            target = mDepthMat;
        Imgproc.warpPerspective(target, warped, mWarpMat, target.size());
        return warped;
    }

    public int requiredInputFormat() {
        if(!isCallibrated())
            return ImageFormat.DEPTH_JPEG;

        return mUseDepth16 ? ImageFormat.DEPTH16 : ImageFormat.DEPTH_JPEG;
    }


    public boolean isCallibrated() {
        return mQuad != null && mWarpMat != null;
    }

    public void recallibrate() {
        mProcessingStartTime = -1;
        mDepthProjectStartTime = -1;
        mQuad = null;
        mWarpMat = null;
        mWhiteFlashCount = 0;
        mFrameCount = 0;
    }

    public void setAutoCallibrate(boolean enable) {
        mAutoCallibration = enable;
        mVisualCallibration = !enable;
    }

    public boolean getAutoCallibrate() {
        return mAutoCallibration;
    }

    public MatOfPoint2f getCallibration() {
        return mScaledDownQuad;
    }

    public void setCallibration(MatOfPoint2f quad) {
        mScaledDownQuad = quad;
        mQuad = scaleQuadToOutputSize(mScaledDownQuad);
        mWarpMat = null;
    }

    public void setVisualCallibrationMode(boolean visual) {
        mVisualCallibration = visual;
    }

    // whether to output depth or a colour image
    public void setColorOutput(boolean color) {
        if(mColorOutput == color)
            return;
        mColorOutput = color;
        if(mWarpMat != null)
            calculatePerspectiveTransform();
    }

    private Mat highlightedQuad(Mat baseImage) {
        if (mQuad == null)
            return null;

        MatOfPoint2f quad2f = scaledQuad(baseImage.width(), baseImage.height());

        MatOfPoint quad = new MatOfPoint();
        quad2f.convertTo(quad, CvType.CV_32S);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        contours.add(quad);

        Imgproc.drawContours(baseImage, contours, 0, new Scalar(0, 255, 0), 5);
        return baseImage;
    }

    private MatOfPoint2f scaledQuad(double width, double height) {
        if(width == mRgbMat.width())
            return mQuad;
        else if(width == mRgbMat.width()/SIZE_REDUCTION)
            return mScaledDownQuad;

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

        mScaledDownQuad = largestSquare;
        mQuad = scaleQuadToOutputSize(mScaledDownQuad);

        return mQuad;
    }

    private MatOfPoint2f scaleQuadToOutputSize(MatOfPoint2f scaledDownQuad) {
        if(scaledDownQuad == null)
            return null;
        MatOfPoint2f quad = new MatOfPoint2f();
        Core.multiply(scaledDownQuad, new Scalar(SIZE_REDUCTION, SIZE_REDUCTION), quad);
        Core.add(quad, new Scalar(SIZE_REDUCTION/2, SIZE_REDUCTION/2), quad);
        return quad;
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

                if (approx.rows() == 4 && (sortQuadPoints(approx) != null))
                    result.add(approx);
            }
        }
        return result;
    }

    private Point[] sortQuadPoints(MatOfPoint2f quad2f) {
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

        for(int i=0; i < sortedPoints.length; i++) {
            if(sortedPoints[i] == null)
                return null;
        }

        return sortedPoints;
    }

    private Mat calculatePerspectiveTransform() {
        int targetWidth, targetHeight;
        if(mColorOutput) {
            targetWidth = mRgbMat.width();
            targetHeight = mRgbMat.height();
        } else {
            targetWidth = mDepthMat.width();
            targetHeight = mDepthMat.height();
        }

        // based on https://stackoverflow.com/questions/40688491/opencv-getperspectivetransform-and-warpperspective-java
        MatOfPoint2f quad2f = scaledQuad(targetWidth, targetHeight);

        Point[] sortedPoints = sortQuadPoints(quad2f);
        if(sortedPoints == null)
            return null;

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

    private Mat whiteFlashMat() {
        // align to the depth image height/weight; it's smaller
        return new Mat(mDepthMat.height(), mDepthMat.width(), CvType.CV_8UC3, new Scalar(255,255,255));
    }

    private Mat addWhiteBorder(Mat input) {
        Point[] imageCorners = new Point[4];
        imageCorners[0] = new Point(0,0);
        imageCorners[1] = new Point(input.width()-1, 0);
        imageCorners[2] = new Point(input.width()-1, input.height()-1);
        imageCorners[3] = new Point(0, input.height()-1);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        contours.add(new MatOfPoint(imageCorners));
        Imgproc.drawContours(input, contours, 0, new Scalar(255, 255, 255), 400);
        return input;
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
