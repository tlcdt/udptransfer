package tlcnet.udptest;

import java.net.DatagramPacket;
import java.net.InetAddress;

public class EobAssembler {
	
	private DatagramPacket[] eobCache;
	private final int cacheSize;
	private int eobSn;
	private InetAddress channelAddr, dstAddr;
	private int channelPort, dstPort;
	private int windowLeft;
	
	
	
	public EobAssembler(int cacheSize, InetAddress channelAddr, int channelPort, InetAddress dstAddr, int dstPort) {
		super();
		this.cacheSize = cacheSize;
		eobCache = new DatagramPacket[cacheSize];
		eobSn = 1;
		this.channelAddr = channelAddr;
		this.dstAddr = dstAddr;
		this.channelPort = channelPort;
		this.dstPort = dstPort;
		windowLeft = 1;
	}
	
	
	public void shiftWindow() {
		windowLeft++;
		Utils.shiftArrayLeft(eobCache, 1);
	}
	
	
	public DatagramPacket getFromCache(int index) {
		if (index < 0 || index > cacheSize)
			throw new IllegalArgumentException();
		
		return eobCache[index];
	}
	

	public DatagramPacket assembleEobDatagram(int bn, int numPacketsInThisBlock) {

		UTPpacket eobUtpPkt = new UTPpacket();
		eobUtpPkt.sn = eobSn++;
		eobUtpPkt.dstAddr = dstAddr;
		eobUtpPkt.dstPort = (short)dstPort;
		eobUtpPkt.function = UTPpacket.FUNCT_EOB;
		eobUtpPkt.setEndOfBlock(bn, numPacketsInThisBlock);
		byte[] eobData = eobUtpPkt.getRawData();
		DatagramPacket eobDatagram = new DatagramPacket(eobData, eobData.length, channelAddr, channelPort);
		
		eobUtpPkt.sn = DuplicateIdHandler.JOLLY;
		eobData = eobUtpPkt.getRawData();
		eobCache[bn - windowLeft] = new DatagramPacket(eobData, eobData.length, channelAddr, channelPort);
		
		return eobDatagram;
	}
}
