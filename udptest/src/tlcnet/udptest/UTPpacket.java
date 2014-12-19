package tlcnet.udptest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class UTPpacket {
	
	static final int INVALID_PORT  = -1;
	static final int INVALID_SN    = -1;
	
	static final int FUNCT_INVALID = -1;
	static final int FUNCT_DATA = 2;
	static final int FUNCT_FILEINFO  = 3;
	static final int FUNCT_FILEINFO_ACK = 4;
	static final int FUNCT_ENDOFBLOCK = 5;
	static final int FUNCT_ENDOFBLOCK_ACK = 6;
	static final int FUNCT_FIN = 42;
	static final int FUNCT_ACKFIN = 43;
	
	static final int DSTADDR_START  = 0;  // 4
	static final int DSTADDR_END    = 3;
	static final int DSTPORT_START  = 4;  // 2
	static final int DSTPORT_END    = 5;
	static final int FUNCT_START   = 6; // 1
	static final int FUNCT_END     = 6;
	static final int SN_START      = 7; // 3
	static final int SN_END        = 9;
	static final int PAYL_START    = 10;
	static final int HEADER_LENGTH = 10;
	static final int SN_LENGTH = SN_END - SN_START + 1;
	
	InetAddress dstAddr; // destination address
	short dstPort = INVALID_PORT;
	int sn = INVALID_SN;
	byte function = FUNCT_INVALID;
	byte[] payl = null;
	FileInfo fileInfo = null;

	public UTPpacket() {
		super();
	}
	
	public UTPpacket(byte[] rawData) {
		super();
		parseData(rawData);
	}

	private void parseData(byte[] rawData) {
		try {
			dstAddr = InetAddress.getByAddress(Arrays.copyOfRange(rawData, DSTADDR_START, DSTADDR_END+1));
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		dstPort = bytes2short(Arrays.copyOfRange(rawData, DSTPORT_START, DSTPORT_END+1));

		sn = bytes2int(Arrays.copyOfRange(rawData, SN_START, SN_END+1));
		function = rawData[FUNCT_START];
		payl = Arrays.copyOfRange(rawData, PAYL_START, rawData.length);
		
		// FileInfo packet
		if (function == FUNCT_FILEINFO) {
			fileInfo = new FileInfo(payl);
			// Now the dimension of a block is accessible through this object
		}
	}
	
	
	
	public byte[] getRawData() {
		
		byte[] rawData = new byte[HEADER_LENGTH + payl.length];
		
		System.arraycopy(dstAddr.getAddress(), 0, rawData, DSTADDR_START, dstAddr.getAddress().length);
		System.arraycopy(int2bytes(dstPort, 2), 0, rawData, DSTPORT_START, 2);
		rawData[FUNCT_START] = function;
		System.arraycopy(int2bytes(sn, 4), 4 - SN_LENGTH, rawData, SN_START, SN_LENGTH);
		System.arraycopy(payl, 0, rawData, PAYL_START, payl.length);
		
		return rawData;
	}
	
	
	
//	private byte[] getField(byte[] rawData, int first, int last) {
//		
//	}
	
	
	
	private static byte[] int2bytes(int value, int size) {
		if (size==4)
			return ByteBuffer.allocate(4).putInt(value).array();
		if (size==2)
			return ByteBuffer.allocate(2).putShort((short)value).array();
		return null;
	}
	
	private static int bytes2int(byte[] bytes) {
		if (bytes.length == 4)
			return ByteBuffer.wrap(bytes).getInt();
		if (bytes.length < 4) {
			byte[] newbytes = new byte[] {(byte)0, (byte)0, (byte)0, (byte)0};
			System.arraycopy(bytes, 0, newbytes, 4-bytes.length, bytes.length);
			return ByteBuffer.wrap(newbytes).getInt();
		}
		return -1;
	}
	
	private static short bytes2short(byte[] bytes) {
	     return ByteBuffer.wrap(bytes).getShort();
	}
	
	
	
	
	
	public class FileInfo {
		
		static final int BLOCKDIM_START  = 0;  // 4
		static final int BLOCKDIM_END    = 3;
		static final int FILEINFO_LENGTH = 4;
		static final int BLOCKDIM_LENGTH = BLOCKDIM_END - BLOCKDIM_START + 1;
		
		int blockDim = 0;
		
		FileInfo(byte[] payl) {
			super();
			blockDim = bytes2int(Arrays.copyOfRange(payl, BLOCKDIM_START, BLOCKDIM_END));
		}
		
	}
	
	
	
	public class EndOfBlockAck {
		
		// SN_LENGTH can be reached from down here (y)
		
		int numberOfMissingSN = -1;
		int[] missingSN = null;
		
		public EndOfBlockAck(byte[] payl) {
			super();
			numberOfMissingSN = payl.length / SN_LENGTH; //TODO handle errors?
			for (int i = 0; i < numberOfMissingSN; i++)
				missingSN[i] = bytes2int(Arrays.copyOfRange
						(payl, SN_LENGTH * i, SN_LENGTH * (i + 1)));
		}
	}

}
