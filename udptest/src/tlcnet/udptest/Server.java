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
	private static final int END_TIMEOUT = 40000;		//To stop waiting for pcks
	private static final int RX_PKT_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP

	private static final int INVALID = -1;
	
	static final int PKT_SIZE = Client.PKT_SIZE;
	static final int PKTS_IN_BLOCK = Client.PKTS_IN_BLOCK;
	static final int BLOCKS_IN_BUFFER = Client.BLOCKS_IN_BUFFER; //TODO update these during transmission?
	
	static final int BYTES_IN_BLOCK = PKTS_IN_BLOCK * PKT_SIZE;
	static final int PKTS_IN_BUFFER = PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
	static final int BUFFERSIZE = BYTES_IN_BLOCK * BLOCKS_IN_BUFFER;
	
	private static final int NUMBER_OF_FIN = 10;

	private static int listenPort = DEF_SERVER_PORT;
	private static int channelPort = DEF_CHANNEL_PORT;
	private static int clientPort = Client.DEF_CLIENT_PORT;
	private static InetAddress clientAddr = null;
	
	static int eobSn = 1;




	public static void main(String[] args) throws IOException {

		InetAddress channelAddr = null;
		String filename = null;
		FileOutputStream outStream = null;

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
			outStream = new FileOutputStream(filename, false);

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
			outStream.close(); System.exit(-1);
		}










		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//



		// Sent and received data statistics
		int receivedPackets = 0;
		int bytesWritten = 0;
		int bufferedBytes = 0;
		int receivedBytes = 0;
		
		// This object's job is to identify EOB ACK packets that have already been received before.
		// These packets will be dropped, so as to avoid useless retransmission of data packets.
		DuplicateIdHandler dupEobHandler = new DuplicateIdHandler();

		// Counters for duplicate data packets and packets belonging to future BNs (for performance analysis purpose)
		int duplicateCounter = 0;
		int outOfWindowCounter = 0;

		// Arrays of flags. If receivedPkts[i] is true, the packet of index i in the buffer has been received
		// by this server. The same applies to blockAcked as regards correct reception of whole blocks.
		boolean[] receivedPkts = new boolean[PKTS_IN_BUFFER]; // all false
		boolean[] isBlockAcked = new boolean[BLOCKS_IN_BUFFER];
		
		// The first and last block in the window are windowLeft and windowRight. The window is the rx buffer.
		int windowRight = BLOCKS_IN_BUFFER;
		int windowLeft = 1;
		byte[] rxBuffer = new byte[BUFFERSIZE];
		
		int sizeOfLastPkt = INVALID;
		int lastSN = INVALID;
		int lastBN = INVALID;

		boolean theEnd = false;
		boolean canShift = false;
		while(!theEnd)
		{

			// Receive UDP datagram
			DatagramPacket recvPkt = receiveDatagram(socket);

			// Process packet
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // payload of received UDP packet
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port

			switch (recvUTPpkt.function) {
			case UTPpacket.FUNCT_DATA:
			{
				// Get block number for this data packet, and discard if it's outside the window
				int bn = (recvUTPpkt.sn - 1) / PKTS_IN_BLOCK + 1;
				if (bn < windowLeft || bn > windowRight) {
					outOfWindowCounter++;
					if(bn < windowLeft)
						duplicateCounter++;
					break;
				}
				
				// Index of this packet's block in the current window
				int bnIndexInWindow = bn - windowLeft;
				
				
				// If pktSize was defined, and this packet is smaller than usual, this must be the last packet of the last block
				if (PKT_SIZE != recvUTPpkt.payl.length) {
					
					if (sizeOfLastPkt == INVALID && lastSN == INVALID) {
						lastSN = recvUTPpkt.sn;
						lastBN = bn;
						sizeOfLastPkt = recvUTPpkt.payl.length;
						Utils.logg("Last packet is SN=" + lastSN + " with size " + sizeOfLastPkt);
					}
					// Two different final packets: this should never happen!
					else if (sizeOfLastPkt != INVALID && lastSN != INVALID && (sizeOfLastPkt != recvUTPpkt.payl.length || lastSN != recvUTPpkt.sn)) {
						System.err.println("Two different final packets... Don't know what to do!");
						Utils.logg(lastSN + " with size " + sizeOfLastPkt + " and now " + recvUTPpkt.sn + " with size " + recvUTPpkt.payl.length);
						System.exit(-1);
					}
					// This makes no sense. TODO: check if it's _really_ impossible and delete these lines.
					else if (sizeOfLastPkt != recvUTPpkt.payl.length || lastSN != recvUTPpkt.sn) {
						Utils.logg("wut"); System.exit(-1);
					}
				}

				// Position of this packet in its block: it can range from 0 to (PKTS_IN_BLOCK - 1)
				int snOffsetInBlock = (recvUTPpkt.sn - 1) % PKTS_IN_BLOCK;
				
				// Position of this packet in the current window
				int pktIndexInWindow = bnIndexInWindow * PKTS_IN_BLOCK + snOffsetInBlock;
				
				// Index of the first byte of the packet in the rx buffer: we're gonna write it there.
				int pktByteOffsInBuffer = pktIndexInWindow * PKT_SIZE;
				
				// If it was already received, record it and continue.
				if (receivedPkts[pktIndexInWindow]) {
					duplicateCounter++;
					//Utils.logg("Received SN=" + recvUTPpkt.sn + " duplicate");
					break;
				}
				
				// Copy received data in the write buffer at the correct position
				System.arraycopy(recvUTPpkt.payl, 0, rxBuffer, pktByteOffsInBuffer, recvUTPpkt.payl.length);
				
				// Update the boolean register of received packets and various counters
				receivedPkts[pktIndexInWindow] = true;
				bufferedBytes += recvUTPpkt.payl.length;
				receivedBytes += recvUTPpkt.payl.length;
				receivedPackets++;
				
				//Utils.logg("Received SN=" + recvUTPpkt.sn);

				break;
			}



			case UTPpacket.FUNCT_EOB:
			{
				// -- Fill in the array with missing SNs
				if (!dupEobHandler.isNew(recvUTPpkt.sn)) continue; // TODO This seems to half the throughput. Maybe we should send even more ACKs instead?
				int[] missingSN = getMissingSN(receivedPkts, recvUTPpkt, windowLeft, windowRight);
				int bn = recvUTPpkt.endOfBlock.bn;
				if (missingSN == null) {
					if (bn > windowRight || (lastBN != INVALID && bn > lastBN))
						break;
					if (bn < windowLeft) { // this condition shouldn't be necessary if we implement a simple linear window
						// Send ACK for old EOB
						//Utils.logg("Sending ACK for old BN=" + bn);
						UTPpacket eobAckPkt = assembleEobAckPacket(bn, new int[0]);
						sendUtpPkt(eobAckPkt, socket, channelAddr, channelPort);
						sendUtpPkt(eobAckPkt, socket, channelAddr, channelPort); // try harder: the client won't even respond to this
						break;
					}
				}
				else Utils.logg(missingSN.length + "pkt\t missing from BN=" + bn);

				// Index of this packet's block in the current window
				int bnIndexInWindow = bn - windowLeft;
				
				// If the block was already acked, skip processing of this EOB packet
				if (isBlockAcked[bnIndexInWindow]) continue;
				
				
				// -- Assemble and send EOB_ACK

				int numOfEobAckTx = 8;//missingSN.length / 50 + 3;
				UTPpacket eobAckPkt = assembleEobAckPacket(bn, missingSN);
				for (int k = 0; k < numOfEobAckTx; k++)
					sendUtpPkt(eobAckPkt, socket, channelAddr, channelPort);

				// This block has been received correctly!
				if (missingSN.length == 0) {
					Utils.logg("Received correctly BN=" + bn);
					isBlockAcked[bnIndexInWindow] = true;
					if (bn == windowLeft)
						canShift = true;
				}
				
				
				
				while (canShift) {
					Utils.logg("windowLeft=" + windowLeft + "  -  now shifting by 1");
					
					// Before shifting we need to know how many bytes we must write. Full block? Or is this the
					// last block? We get this info from the size of the buffer alone.
					
					// Write on file the proper amount of bytes (the first block in the buffer)
					int bytesInThisBlock = Math.min(BYTES_IN_BLOCK, bufferedBytes);
					outStream.write(rxBuffer, 0, bytesInThisBlock);
					//if(bytesInThisBlock != BYTES_IN_BLOCK) Utils.logg("The buffer has only " + bufferedBytes + " bytes left: last block");

					// Counters
					bytesWritten += bytesInThisBlock;
					bufferedBytes -= bytesInThisBlock;

					// Shift the window, i.e. shift rx buffer and other entities that depend on the window itself
					Utils.shiftArrayLeft(rxBuffer, BYTES_IN_BLOCK);
					Utils.shiftArrayLeft(isBlockAcked, 1);
					windowLeft++; windowRight++;
					Utils.shiftArrayLeft(receivedPkts, PKTS_IN_BLOCK); // indices not corresponding to any packet are false, so we must pay attention and not consider them

					// See whether this NEW block (AFTER the shifting) is the last one, and decide if we can shift again the window
					int pktsInThisBlock = PKTS_IN_BLOCK;
					if (windowLeft == lastBN) // we know which is the last BN, and it is now the first (and only one) in the buffer
						pktsInThisBlock = (lastSN - 1) % PKTS_IN_BLOCK + 1;
					boolean[] receivedPktsOldestBlock = Utils.resizeArray(receivedPkts, pktsInThisBlock);
					canShift = (Utils.count(receivedPktsOldestBlock, false) == 0) && (pktsInThisBlock > 0);
					if (windowLeft == lastBN + 1)
						theEnd = true;
				}
				
				break;
			}

			default:
				// Ignore any other type of packet
				Utils.logg("Invalid packet received: neither DATA nor EOB");
			}
		}
		
		
		if (bufferedBytes > 0) { // should never happen except in case of bugs!
			outStream.write(rxBuffer, 0, bufferedBytes);
			System.out.println("What's happening? " + bufferedBytes + " bytes still in the buffer! Let's write them out");
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


		Utils.logg(receivedPackets + " packets in the file, while " + (receivedPackets + duplicateCounter) + " data packets were received");
		double percentRetxOverhead = (double)duplicateCounter/receivedPackets * 100;
		Utils.logg(duplicateCounter + " duplicate data packets (" + new DecimalFormat("#0.00").format(percentRetxOverhead) + "% overhead)\n" + outOfWindowCounter + " data packets outside the window");
		Utils.logg(receivedBytes + " bytes received\n" + bytesWritten + " bytes written on disk");
		
		System.out.println("Bye bye, Client! ;-)");
	}








	/**
	 * Assembles an End Of Block Acknowledgement packet for the specified Block Number, including the
	 * list of the Sequence Numbers of missing data packets.
	 * 
	 * @param bn - the Block Number of the block that's being dealt with
	 * @param missingSN - the array of SNs of missing data packets in the current block
	 * @return the assembled UTP packet
	 */
	private static UTPpacket assembleEobAckPacket(int bn, int[] missingSN) {
		
		UTPpacket eobAckPkt = new UTPpacket();
		eobAckPkt.sn = eobSn++;
		eobAckPkt.dstAddr = clientAddr;
		eobAckPkt.dstPort = (short) clientPort;
		eobAckPkt.function = UTPpacket.FUNCT_EOB_ACK;
		eobAckPkt.setEndOfBlockAck(bn, missingSN);
		return eobAckPkt;
	}






	/**
	 * Given the current window, the register of received packets and the EOB packet just received, it returns the
	 * array of Sequence Numbers of the packets that are missing from the block that this EOB packet advertises. 
	 * 
	 * @param receivedPkts - the boolean register of received packets for the whole window: this method only considers
	 * the part of this array that concerns the block advertised by the EOB packet
	 * @param eobPkt - the EOB packet that advertises the packets that the server should have received
	 * @param windowLeft - the leftmost BN of the current window
	 * @param windowRight - the rightmost BN of the current window
	 * @return the array of Sequence Numbers of the packets that are missing from the block that this EOB packet advertises
	 */
	private static int[] getMissingSN(boolean[] receivedPkts, UTPpacket eobPkt, int windowLeft, int windowRight) {
		
		int bn = eobPkt.endOfBlock.bn;
		if (bn < windowLeft || bn > windowRight)
			return null;
		
		int bnIndexInBuffer = eobPkt.endOfBlock.bn - windowLeft;
		int[] missingSN = new int[PKTS_IN_BLOCK];
		int firstSnOfThisBlock = PKTS_IN_BLOCK * (eobPkt.endOfBlock.bn - 1) + 1;
		
		int missingSNindex = 0;
		for (int i = 0; i < eobPkt.endOfBlock.numberOfSentSN; i++) {
			if (! receivedPkts[i + bnIndexInBuffer * PKTS_IN_BLOCK])
				missingSN[missingSNindex++] = i + firstSnOfThisBlock;
		}
		
		return Arrays.copyOf(missingSN, missingSNindex); // Truncate
	}








	/**
	 * Receives a datagram from the given socket and returns it. It stops execution of the process if
	 * socket.receive() reaches the timeout set for this socket, or if an I/O error occurs.
	 * 
	 * @param socket - the socket on which the datagram will be received
	 * @return the received datagram
	 */
	private static DatagramPacket receiveDatagram(DatagramSocket socket) {

		byte[] recvBuf = new byte[RX_PKT_BUFSIZE];
		DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
		try {
			socket.receive(recvPkt);
		} catch (SocketTimeoutException e) {		
			System.err.println("Connection timeout: exiting");
			System.exit(-1);
		} catch(IOException e) {
			System.err.println("I/O error while receiving datagram:\n" + e);
			socket.close(); System.exit(-1);
		}
		return recvPkt;
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