package tlcnet.udptest;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.Arrays;

// TODO: What if the Server starts listening after the Client has started transmitting?


public class Server {

	static final int DEF_CHANNEL_PORT = 65432;
	static final int DEF_SERVER_PORT = 65433;
	private static final short END_TIMEOUT = 20000;		//To stop waiting for pcks
	private static final int RX_PKT_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP


	private static final int INIT_BLOCKSIZE = 60; // TODO: this must be updated when receiving

	private static final int INVALID_PKTSIZE = -1;
	
	static final int PKT_SIZE = Client.PKT_SIZE;
	static final int PKTS_IN_BLOCK = Client.PKTS_IN_BLOCK;
	static final int BLOCKS_IN_BUFFER = Client.BLOCKS_IN_BUFFER * 10; //TODO temp
	static final int BLOCKSIZE = PKTS_IN_BLOCK * PKT_SIZE;
	static final int PKTS_IN_BUFFER = PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
	static final int BUFFERSIZE = BLOCKSIZE * BLOCKS_IN_BUFFER;
	





	public static void main(String[] args) throws IOException {

		final int NUMBER_OF_FIN = 10;
		int listenPort = DEF_SERVER_PORT;
		int channelPort = DEF_CHANNEL_PORT;
		int clientPort = Client.DEF_CLIENT_PORT;
		InetAddress clientAddr = null;
		InetAddress channelAddr = null;
		String filename = null;
		FileOutputStream fileOutputStream = null;

		// Check input parameters
		if (args.length != 2) {
			System.out.println("Usage: java Server <client address> <path to new file>");
			return;
		}

		try {

			// Get address of client from command line parameter
			clientAddr = InetAddress.getByName(args[0]);

			// Create output file and overwrite if it already exists
			filename = args[1];
			fileOutputStream = new FileOutputStream(filename, false);

		} catch (UnknownHostException e) {
			System.err.println(e); return;
		} catch (FileNotFoundException e) {
			System.err.println("Cannot create file!\n" + e); return;
		}

		// --- Create the socket ---
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(listenPort);
			socket.setSoTimeout(END_TIMEOUT);
		} catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			fileOutputStream.close(); System.exit(-1);
		}










		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//


		// 
		byte[] writeBuffer = new byte[BUFFERSIZE];
	
		// File statistics
		int totNumPackets = 0;
		int totNumBytes = 0;

		
		// Counters for duplicate data packets and packets belonging to future BNs
		// (for performance analysis purpose)
		int duplicateCounter = 0;
		int futureBlockArrivals = 0;

		// Array of flags. If receivedPkts[i] is true, the packet of index i in this block
		// has been received by this server.
		boolean[] receivedPkts = new boolean[PKTS_IN_BUFFER]; // all false

		int bytesInCurrBlock = -1;


		boolean theEnd = false; //Needed to stop the cycle
		boolean lastBlock = false;
		int lastSN = 0;
		while(!theEnd)
		{

			// ---- Receive packet ----

			byte[] recvBuf = new byte[RX_PKT_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try {
				socket.receive(recvPkt);
			} catch (SocketTimeoutException e) {		
				System.out.println("Closing connection: FIN not received...");
				break;
			} catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}

			
			
//			Arrays.fill(receivedPkts, false);



			// ---- Process packet ----

			// payload of received UDP packet
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port


			//DEBUG
			//Utils.logg("    Received  -  header: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
			//Utils.logg("    Received SN=" + recvUTPpkt.sn);
			//Utils.logg("\nPayload length = " + recvUTPpkt.payl.length);


			switch (recvUTPpkt.function) {
			case UTPpacket.FUNCT_DATA:

				// Note: if a packet from a block BN>currBN arrives, blockSize increases and this packet
				// is thought to be of the current BN. Later, when the first block ends, we will know the
				// actual size of the block and truncate the appropriate arrays to size blockSize.
					
				// If pktSize was defined, and this packet is smaller than usual, this must be
				// the last packet of the last block: end of transmission!
				if (PKT_SIZE != recvUTPpkt.payl.length) { // this should never happen except in the end of transmission
					lastBlock = true;
					lastSN = recvUTPpkt.sn;
					int pktsInLastBlock = (recvUTPpkt.sn - 1) % PKTS_IN_BLOCK + 1;
					bytesInCurrBlock = (pktsInLastBlock - 1) * PKT_SIZE + recvUTPpkt.payl.length;
					
					// Save total number of packets and bytes of the file
					totNumPackets = recvUTPpkt.sn;
					totNumBytes = (recvUTPpkt.sn - 1) * PKT_SIZE + recvUTPpkt.payl.length;
					// TODO compute these counters along the way, not here at the end!
				}

				// Index [0, blockSize] of this packet in the current block
				int pktIndexInCurrBlock = (recvUTPpkt.sn - 1) % PKTS_IN_BUFFER;
				
				// Index of the first byte of the packet in the write buffer: we're gonna write it there.
				int pktByteOffsInCurrBlock = pktIndexInCurrBlock * PKT_SIZE;
				
				// If we're here, the received data belongs to the current block. If it was already received, record it and continue.
//				Utils.logg("arraylength= " + receivedPkts.length + "\tindex=" + pktIndexInCurrBlock);
				if (receivedPkts[pktIndexInCurrBlock]) {
					duplicateCounter++;
					//Utils.logg("Received SN=" + recvUTPpkt.sn + " duplicate");
					break;
				}
				
				// Copy received data in the write buffer at the correct position
				System.arraycopy(recvUTPpkt.payl, 0, writeBuffer, pktByteOffsInCurrBlock, recvUTPpkt.payl.length);
				
				// Update receivedPkts array
				receivedPkts[pktIndexInCurrBlock] = true;
				
				Utils.logg("Received SN=" + recvUTPpkt.sn);
				//Utils.logg(" == Pkt " + recvUTPpkt.sn + ": " + new String(recvUTPpkt.payl).substring(0, 12));

				break;





			case UTPpacket.FUNCT_EOB:


				// -- Fill in the array with missing SNs
				
				int[] missingSN = new int[PKTS_IN_BUFFER];
				int missingSNindex = 0;
				for (int i = 0; i < recvUTPpkt.endOfBlock.numberOfSentSN; i++) {
					if (! receivedPkts[i + PKTS_IN_BLOCK * (recvUTPpkt.endOfBlock.bn - 1)])
						missingSN[missingSNindex++] = i + PKTS_IN_BLOCK * (recvUTPpkt.endOfBlock.bn - 1) + 1;
				}
				missingSN = Arrays.copyOf(missingSN, missingSNindex); // Truncate

				Utils.logg(missingSNindex + "pkt\t missing from BN=" + recvUTPpkt.endOfBlock.bn);
				
				// -- Assemble and send EOB_ACK
				
				UTPpacket eobAckPkt = new UTPpacket();
				eobAckPkt.sn = UTPpacket.INVALID_SN;
				eobAckPkt.dstAddr = clientAddr;
				eobAckPkt.dstPort = (short) clientPort;
				eobAckPkt.function = UTPpacket.FUNCT_EOB_ACK;
				eobAckPkt.setEndOfBlockAck(recvUTPpkt.endOfBlock.bn, missingSN);
				Utils.logg("Sending ACK for BN=" + recvUTPpkt.endOfBlock.bn);
				sendUtpPkt(eobAckPkt, socket, channelAddr, channelPort);

				if (missingSN.length == 0) {
					// *This block has been received!*
					Utils.logg("Received correctly BN=" + recvUTPpkt.endOfBlock.bn);
					if (!lastBlock)
						bytesInCurrBlock = BLOCKSIZE;
				}

				// This is the ACTUAL end of transmission
				if (lastBlock) {
					boolean[] receivedPktsLastChunk = Utils.resizeArray(receivedPkts, lastSN); //FIXME
					if (Utils.count(receivedPktsLastChunk, false) == 0)
					theEnd = true;
					break;
				}

				break;

			default:
				// Ignore any other type of packet
				Utils.logg("Invalid packet received: neither DATA nor EOB");
			}

		}
		
		// Send multiple FIN
		Utils.logg("Sending multiple FIN...\n");
		UTPpacket finPacket = new UTPpacket();
		finPacket.dstAddr = clientAddr;
		finPacket.dstPort = (short) clientPort;
		finPacket.function = UTPpacket.FUNCT_FIN;
		for (int i = 0; i < NUMBER_OF_FIN; i++) {
			sendUtpPkt(finPacket, socket, channelAddr, channelPort);
		}


		double percentRetxOverhead = (double)duplicateCounter/totNumPackets * 100;
		Utils.logg(duplicateCounter + " duplicate data packets (" + new DecimalFormat("#0.00").format(percentRetxOverhead) + "% overhead)\n" + futureBlockArrivals + " data packets from future blocks");
		Utils.logg(totNumBytes + " bytes received");
		
		fileOutputStream.write(writeBuffer, 0, totNumBytes);
		
		System.out.println("Bye bye, Client! ;-)");
	}
	
	






	/**
	 * Sends an UTP packet over UDP, using the predefined socket, to the remote address and port specified as parameters.
	 * If an exception occurs, this method forces the process to exit.
	 * 
	 * @param utpPkt
	 * @param socket
	 * @param remoteAddr
	 * @param remotePort
	 */
	private static void sendUtpPkt(UTPpacket utpPkt, DatagramSocket socket,	InetAddress remoteAddr, int remotePort) {

		byte[] sendData = utpPkt.getRawData();
		DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, remoteAddr, remotePort); 

		try {
			socket.send(sendPkt);
		} catch(IOException e) {
			System.err.println("I/O error while sending datagram:\n" + e);
			socket.close(); System.exit(-1);
		}
	}
}