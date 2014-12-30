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

	private static final int INVALID = -1;
	
	static final int PKT_SIZE = Client.PKT_SIZE;
	static final int PKTS_IN_BLOCK = Client.PKTS_IN_BLOCK;
	static final int BLOCKS_IN_BUFFER = Client.BLOCKS_IN_BUFFER; //TODO temp
	
	static final int BYTES_IN_BLOCK = PKTS_IN_BLOCK * PKT_SIZE;
	static final int PKTS_IN_BUFFER = PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
	static final int BUFFERSIZE = BYTES_IN_BLOCK * BLOCKS_IN_BUFFER;

	static int listenPort = DEF_SERVER_PORT;
	static int channelPort = DEF_CHANNEL_PORT;
	static int clientPort = Client.DEF_CLIENT_PORT;
	static InetAddress clientAddr = null;




	public static void main(String[] args) throws IOException {

		final int NUMBER_OF_FIN = 10;
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


		// 
		byte[] rxBuffer = new byte[BUFFERSIZE];
	
		// File statistics
		int totNumPackets = 0;
		int totNumBytes = 0;
		int bytesWritten = 0;

		
		// Counters for duplicate data packets and packets belonging to future BNs
		// (for performance analysis purpose)
		int duplicateCounter = 0;
		int outOfWindowCounter = 0;

		// Array of flags. If receivedPkts[i] is true, the packet of index i in this block
		// has been received by this server.
		boolean[] receivedPkts = new boolean[PKTS_IN_BUFFER]; // all false
		
		int[] bnInBuffer = new int[BLOCKS_IN_BUFFER];
		for (int i = 0; i < BLOCKS_IN_BUFFER; i++)
			bnInBuffer[i] = i+1;
		int windowLast = BLOCKS_IN_BUFFER;
		int windowFirst = 1;
		int sizeOfLastPkt = INVALID;
		int bufferedBytes = 0;


		boolean theEnd = false; //Needed to stop the cycle
		boolean canShift = false;
		int lastSN = INVALID;
		int lastBN = INVALID;
		while(!theEnd)
		{

			// Receive UDP datagram
			DatagramPacket recvPkt = receiveDatagram(socket);

			// Process packet
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // payload of received UDP packet
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port

//			TODO Arrays.fill(receivedPkts, false);

			//DEBUG
			//Utils.logg("    Received  -  header: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
			//Utils.logg("    Received SN=" + recvUTPpkt.sn);
			//Utils.logg("\nPayload length = " + recvUTPpkt.payl.length);


			switch (recvUTPpkt.function) {
			case UTPpacket.FUNCT_DATA:
			{
				int bn = (recvUTPpkt.sn - 1) / PKTS_IN_BLOCK + 1;
				if (bn < windowFirst || bn > windowLast) {
					//Utils.logg("Received packet outside current window (BN = " + bn + ")");
					outOfWindowCounter++;
					if(bn < windowFirst)
						duplicateCounter++;
					break;
				}
				int bnIndexInBuffer = Arrays.binarySearch(bnInBuffer, bn); // TODO fix all this part after implementing a real window
				
				
				// If pktSize was defined, and this packet is smaller than usual, this must be
				// the last packet of the last block: end of transmission!
				if (PKT_SIZE != recvUTPpkt.payl.length) {
					
					if (sizeOfLastPkt == INVALID && lastSN == INVALID) {
						lastSN = recvUTPpkt.sn;
						lastBN = bn;
						sizeOfLastPkt = recvUTPpkt.payl.length;
						//int pktsInLastBlock = (lastSN - 1) % PKTS_IN_BLOCK + 1;
						//bytesInLastBlock = (pktsInLastBlock - 1) * PKT_SIZE + sizeOfLastPkt;

						// Save total number of packets and bytes of the file
						totNumPackets = lastSN;
						totNumBytes = (lastSN - 1) * PKT_SIZE + sizeOfLastPkt;
						// TODO compute these counters along the way, not here at the end!
					}
					
					else if (sizeOfLastPkt != INVALID && lastSN != INVALID && (sizeOfLastPkt != recvUTPpkt.payl.length || lastSN != recvUTPpkt.sn)) {
						System.err.println("Two different final packets... Don't know what to do!");
						Utils.logg(lastSN + " with size " + sizeOfLastPkt + " and now " + recvUTPpkt.sn + " with size " + recvUTPpkt.payl.length);
						System.exit(-1);
					}
					else if (sizeOfLastPkt != recvUTPpkt.payl.length || lastSN != recvUTPpkt.sn) {
						Utils.logg("LOL " + lastSN + " with size " + sizeOfLastPkt + " and now " + recvUTPpkt.sn + " with size " + recvUTPpkt.payl.length);
						System.exit(-1);
					}
					
				}

				int snOffsetInBlock = (recvUTPpkt.sn - 1) % PKTS_IN_BLOCK; // Position of this packet in its block: it can range from 0 to PKTS_IN_BLOCK-1
				int pktIndexInBuffer = bnIndexInBuffer * PKTS_IN_BLOCK + snOffsetInBlock;
				
				// Index of the first byte of the packet in the rx buffer: we're gonna write it there.
				int pktByteOffsInBuffer = pktIndexInBuffer * PKT_SIZE;
				
				// If it was already received, record it and continue.
				if (receivedPkts[pktIndexInBuffer]) {
					duplicateCounter++;
					//Utils.logg("Received SN=" + recvUTPpkt.sn + " duplicate");
					break;
				}
				
				// Copy received data in the write buffer at the correct position
				System.arraycopy(recvUTPpkt.payl, 0, rxBuffer, pktByteOffsInBuffer, recvUTPpkt.payl.length);
				
				// Update the array receivedPkts and the counter of buffered data
				receivedPkts[pktIndexInBuffer] = true;
				bufferedBytes += recvUTPpkt.payl.length;
				
				//Utils.logg("Received SN=" + recvUTPpkt.sn);

				break;
			}



			case UTPpacket.FUNCT_EOB:
			{
				// -- Fill in the array with missing SNs
				int[] missingSN = getMissingSN(receivedPkts, recvUTPpkt, bnInBuffer);
				int bn = recvUTPpkt.endOfBlock.bn;
				if (missingSN == null) {
					if (bn > windowLast || (lastBN != INVALID && bn > lastBN))
						break;
					if (bn < windowFirst) { // this condition shouldn't be necessary if we implement a simple linear window
						// Send ACK for old EOB
						Utils.logg("Sending ACK for old BN=" + bn);
						UTPpacket eobAckPkt = getEobAckPacket(bn, new int[0]);
						sendUtpPkt(eobAckPkt, socket, channelAddr, channelPort);
						sendUtpPkt(eobAckPkt, socket, channelAddr, channelPort); // try harder: the client won't even respond to this
						break;
					}
				}
				else
					Utils.logg(missingSN.length + "pkt\t missing from BN=" + bn);
				
				// -- Assemble and send EOB_ACK
				
				Utils.logg("Sending ACK for BN=" + bn);
				UTPpacket eobAckPkt = getEobAckPacket(bn, missingSN);
				sendUtpPkt(eobAckPkt, socket, channelAddr, channelPort);

				if (missingSN.length == 0) {
					// *This block has been received!*
					Utils.logg("Received correctly BN=" + bn);
					if (bn == windowFirst)
						canShift = true;
				}
				
				
				while (canShift) {

					Utils.logg("Shifting...");
					// Before shifting we need to know how many bytes we must write. Full block? Or is this the last block? We get this info from the size of the buffer alone.
					
					// Write on file the proper amount of bytes (the first block in the buffer)
					int bytesInThisBlock = Math.min(BYTES_IN_BLOCK, bufferedBytes);
					outStream.write(rxBuffer, 0, bytesInThisBlock);
					
					// Shift tx buffer
					Utils.shiftArrayLeft(rxBuffer, BYTES_IN_BLOCK);

					// Counters
					bytesWritten += bytesInThisBlock;
					bufferedBytes -= bytesInThisBlock;

					// Shift and update other entities
					Utils.shiftArrayLeft(bnInBuffer, 1);
					bnInBuffer[bnInBuffer.length - 1] = ++windowLast;
					Utils.shiftArrayLeft(receivedPkts, PKTS_IN_BLOCK); // indices not corresponding to any packet are false, so we must pay attention and not consider them
					windowFirst++;

					// See whether this is the last block, and decide if we can shift again the window
					int pktsInThisBlock = PKTS_IN_BLOCK;
					if (windowFirst == lastBN) { // we know which is the last BN, and it is now the first (and only one) in the buffer
						pktsInThisBlock = (lastSN - 1) % PKTS_IN_BLOCK + 1;
						theEnd = true;
					}
					boolean[] receivedPktsOldestBlock = Utils.resizeArray(receivedPkts, pktsInThisBlock);
					canShift = (Utils.count(receivedPktsOldestBlock, false) == 0) && (receivedPktsOldestBlock.length > 0);
				}

				break;
			}

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


		double percentRetxOverhead = (double)duplicateCounter/totNumPackets * 100;//FIXME
		Utils.logg(duplicateCounter + " duplicate data packets (" + new DecimalFormat("#0.00").format(percentRetxOverhead) + "% overhead)\n" + outOfWindowCounter + " data packets outside the window");
		Utils.logg(bytesWritten + " bytes written on disk");
		
		System.out.println("Bye bye, Client! ;-)");
	}








	/**
	 * @param bn
	 * @param missingSN
	 * @return
	 */
	private static UTPpacket getEobAckPacket(int bn, int[] missingSN) {
		UTPpacket eobAckPkt = new UTPpacket();
		eobAckPkt.sn = UTPpacket.INVALID_SN;
		eobAckPkt.dstAddr = clientAddr;
		eobAckPkt.dstPort = (short) clientPort;
		eobAckPkt.function = UTPpacket.FUNCT_EOB_ACK;
		eobAckPkt.setEndOfBlockAck(bn, missingSN);
		return eobAckPkt;
	}








	/**
	 * @param receivedPkts
	 * @param recvUTPpkt
	 * @param bnInBuffer
	 * @return
	 */
	private static int[] getMissingSN(boolean[] receivedPkts, UTPpacket recvUTPpkt, int[] bnInBuffer) {
		
		int[] missingSN;
		int bnIndexInBuffer = Arrays.binarySearch(bnInBuffer, recvUTPpkt.endOfBlock.bn); // TODO fix all this part after implementing a real window
		if (bnIndexInBuffer < 0) //FIXME
			return null;
		missingSN = new int[PKTS_IN_BLOCK];
		int firstSnOfThisBlock = PKTS_IN_BLOCK * (recvUTPpkt.endOfBlock.bn - 1) + 1;
		
		int missingSNindex = 0;
		for (int i = 0; i < recvUTPpkt.endOfBlock.numberOfSentSN; i++) {
			if (! receivedPkts[i + bnIndexInBuffer * PKTS_IN_BLOCK])
				missingSN[missingSNindex++] = i + firstSnOfThisBlock;
		}
		
		return Arrays.copyOf(missingSN, missingSNindex); // Truncate;
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