package tlcnet.udptest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class ChannelGenericPacketParser {	
	private static final int INVALID_PORT  = 0;
	
	static final int DSTADDR_START  = 0;  // 4 bytes
	static final int DSTADDR_END    = 3;
	static final int DSTPORT_START  = 4;  // 2 bytes
	static final int DSTPORT_END    = 5;
	
	private InetAddress dstAddr = null;
	private short dstPort = INVALID_PORT;

	
	/**
	 * Creates a new empty ChannelGenericPacketParser.
	 * 
	 * @param payload - the byte array that represents the payload of an UDP packet received by the channel.
	 */
	public ChannelGenericPacketParser() {
		super();
	}
	
	/**
	 * Creates a new ChannelGenericPacketParser with the payload of an UDP datagram represented as a byte array.
	 * This constructor parses the byte array and initializes all fields accordingly.
	 * 
	 * @param payload - the byte array that represents the payload of an UDP packet received by the channel.
	 */
	public ChannelGenericPacketParser(byte[] payload) {
		super();
		parseData(payload);
	}
	
	/**
	 * Sets the UDP payload to be parsed and performs the parsing. The result can be obtained by means
	 * of the getters.
	 * 
	 * @param payload - the UDP payload to be parsed
	 * @return this object
	 */
	public ChannelGenericPacketParser setPayload(byte[] payload) {
		parseData(payload);
		return this;
	}

	
	/**
	 * Parses the payload of the UDP packet and updates all fields of this instance.
	 * @param rawData - the byte array representation of the payload to be parsed
	 */
	private void parseData(byte[] rawData) {
		try {
			dstAddr = InetAddress.getByAddress(Arrays.copyOfRange(rawData, DSTADDR_START, DSTADDR_END+1));
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		dstPort = Utils.bytes2short(Arrays.copyOfRange(rawData, DSTPORT_START, DSTPORT_END+1));
	}


	public InetAddress getDstAddr() {
		return dstAddr;
	}


	public int getDstPort() {
		return (int) dstPort & 0xffff;
	}
}
