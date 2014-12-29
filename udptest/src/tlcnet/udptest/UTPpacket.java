package tlcnet.udptest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class UTPpacket {
	static final int INVALID_PORT  = -1;
	static final int INVALID_SN    = -1;
	
	static final int FUNCT_INVALID = -1;
	//static final int FUNCT_REQ  = 1;
	static final int FUNCT_DATA = 2;
	//static final int FUNCT_ACKREQ  = 9;
	static final int FUNCT_ACKDATA = 10;
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
	static final int LAST_SN_START = 10;// 3
	static final int LAST_SN_END = 12;	// 3		
	static final int PAYL_START    = 13;
	static final int HEADER_LENGTH = 13;
	static final int SN_LENGTH = SN_END - SN_START + 1;
	static final int LAST_SN_LENGTH = LAST_SN_END - LAST_SN_START + 1;
	static final int LAST_SN = 0;
	
	InetAddress dstAddr; // destination address
	short dstPort = INVALID_PORT;
	int sn = INVALID_SN;
	int lastSnInWindow = LAST_SN;
	byte function = FUNCT_INVALID;
	byte[] payl = null;

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
		lastSnInWindow = bytes2int(Arrays.copyOfRange(rawData, LAST_SN_START, LAST_SN_END+1));
		function = rawData[FUNCT_START];
		payl = Arrays.copyOfRange(rawData, PAYL_START, rawData.length);
	}
	
	
	
	public byte[] getRawData() {
		
		byte[] rawData = new byte[HEADER_LENGTH + payl.length];
		
		System.arraycopy(dstAddr.getAddress(), 0, rawData, DSTADDR_START, dstAddr.getAddress().length);
		System.arraycopy(int2bytes(dstPort, 2), 0, rawData, DSTPORT_START, 2);
		rawData[FUNCT_START] = function;
		System.arraycopy(int2bytes(sn, 4), 4 - SN_LENGTH, rawData, SN_START, SN_LENGTH);
		System.arraycopy(int2bytes(lastSnInWindow, 4), 4 - LAST_SN_LENGTH, rawData, LAST_SN_START, LAST_SN_LENGTH);
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

}
