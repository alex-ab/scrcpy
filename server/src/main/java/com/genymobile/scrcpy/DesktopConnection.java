package com.genymobile.scrcpy;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetAddress;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME = "scrcpy";

    private final LocalSocket videoSocket;
    private final OutputStream videoOutputStream;

    private final LocalSocket controlSocket;
    private final InputStream controlInputStream;
    private final OutputStream controlOutputStream;

    private final Socket videoDirectSocket;
    private final Socket controlDirectSocket;

    private final boolean direct;

    private final ControlMessageReader reader = new ControlMessageReader();
    private final DeviceMessageWriter writer = new DeviceMessageWriter();

    private DesktopConnection(Socket videoSocket, Socket controlSocket) throws IOException {
        this.videoSocket = new LocalSocket();
        this.controlSocket = new LocalSocket();

        this.direct = true;

        this.videoDirectSocket = videoSocket;
        this.controlDirectSocket = controlSocket;

        controlInputStream = controlDirectSocket.getInputStream();
        controlOutputStream = controlDirectSocket.getOutputStream();
        videoOutputStream = videoDirectSocket.getOutputStream();
    }

    private DesktopConnection(LocalSocket videoSocket, LocalSocket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.controlSocket = controlSocket;

        this.direct = false;

        this.videoDirectSocket = new Socket();
        this.controlDirectSocket = new Socket();

        controlInputStream = controlSocket.getInputStream();
        controlOutputStream = controlSocket.getOutputStream();
        videoOutputStream = videoSocket.getOutputStream();
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    public static DesktopConnection open(Device device, boolean tunnelForward) throws IOException {
        LocalSocket videoSocket;
        LocalSocket controlSocket;

        if (tunnelForward) {
            LocalServerSocket localServerSocket = new LocalServerSocket(SOCKET_NAME);
            try {
                videoSocket = localServerSocket.accept();
                // send one byte so the client may read() to detect a connection error
                videoSocket.getOutputStream().write(0);
                try {
                    controlSocket = localServerSocket.accept();
                } catch (IOException | RuntimeException e) {
                    videoSocket.close();
                    throw e;
                }
            } finally {
                localServerSocket.close();
            }
        } else {
            videoSocket = connect(SOCKET_NAME);
            try {
                controlSocket = connect(SOCKET_NAME);
            } catch (IOException | RuntimeException e) {
                videoSocket.close();
                throw e;
            }
        }

        DesktopConnection connection = new DesktopConnection(videoSocket, controlSocket);
        Size videoSize = device.getScreenInfo().getVideoSize();
        connection.send(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());

        return connection;
    }

    public static DesktopConnection open(Device device, boolean tunnelForward, String ip_string, int port) throws IOException {
        if (ip_string == null)
            return open(device, tunnelForward);

        InetAddress ip = InetAddress.getByName(ip_string);

        Socket videoDirectSocket;
        Socket controlDirectSocket;

        if (tunnelForward) {
            ServerSocket server = new ServerSocket(port);
            try {
                videoDirectSocket = server.accept();
                // send one byte so the client may read() to detect a connection error
                videoDirectSocket.getOutputStream().write(0);

                controlDirectSocket = server.accept();
            } finally {
                server.close();
            }
        } else {
            videoDirectSocket = new Socket(ip, port);
            controlDirectSocket = new Socket(ip, port);
        }

        DesktopConnection connection = new DesktopConnection(videoDirectSocket, controlDirectSocket);
        Size videoSize = device.getScreenInfo().getVideoSize();
        connection.send(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());
        return connection;
    }

    public void close() throws IOException {
        videoSocket.shutdownInput();
        videoSocket.shutdownOutput();
        videoSocket.close();
        controlSocket.shutdownInput();
        controlSocket.shutdownOutput();
        controlSocket.close();
    }

    private void send(String deviceName, int width, int height) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH + 4];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        buffer[DEVICE_NAME_FIELD_LENGTH] = (byte) (width >> 8);
        buffer[DEVICE_NAME_FIELD_LENGTH + 1] = (byte) width;
        buffer[DEVICE_NAME_FIELD_LENGTH + 2] = (byte) (height >> 8);
        buffer[DEVICE_NAME_FIELD_LENGTH + 3] = (byte) height;
        IO.writeFully(videoOutputStream, buffer, 0, buffer.length);
    }

    public OutputStream getVideoFd() {
        return videoOutputStream;
    }

    public ControlMessage receiveControlMessage() throws IOException {
        ControlMessage msg = reader.next();
        while (msg == null) {
            reader.readFrom(controlInputStream);
            msg = reader.next();
        }
        return msg;
    }

    public void sendDeviceMessage(DeviceMessage msg) throws IOException {
        writer.writeTo(msg, controlOutputStream);
    }
}
