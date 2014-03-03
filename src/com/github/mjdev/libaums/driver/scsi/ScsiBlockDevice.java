package com.github.mjdev.libaums.driver.scsi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import android.util.Log;

import com.github.mjdev.libaums.UsbCommunication;
import com.github.mjdev.libaums.driver.BlockDeviceDriver;
import com.github.mjdev.libaums.driver.scsi.commands.CommandBlockWrapper;
import com.github.mjdev.libaums.driver.scsi.commands.CommandBlockWrapper.Direction;
import com.github.mjdev.libaums.driver.scsi.commands.CommandStatusWrapper;
import com.github.mjdev.libaums.driver.scsi.commands.ScsiInquiry;
import com.github.mjdev.libaums.driver.scsi.commands.ScsiRead10;
import com.github.mjdev.libaums.driver.scsi.commands.ScsiReadCapacity;
import com.github.mjdev.libaums.driver.scsi.commands.ScsiReadCapacityResponse;
import com.github.mjdev.libaums.driver.scsi.commands.ScsiTestUnitReady;
import com.github.mjdev.libaums.driver.scsi.commands.ScsiWrite10;

public class ScsiBlockDevice implements BlockDeviceDriver {

	private static final String TAG = ScsiBlockDevice.class.getSimpleName();
	
	private UsbCommunication usbCommunication;
	private ByteBuffer outBuffer;
	
	private int blockSize;
	private int lastBlockAddress;
	
	public ScsiBlockDevice(UsbCommunication usbCommunication) {
		this.usbCommunication = usbCommunication;
		outBuffer = ByteBuffer.allocate(31);
	}
	
	@Override
	public void init() {
		ByteBuffer inBuffer = ByteBuffer.allocate(36);
		ScsiInquiry inquiry = new ScsiInquiry();
		transferCommand(inquiry, inBuffer);
		//ScsiInquiryResponse inquiryResponse = ScsiInquiryResponse.read(inBuffer);
		ScsiTestUnitReady testUnit = new ScsiTestUnitReady();
		if(!transferCommand(testUnit, null)) {
			Log.e(TAG, "unit not ready!");
		}
		
		ScsiReadCapacity readCapacity = new ScsiReadCapacity();
		transferCommand(readCapacity, inBuffer);
		ScsiReadCapacityResponse readCapacityResponse = ScsiReadCapacityResponse.read(inBuffer);
		blockSize = readCapacityResponse.getBlockLength();
		lastBlockAddress = readCapacityResponse.getLogicalBlockAddress();
		
		Log.i(TAG, "Block size: " + blockSize);
		Log.i(TAG, "Last block address: " + lastBlockAddress);
		
		ByteBuffer buffer = ByteBuffer.allocate(512);
		ScsiRead10 read = new ScsiRead10(0, buffer.capacity(), blockSize);
		transferCommand(read, buffer);
	}
	
	private boolean transferCommand(CommandBlockWrapper command, ByteBuffer inBuffer) {
		byte[] outArray = outBuffer.array();
		outBuffer.clear();
		Arrays.fill(outArray, (byte) 0);
		
		command.serialize(outBuffer);
		int written = usbCommunication.bulkOutTransfer(outArray, outArray.length);
		if(written != outArray.length) {
			Log.e(TAG, "Writing all bytes on command " + command + " failed!");
		}
		
		int transferLength = command.getdCbwDataTransferLength();
		int read = 0;
		if(transferLength > 0) {
			byte[] inArray = inBuffer.array();
			//inBuffer.clear();
			//Arrays.fill(inArray, (byte) 0);

			if(command.getDirection() == Direction.IN) {
				do {
					int tmp = usbCommunication.bulkInTransfer(inArray, read + inBuffer.position(), inBuffer.limit() - inBuffer.position() - read);
					if(tmp == -1) {
						Log.e(TAG, "reading failed!");
						break;
					}
					read += tmp;
				} while(read < transferLength);
				
				if(read != transferLength) {
					Log.e(TAG, "Unexpected command size on response to " + command);
				}
			} else {
				written = 0;
				do {
					int tmp = usbCommunication.bulkOutTransfer(inArray, written + inBuffer.position(), inBuffer.limit() - inBuffer.position() - written);
					if(tmp == -1) {
						Log.e(TAG, "writing failed!");
						break;
					}
					written += tmp;
				} while(written < transferLength);
				
				if(written != transferLength) {
					Log.e(TAG, "Could not write all bytes: " + command);
				}
			}
		}
		
		
		// expecting csw now
		byte[] cswBuffer = new byte[CommandStatusWrapper.SIZE];
		read = usbCommunication.bulkInTransfer(cswBuffer, cswBuffer.length);
		if(read != CommandStatusWrapper.SIZE) {
			Log.e(TAG, "Unexpected command size while expecting csw");
		}
		
		CommandStatusWrapper csw = CommandStatusWrapper.read(ByteBuffer.wrap(cswBuffer));
		if(csw.getbCswStatus() != CommandStatusWrapper.COMMAND_PASSED) {
			Log.e(TAG, "Unsuccessful Csw status: " + csw.getbCswStatus());
		}
		
		if(csw.getdCswTag() != command.getdCbwTag()) {
			Log.e(TAG, "wrong csw tag!");
		}
		
		return csw.getbCswStatus() == CommandStatusWrapper.COMMAND_PASSED;
	}
	
	@Override
	public void read(long devOffset, ByteBuffer dest) throws IOException {
		long time = System.currentTimeMillis();
		ByteBuffer buffer;
		if((dest.limit() - dest.position()) % blockSize != 0) {
			Log.i(TAG, "we have to round up size to next block sector");
			int rounded = blockSize - (dest.limit() - dest.position()) % blockSize + dest.limit() - dest.position();
			buffer = ByteBuffer.allocate(rounded);
			buffer.limit(rounded);
		} else {
			buffer = dest;
		}
		
		ScsiRead10 read = new ScsiRead10((int) devOffset, buffer.limit() - buffer.position(), blockSize);
		Log.d(TAG, "reading: " + read);
		transferCommand(read, buffer);
		
		if((dest.limit() - dest.position()) % blockSize != 0) {
			System.arraycopy(buffer.array(), 0, dest.array(), dest.position(), dest.limit() - dest.position());
			//dest = ByteBuffer.wrap(buffer.array(), 0, dest.capacity());
		}
		
		dest.position(dest.limit());
		
		Log.d(TAG, "read time: " + (System.currentTimeMillis() - time));
	}

	@Override
	public void write(long devOffset, ByteBuffer src) throws IOException {
		long time = System.currentTimeMillis();
		ByteBuffer buffer;
		if((src.limit() - src.position()) % blockSize != 0) {
			Log.i(TAG, "we have to round up size to next block sector");
			int rounded = blockSize - (src.limit() - src.position()) % blockSize + src.limit() - src.position();
			buffer = ByteBuffer.allocate(rounded);
			buffer.limit(rounded);
			System.arraycopy(src.array(), src.position(), buffer.array(), 0, src.limit() - src.position());
		} else {
			buffer = src;
		}
		
		ScsiWrite10 write = new ScsiWrite10((int) devOffset, buffer.limit() - buffer.position(), blockSize);
		Log.d(TAG, "writing: " + write);
		transferCommand(write, buffer);
		
		src.position(src.limit());
		
		Log.d(TAG, "write time: " + (System.currentTimeMillis() - time));
	}

	@Override
	public int getBlockSize() {
		return blockSize;
	}
}
