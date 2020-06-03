package com.kmewhort.funar;

import android.media.Image;

import com.kmewhort.funar.preprocessors.Depth16Processor;
import com.kmewhort.funar.preprocessors.DepthJpegProcessor;
import com.kmewhort.funar.preprocessors.ImagePreprocessor;
import com.kmewhort.funar.preprocessors.ProjectionAreaProcessor;
import com.kmewhort.funar.processors.ContourGenerator;
import com.kmewhort.funar.processors.ImageProcessor;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;

import java.util.ArrayList;

// this class runs a group of effects and provides for selecting an effect group
public class EffectRunner extends ImagePreprocessor {
    private class EffectGroup {
        EffectGroup(ImagePreprocessor effect) {
            processors = new ArrayList<ImageProcessor>();
            processors.add(effect);
        }
        EffectGroup(ImagePreprocessor preprocessor, ImageProcessor effect) {
            this(preprocessor);
            processors.add(effect);
        }
        EffectGroup(ImagePreprocessor preprocessor, ImageProcessor effect1, ImageProcessor effect2) {
            this(preprocessor, effect1);
            processors.add(effect2);
        }
        public ImagePreprocessor getPreprocessor() {
            return (ImagePreprocessor)processors.get(0);
        }
        public ArrayList<ImageProcessor> processors;
    }
    private EffectGroup mCurrentGroup;
    private ArrayList<EffectGroup> mAllEffectGroups;

    public EffectRunner() {
        initializeProcessors();
        mCurrentGroup = mAllEffectGroups.get(0);
    }

    public EffectGroup prevEffectGroup() {
        int currentIndex = mAllEffectGroups.indexOf(mCurrentGroup);
        int prevIndex = currentIndex-1;
        if(prevIndex <= -1) prevIndex = mAllEffectGroups.size()-1;
        EffectGroup prevGroup = mAllEffectGroups.get(prevIndex);

        if(mCurrentGroup.getPreprocessor().getCallibration() != null)
            prevGroup.getPreprocessor().setCallibration(mCurrentGroup.getPreprocessor().getCallibration());

        mCurrentGroup = mAllEffectGroups.get(prevIndex);
        return mCurrentGroup;
    }

    public EffectGroup nextEffectGroup() {
        int currentIndex = mAllEffectGroups.indexOf(mCurrentGroup);
        int nextIndex = currentIndex+1;
        if(nextIndex >= mAllEffectGroups.size()) nextIndex = 0;
        EffectGroup nextGroup = mAllEffectGroups.get(nextIndex);

        if(mCurrentGroup.getPreprocessor().getCallibration() != null)
            nextGroup.getPreprocessor().setCallibration(mCurrentGroup.getPreprocessor().getCallibration());

        mCurrentGroup = mAllEffectGroups.get(nextIndex);
        return mCurrentGroup;
    }

    private void initializeProcessors() {
        mAllEffectGroups = new ArrayList<>();

        // Contour: Contour heatmap from a Depth JPEG, just on projected area
        /*
        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                new ProjectionAreaProcessor(false, false),
                new ContourGenerator()
        ));
        */

        // Hypercolor: Re-render of the input image
        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                new ProjectionAreaProcessor(true, false)
        ));


        // Full-collection-area depth JPEG input contour heatmap
        /*mAllEffectGroups.add(new EffectRunner.EffectGroup(
                new DepthJpegProcessor(),
                new ContourGenerator()
       ));*/

        /*
        // Full-collection-area depth16 input contour heatmap
        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                new Depth16Processor(),
                new ContourGenerator()
        ));*/
    }

    @Override
    public Mat process(Image img) {
        ImagePreprocessor preprocessor = mCurrentGroup.getPreprocessor();
        Mat output = preprocessor.process(img);
        if(output == null) return null;
        if(!preprocessor.isCallibrated())
            return output;

        for(int i = 1; i < mCurrentGroup.processors.size(); i++) {
            ImageProcessor processor = mCurrentGroup.processors.get(i);
            output = processor.process(output);
            if(output == null) return null;
        }
        return output;
    }

    public Mat process(Mat mat) {
        Mat output = null;
        for(int i = 0; i < mCurrentGroup.processors.size(); i++) {
            ImageProcessor processor = mCurrentGroup.processors.get(i);
            output = processor.process(output);
            if(output == null) return null;
        }
        return output;
    }

    @Override
    public void recallibrate() {
        mCurrentGroup.getPreprocessor().recallibrate();
    }

    @Override
    public boolean isCallibrated() {
        return mCurrentGroup.getPreprocessor().isCallibrated();
    }

    @Override
    public void setAutoCallibrate(boolean enable) {
        mCurrentGroup.getPreprocessor().setAutoCallibrate(enable);
    }

    @Override
    public boolean getAutoCallibrate() {
        return mCurrentGroup.getPreprocessor().getAutoCallibrate();
    }

    @Override
    public int requiredInputFormat() {
        return mCurrentGroup.getPreprocessor().requiredInputFormat();
    }

    @Override
    public MatOfPoint2f getCallibration() {
        return mCurrentGroup.getPreprocessor().getCallibration();
    }

    @Override
    public void setCallibration(MatOfPoint2f callib) {
        mCurrentGroup.getPreprocessor().setCallibration(callib);
    }
}
