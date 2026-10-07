package com.winlator.renderer;

import com.winlator.math.XForm;

public class ViewTransformation {
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        aspect = Math.min((float)outerWidth / innerWidth, (float)outerHeight / innerHeight);
        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetY = (int)((outerHeight - innerHeight * aspect) * 0.5f);

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }

    public static void updateInputTransform(
            float[] xform,
            boolean fullscreen,
            int outerWidth,
            int outerHeight,
            int innerWidth,
            int innerHeight
    ) {
        if (outerWidth <= 0 || outerHeight <= 0 || innerWidth <= 0 || innerHeight <= 0) {
            XForm.identity(xform);
            return;
        }
        if (fullscreen) {
            XForm.makeScale(
                    xform,
                    innerWidth / (float)outerWidth,
                    innerHeight / (float)outerHeight
            );
            return;
        }

        ViewTransformation transformation = new ViewTransformation();
        transformation.update(outerWidth, outerHeight, innerWidth, innerHeight);
        float inverseAspect = 1.0f / transformation.aspect;
        XForm.makeTranslation(xform, -transformation.viewOffsetX, -transformation.viewOffsetY);
        XForm.scale(xform, inverseAspect, inverseAspect);
    }
}
