package com.kmewhort.funar;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import java.nio.ByteBuffer;

import android.renderscript.*;
import com.kmewhort.funar.ScriptC_yuv420888;

import org.opencv.calib3d.StereoBM;
import org.opencv.calib3d.StereoSGBM;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.android.Utils;
import org.opencv.imgproc.Imgproc;

import static android.graphics.Bitmap.Config.ALPHA_8;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static org.opencv.core.Core.NORM_MINMAX;
import static org.opencv.core.Core.normalize;
import static org.opencv.core.CvType.CV_8U;

public class StereoImageProcessor {
    private RenderScript rs;
    private StereoSGBM stereoSGBM;

    public StereoImageProcessor(Context ctx) {
        rs = RenderScript.create(ctx);
        stereoSGBM = StereoSGBM.create(); // TODO: set num disparities
    }

    public Bitmap calculateDisparity(Bitmap leftBmp, Bitmap rightBmp) {
        // TODO: get rid of some of these conversions - eg straight to Mat from YUV420
        // TODO: calc each channel seperately and recombine? Or actually, looks like
        // multichannel can be fed to StereoSGBM

        // RGB Bitmap -> Mat RGB
        Mat lMatRgb = new Mat();
        Mat rMatRgb = new Mat();
        Utils.bitmapToMat(leftBmp, lMatRgb);
        Utils.bitmapToMat(rightBmp, rMatRgb);

        // Mat RGB -> Mat gray
        /*
        Mat lMatGray = new Mat();
        Mat rMatGray = new Mat();
        Imgproc.cvtColor(lMatRgb, lMatGray, Imgproc.COLOR_RGB2GRAY);
        Imgproc.cvtColor(rMatRgb, rMatGray, Imgproc.COLOR_RGB2GRAY);
         */

        // disparity computation
        Mat resultMatGray16 = new Mat();
        stereoSGBM.compute(lMatRgb, rMatRgb, resultMatGray16);

        // Gray Mat -> Bitmap
        Mat resultMatGray8 = new Mat(resultMatGray16.width(), resultMatGray16.height(), CV_8U);
        //resultMatGray16.convertTo(resultMatGray8, CV_8U, 1.0/257.0);
        normalize(resultMatGray16, resultMatGray8, 0, 255, NORM_MINMAX, CV_8U);

        Mat resultMatRgb = new Mat();
        Imgproc.cvtColor(resultMatGray8, resultMatRgb, Imgproc.COLOR_GRAY2RGBA);
        Bitmap resultBmp = Bitmap.createBitmap(resultMatRgb.width(), resultMatRgb.height(), ARGB_8888);
        Utils.matToBitmap(resultMatRgb, resultBmp);
        return resultBmp;
    }

    // adapted from https://stackoverflow.com/questions/36212904/yuv-420-888-interpretation-on-samsung-galaxy-s7-camera2
    public Bitmap YUV_420_888_toRGB(Image image, int width, int height){
        // Get the three image planes
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        byte[] y = new byte[buffer.remaining()];
        buffer.get(y);

        buffer = planes[1].getBuffer();
        byte[] u = new byte[buffer.remaining()];
        buffer.get(u);

        buffer = planes[2].getBuffer();
        byte[] v = new byte[buffer.remaining()];
        buffer.get(v);

        // get the relevant RowStrides and PixelStrides
        // (we know from documentation that PixelStride is 1 for y)
        int yRowStride= planes[0].getRowStride();
        int uvRowStride= planes[1].getRowStride();  // we know from   documentation that RowStride is the same for u and v.
        int uvPixelStride= planes[1].getPixelStride();  // we know from   documentation that PixelStride is the same for u and v.


        // rs creation just for demo. Create rs just once in onCreate and use it again.

        ScriptC_yuv420888 mYuv420=new ScriptC_yuv420888(rs);

        // Y,U,V are defined as global allocations, the out-Allocation is the Bitmap.
        // Note also that uAlloc and vAlloc are 1-dimensional while yAlloc is 2-dimensional.
        Type.Builder typeUcharY = new Type.Builder(rs, Element.U8(rs));
        typeUcharY.setX(yRowStride).setY(height);
        Allocation yAlloc = Allocation.createTyped(rs, typeUcharY.create());
        yAlloc.copyFrom(y);
        mYuv420.set_ypsIn(yAlloc);

        Type.Builder typeUcharUV = new Type.Builder(rs, Element.U8(rs));
        // note that the size of the u's and v's are as follows:
        //      (  (width/2)*PixelStride + padding  ) * (height/2)
        // =    (RowStride                          ) * (height/2)
        // but I noted that on the S7 it is 1 less...
        typeUcharUV.setX(u.length);
        Allocation uAlloc = Allocation.createTyped(rs, typeUcharUV.create());
        uAlloc.copyFrom(u);
        mYuv420.set_uIn(uAlloc);

        Allocation vAlloc = Allocation.createTyped(rs, typeUcharUV.create());
        vAlloc.copyFrom(v);
        mYuv420.set_vIn(vAlloc);

        // handover parameters
        mYuv420.set_picWidth(width);
        mYuv420.set_uvRowStride (uvRowStride);
        mYuv420.set_uvPixelStride (uvPixelStride);

        Bitmap outBitmap = Bitmap.createBitmap(width, height, ARGB_8888);
        Allocation outAlloc = Allocation.createFromBitmap(rs, outBitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);

        Script.LaunchOptions lo = new Script.LaunchOptions();
        lo.setX(0, width);  // by this we ignore the y’s padding zone, i.e. the right side of x between width and yRowStride
        lo.setY(0, height);

        mYuv420.forEach_doConvert(outAlloc,lo);
        outAlloc.copyTo(outBitmap);

        return outBitmap;
    }
}
