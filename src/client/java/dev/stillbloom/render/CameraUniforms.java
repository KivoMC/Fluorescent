package dev.stillbloom.render;

import dev.stillbloom.render.source.SourceMask;

import java.nio.ByteBuffer;

// I cache camera uniforms per chain while still allowing texture uploads when the camera stays
// still.
public final class CameraUniforms {
    private final ByteBuffer previous = ByteBuffer.allocateDirect(SourceMask.BUFFER_BYTES);
    private boolean valid;

    public boolean changed(ByteBuffer current) {
        if (valid && previous.mismatch(current) == -1) return false;
        previous.clear();
        previous.put(current.duplicate()).flip();
        valid = true;
        return true;
    }

    public void reset() {
        valid = false;
    }
}
