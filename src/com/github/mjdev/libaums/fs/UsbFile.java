package com.github.mjdev.libaums.fs;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface UsbFile {
	public boolean isDirectory();
	public String getName();
	public String[] list() throws IOException;
	public UsbFile[] listFiles() throws IOException;
	public long getLength();
	public void read(long offset, ByteBuffer destination) throws IOException;
}
