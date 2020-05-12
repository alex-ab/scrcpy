package com.genymobile.scrcpy;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class IO {
    private IO() {
        // not instantiable
    }

    public static void writeFully(OutputStream stream, ByteBuffer from) throws IOException {
        try {
            if (from.hasArray()) {
                stream.write(from.array());
            } else {
                /* XXX optimize not to allocate again and again ? */
                ByteBuffer tmp = ByteBuffer.allocate(from.remaining());
                tmp.put(from);
                stream.write(tmp.array());
            }
        } catch (IOException e) {
            throw new IOException(e);
        }
    }

    public static void writeFully(OutputStream stream, byte[] buffer, int offset, int len) throws IOException {
        writeFully(stream, ByteBuffer.wrap(buffer, offset, len));
    }
}
