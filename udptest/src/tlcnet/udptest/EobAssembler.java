package tlcnet.udptest;

import java.net.DatagramPacket;
import java.net.InetAddress;


/**
 * An object of this class is capable of generating EOB packets with increasing sequence number, and
 * keeping track of the EOB sent in a transmission window whose size is specified in the constructor.<br>
 * The UTP packets are created as DatagramPackets; the address and port of the channel and destination
 * must be provided in the constructor and are constant throughout an instance of this class.
 */
public class EobAssembler {
	
	private DatagramPacket[] eobCache;
	private final int cacheSize;
	private int eobSn;
	private final InetAddress channelAddr, dstAddr;
	private final int channelPort, dstPort;
	private int windowLeft;
	
	
	
	/**
	 * This constructor provides this object of all its final fields.
	 * @param cacheSize - the size of the sliding window; it should be the number of blocks in a window
	 * @param channelAddr - the IP address of the channel
	 * @param channelPort - the port of the channel
	 * @param dstAddr - the IP address of the destination
	 * @param dstPort - the port of the destination
	 */
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
	
	
	
	/**
	 * Performs a forward shift of the window by one element: this should be called whenever the
	 * main application's sliding windows moves forward by one.
	 */
	public void shiftWindow() {
		windowLeft++;
		Utils.shiftArrayLeft(eobCache, 1);
	}
	
	
	
	/**
	 * Retrieves from the EOB cache the EOB packet that is at the specified index in the sliding window.
	 * @param index - the index of the packet
	 * @return the EOB packet that's at the specified index in the sliding window
	 */
	public DatagramPacket getFromCache(int index) {
		if (index < 0 || index > cacheSize)
			throw new IllegalArgumentException();
		
		return eobCache[index];
	}
	
	
	
	/**
	 * Returns an EOB datagram with the specified information.<br>
	 * In detail: after assembling the packet with a progressive sequence number (the counter is a private
	 * field of this class), it stores a SN-less version of it (SN = 0) in the EOB cache at the proper index,
	 * to be retrieved with getFromCache(). Finally it returns the original packet with the SN.
	 * 
	 * @param bn - the Block Number of the block to which this EOB packet is referred
	 * @param numPacketsInThisBlock - the number of data packets that were sent for this block before this EOB
	 * @return an EOB datagram with the specified information.
	 */
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
