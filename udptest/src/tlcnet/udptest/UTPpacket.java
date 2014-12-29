package tlcnet.udptest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class UTPpacket {
	
	static final int INVALID_PORT  = 0;
	static final int INVALID_SN    = 0;
	
	static final int FUNCT_INVALID = -1;
	static final int FUNCT_DATA = 2;
	static final int FUNCT_FIN  = 3;
	static final int FUNCT_EOB = 5;	// endofblock
	static final int FUNCT_EOB_ACK = 6;
	
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
	byte[] payl = new byte[0]; // it makes sense
	EndOfBlock endOfBlock = null;
	EndOfBlockAck endOfBlockAck = null;

	
	/**
	 * Creates a new UTPpacket without initializing its fields. The fields should be set manually,
	 * and then the content of the packet in byte[] form is created and returned by getRawData()
	 */
	public UTPpacket() {
		super();
	}
	
	
	/**
	 * Creates a new UTPpacket from its representation as byte array. This representation is in fact
	 * the payload of the enclosing UDP packet.
	 * This constructor parses the byte array and initializes all fields of this UTPpacket accordingly.
	 * 
	 * @param rawData - the byte array that represents an UTPpacket (and the payload of the corresponding
	 * enclosing UDP packet)
	 */
	public UTPpacket(byte[] rawData) {
		super();
		parseData(rawData);
	}

	
	/**
	 * Parses a byte array representation of an UTP packet (e.g. the payload of the enclosing UDP packet)
	 * and updates all fields of this instance of UTPpacket, that can be later directly accessed from
	 * outside this class. 
	 * @param rawData - the byte array representation of an UTP packet to be parsed
	 */
	private void parseData(byte[] rawData) {
		try {
			dstAddr = InetAddress.getByAddress(Arrays.copyOfRange(rawData, DSTADDR_START, DSTADDR_END+1));
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		dstPort = Utils.bytes2short(Arrays.copyOfRange(rawData, DSTPORT_START, DSTPORT_END+1));

		// TODO Handle degenerate situations (packets sent from other uncontrolled sources) with ArrayIndexOutOfBoundException. Leave fields as invalid.
		sn = Utils.bytes2int(Arrays.copyOfRange(rawData, SN_START, SN_END+1));
		function = rawData[FUNCT_START];
		payl = Arrays.copyOfRange(rawData, PAYL_START, rawData.length);

		// This is an EndOfBlock packet
		if (function == FUNCT_EOB) {
			endOfBlock = new EndOfBlock(payl);
		}
		
		// This is an EndOfBlockAck packet
		else if (function == FUNCT_EOB_ACK) {
			endOfBlockAck = new EndOfBlockAck(payl);
		}
	}
	
	
	
	/**
	 * Returns the byte array representation of this UTPpacket, based on the fields of this object,
	 * that must therefore be initialized manually or with the constructor UTPpacket(byte[]).
	 * 
	 * @return the byte array representation of this UTPpacket
	 */
	public byte[] getRawData() {
		
		byte[] rawData = new byte[HEADER_LENGTH + payl.length];
		
		System.arraycopy(dstAddr.getAddress(), 0, rawData, DSTADDR_START, dstAddr.getAddress().length);
		System.arraycopy(Utils.int2bytes(dstPort, 2), 0, rawData, DSTPORT_START, 2);
		rawData[FUNCT_START] = function;
		System.arraycopy(Utils.int2bytes(sn, 4), 4 - SN_LENGTH, rawData, SN_START, SN_LENGTH);
		System.arraycopy(payl, 0, rawData, PAYL_START, payl.length);
		
		return rawData;
	}
	
	
	

	/**
	 * Initializes the fields of the object this.endOfBlockAck, and then updates the payload of this UTPpacket.
	 * 
	 * @param bn - block number for this EOB_ACK packet
	 * @param missingSN - array of Sequence Numbers corresponding to packets that were not received
	 */
	public void setEndOfBlockAck(int bn, int[] missingSN) {

		// Initialize the fields of the object endOfBlockAck
		endOfBlockAck = new EndOfBlockAck();
		endOfBlockAck.bn = bn;
		endOfBlockAck.missingSN = new int[missingSN.length];
		System.arraycopy(missingSN, 0, endOfBlockAck.missingSN, 0, missingSN.length);
		
		// Update the payload of the packet according to endOfBlockAck
		endOfBlockAck.generateAndUpdatePayload();
		
		// The other fields are not initialized, in particular the function field. This must be done outside of this class.
	}

	
	
	/**
	 * Initializes the fields of the object this.endOfBlock, and then updates the payload of this UTPpacket.
	 * 
	 * @param bn - block number for this EOB packet
	 * @param numberOfSentSN - number of packets of this block that have been sent. This should be always the same
	 * except for the last block.
	 */
	public void setEndOfBlock(int bn, int numberOfSentSN) {

		// Initialize the fields of the object endOfBlock
		endOfBlock = new EndOfBlock();
		endOfBlock.bn = bn;
		endOfBlock.numberOfSentSN = numberOfSentSN;
		
		// Update the payload of the packet according to endOfBlockAck
		endOfBlock.generateAndUpdatePayload();
		
		// The other fields are not initialized, in particular the function field. This must be done outside of this class.
	}
	
	
	

	
	

	
	/**
	 * An instance of this class represents the payload of an EOB packet, and allows to initialize it
	 * or parse it through the enclosing class UTPpacket. An instance of UTPpacket can have an instance
	 * of this class as a field: it is created when parsing a byte array that represents an EOB UTPpacket
	 * (by means of the constructor of UTPpacket), or when setEndOfBlock is called on a UTPpacket object
	 * (this is useful for creating a new EOB packet from scratch).
	 */
	public class EndOfBlock {
		
		private static final int BN_START = 0;
		private static final int BN_END = 3;
		private static final int NUMBER_SENT_SN_START = 4;
		private static final int NUMBER_SENT_SN_END = 7;
		private static final int PAYL_LENGTH = 8;
		
		int bn = 0;
		int numberOfSentSN = -1;
		
		private EndOfBlock() {
			super();
		}
		
		private EndOfBlock(byte[] payl) {
			super();
			bn = Utils.bytes2int(Arrays.copyOfRange(payl, BN_START, BN_END + 1));
			numberOfSentSN = Utils.bytes2int(Arrays.copyOfRange
					(payl, NUMBER_SENT_SN_START, NUMBER_SENT_SN_END + 1));
		}
		
		
		private void generateAndUpdatePayload() {
			payl = new byte[PAYL_LENGTH];
			byte[] bnBytes = Utils.int2bytes(bn, BN_END - BN_START + 1);
			byte[] numSentSnBytes = Utils.int2bytes(numberOfSentSN, NUMBER_SENT_SN_END - NUMBER_SENT_SN_START + 1);
			
			System.arraycopy(bnBytes, 0, payl, BN_START, bnBytes.length);
			System.arraycopy(numSentSnBytes, 0, payl, NUMBER_SENT_SN_START, numSentSnBytes.length);
		}
	}
	
	
	
	
	
	/**
	 * An instance of this class represents the payload of an EOB_ACK packet, and allows to initialize it
	 * or parse it through the enclosing class UTPpacket. An instance of UTPpacket can have an instance
	 * of this class as a field: it is created when parsing a byte array that represents an EOB_ACK UTPpacket
	 * (by means of the constructor of UTPpacket), or when setEndOfBlock is called on a UTPpacket object
	 * (this is useful for creating a new EOB_ACK packet from scratch).
	 */
	public class EndOfBlockAck {
		private static final int BN_START = 0;
		private static final int BN_END = 3;
		private static final int MISSING_SN_START = 4;

		int bn = -1;
		int[] missingSN = null;
		int numberOfMissingSN = -1;
		
		
		private EndOfBlockAck() {
			super();
		}
		
		
		private EndOfBlockAck(byte[] payl) {
			super();
			bn = Utils.bytes2int(Arrays.copyOfRange(payl, BN_START, BN_END + 1));
			numberOfMissingSN = (payl.length - MISSING_SN_START) / SN_LENGTH; //TODO handle errors?
			missingSN = new int[numberOfMissingSN];
			for (int i = 0; i < numberOfMissingSN; i++)
				missingSN[i] = Utils.bytes2int(Arrays.copyOfRange
						(payl, MISSING_SN_START + SN_LENGTH * i, MISSING_SN_START + SN_LENGTH * (i + 1)));
		}
		
		
		private void generateAndUpdatePayload() {			
			byte[] missingSNbytes = Utils.intarray2bytearray(missingSN, UTPpacket.SN_LENGTH);
			byte[] bnBytes = Utils.int2bytes(bn, BN_END - BN_START + 1);
			payl = new byte[missingSNbytes.length + MISSING_SN_START];
			
			System.arraycopy(missingSNbytes, 0, payl, MISSING_SN_START, missingSNbytes.length);
			System.arraycopy(bnBytes, 0, payl, BN_START, bnBytes.length);
		}
	}

}
