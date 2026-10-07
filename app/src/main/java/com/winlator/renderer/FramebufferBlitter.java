package com.winlator.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;

final class FramebufferBlitter {
    private FramebufferBlitter() {
    }

    static boolean copyIntoTexture(int textureId, short width, short height) {
        int[] framebuffer = new int[1];
        GLES30.glGenFramebuffers(1, framebuffer, 0);
        if (framebuffer[0] == 0) return false;

        int[] previousDrawFramebuffer = new int[1];
        GLES30.glGetIntegerv(
                GLES30.GL_DRAW_FRAMEBUFFER_BINDING,
                previousDrawFramebuffer,
                0
        );
        boolean scissorEnabled = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST);
        if (scissorEnabled) GLES30.glDisable(GLES30.GL_SCISSOR_TEST);

        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
        }
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, framebuffer[0]);
        GLES30.glFramebufferTexture2D(
                GLES30.GL_DRAW_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                textureId,
                0
        );
        boolean complete = GLES30.glCheckFramebufferStatus(
                GLES30.GL_DRAW_FRAMEBUFFER
        ) == GLES30.GL_FRAMEBUFFER_COMPLETE;
        if (complete) {
            GLES30.glBlitFramebuffer(
                    0,
                    0,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    GLES30.GL_COLOR_BUFFER_BIT,
                    GLES30.GL_NEAREST
            );
            complete = GLES30.glGetError() == GLES30.GL_NO_ERROR;
        }
        GLES30.glFramebufferTexture2D(
                GLES30.GL_DRAW_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                0,
                0
        );
        GLES30.glBindFramebuffer(
                GLES30.GL_DRAW_FRAMEBUFFER,
                previousDrawFramebuffer[0]
        );
        if (scissorEnabled) GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
        GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        return complete;
    }
}
