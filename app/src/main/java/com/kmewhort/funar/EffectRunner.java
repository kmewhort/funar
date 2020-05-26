package com.kmewhort.funar;

import android.media.Image;

import org.opencv.core.Mat;

import java.util.ArrayList;

// this class runs a group of effects and provides for selecting an effect group
public class EffectRunner implements ImageProcessor {
    private class EffectGroup {
        EffectGroup(ImageProcessor effect) {
            processors = new ArrayList<ImageProcessor>();
            processors.add(effect);
        }
        EffectGroup(ImageProcessor preprocessor, ImageProcessor effect) {
            this(preprocessor);
            processors.add(effect);
        }
        EffectGroup(ImageProcessor preprocessor, ImageProcessor effect1, ImageProcessor effect2) {
            this(preprocessor, effect1);
            processors.add(effect2);
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
        mCurrentGroup = mAllEffectGroups.get(prevIndex);
        return mCurrentGroup;
    }

    public EffectGroup nextEffectGroup() {
        int currentIndex = mAllEffectGroups.indexOf(mCurrentGroup);
        int nextIndex = currentIndex+1;
        if(nextIndex >= mAllEffectGroups.size()) nextIndex = 0;
        mCurrentGroup = mAllEffectGroups.get(nextIndex);
        return mCurrentGroup;
    }

    private void initializeProcessors() {
        mAllEffectGroups = new ArrayList<>();

        // Contour: Contour heatmap from a Depth JPEG
        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                new DepthJpegProjectionAreaProcessor(),
                new ContourGenerator()
        ));

        // Hypercolor: Re-render of the input image
        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                new DepthJpegProjectionAreaProcessor()
        ));
    }

    @Override
    public Mat process(Image img) {
        ImageProcessor preprocessor = mCurrentGroup.processors.get(0);
        Mat output = preprocessor.process(img);
        if(output == null) return null;
        if(!preprocessor.isCallibrated())
            return output;

        for(int i = 1; i < mCurrentGroup.processors.size(); i++) {
            ImageProcessor processor = mCurrentGroup.processors.get(i);
            output = processor.process(output);
            if(output == null) return null;
            if(!processor.isCallibrated())
                return output;
        }
        return output;
    }

    public Mat process(Mat mat) {
        Mat output = null;
        for(int i = 0; i < mCurrentGroup.processors.size(); i++) {
            ImageProcessor processor = mCurrentGroup.processors.get(i);
            output = processor.process(output);
            if(output == null) return null;
            if(!processor.isCallibrated())
                return output;
        }
        return output;
    }

    @Override
    public void recallibrate() {
        for(ImageProcessor processor : mCurrentGroup.processors) {
            processor.recallibrate();
        }
    }

    @Override
    public boolean isCallibrated() {
        for(ImageProcessor processor : mCurrentGroup.processors) {
            if(!processor.isCallibrated())
                return false;
        }
        return true;
    }

    @Override
    public void setAutoCallibrate(boolean enable) {
        for(ImageProcessor processor : mCurrentGroup.processors) {
            processor.setAutoCallibrate(enable);
        }
    }

    @Override
    public boolean getAutoCallibrate() {
        return mCurrentGroup.processors.get(0).getAutoCallibrate();
    }

    @Override
    public int requiredInputFormat() {
        return mCurrentGroup.processors.get(0).requiredInputFormat();
    }
}
