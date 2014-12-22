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

/* TODO: What if the Server starts listening after the Client has started transmitting?
 */


public class Server {

	static final int DEF_CHANNEL_PORT = 65432;
	static final int DEF_SERVER_PORT = 65433;
	private static final short END_TIMEOUT = 20000;		//To stop waiting for pcks
	private static final int RX_PKT_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP

	// Initial blockSize. It is updated while receiving the first block.
	// The array of flags for received packets, and the write buffer, have a size that
	// depends on blockSize
	private static final int INIT_BLOCKSIZE = 20; // TODO: this must be updated when receiving

	private static final int INVALID_PKTSIZE = -1;





	public static void main(String[] args) throws IOException {

		int listenPort = DEF_SERVER_PORT;
		int channelPort = DEF_CHANNEL_PORT;
		int clientPort = Client.DEF_CLIENT_PORT;
		InetAddress clientAddr = null;
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


		// The write buffer holds 
		byte[] writeBuffer = null;
	
		// File statistics
		int totNumPackets = 0;
		int totNumBytes = 0;
		
		// Counters for duplicate data packets and packets belonging to future BNs
		// (for performance analysis purpose)
		int duplicateCounter = 0;
		int futureBlockArrivals = 0;

		// Array of flags. If receivedPkts[i] is true, the packet of index i in this block
		// has been received by this server.
		boolean[] receivedPkts = new boolean[INIT_BLOCKSIZE]; // all false

		// Current Block Number. The transmission focuses on a block with a certain BN before getting
		// to the next one (block-based stop and wait ARQ), so this is a sort of incremental counter.
		int bn = 1;
		
		// This flag indicates whether our knowledge of the size of the block is complete. This is
		// achieved at the first reception of EOB (block number = 1). Before that, the block size at
		// the server is temporary, and adapts during the reception of data in the first block.
		boolean blockSizeIsFinal = false;

		int pktSize = INVALID_PKTSIZE;
		int blockSize = INIT_BLOCKSIZE;	// TODO update this along the way
		int bytesInCurrBlock = -1;


		boolean theEnd = false; //Needed to stop the cycle
		boolean lastBlock = false;
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




			// ---- Process packet ----

			// payload of received UDP packet
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			InetAddress channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port


			//DEBUG
			//Utils.logg("\n------ RECEIVED\nHeader:\n" + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
			//Utils.logg("Received SN=" + recvUTPpkt.sn);
			//Utils.logg("\nPayload length = " + recvUTPpkt.payl.length);


			switch (recvUTPpkt.function) {
			case UTPpacket.FUNCT_DATA:

				// Store pktSize if this is the first packet, assuming all packets have the
				// same length( except for the last one)
				if (pktSize == INVALID_PKTSIZE) {
					pktSize = recvUTPpkt.payl.length; // is this robust?
					writeBuffer = new byte[pktSize * blockSize];
				}
				
				// If this is still the first block but we're receiving data packets that exceed this block, then
				// the block is actually larger than we thought: double blockSize and resize arrays as needed.
				if (bn==1 && recvUTPpkt.sn > blockSize) {
					blockSize *= 2;
					receivedPkts = Utils.resizeArray(receivedPkts, blockSize);
					writeBuffer = Utils.resizeArray(writeBuffer, pktSize * blockSize);
				}
				
				// Note: if a packet from a block BN>currBN arrives, blockSize increases and this packet
				// is thought to be of the current BN. Later, when the first block ends, we will know the
				// actual size of the block and truncate the appropriate arrays to size blockSize.
					
				// If pktSize was defined, and this packet is smaller than usual, this must be
				// the last packet of the last block: end of transmission!
				else if (pktSize != recvUTPpkt.payl.length) { // this should never happen except in the end of transmission
					lastBlock = true; // Maybe an additional check that this is the end?
					int pktsInLastBlock = (recvUTPpkt.sn - 1) % blockSize + 1;
					bytesInCurrBlock = (pktsInLastBlock - 1) * pktSize + recvUTPpkt.payl.length;
					
					// Save total number of packets and bytes of the file
					totNumPackets = recvUTPpkt.sn;
					totNumBytes = (recvUTPpkt.sn - 1) * pktSize + recvUTPpkt.payl.length;
				}

				// Offset for SN: the first packet of block bn has SN=snOffset
				int snOffset = 1 + blockSize * (bn - 1);

				// If the current packet belongs to a different block, discard it.
				// TODO Handle timeout
				if (recvUTPpkt.sn < snOffset || recvUTPpkt.sn >= snOffset + blockSize) {
					Utils.logg("Received SN=" + recvUTPpkt.sn + " -> discard (wrong block)");
					if (recvUTPpkt.sn < snOffset)
						duplicateCounter++;
					else
						// This never happens
						futureBlockArrivals++;
					break;
				}				

				// TODO: writeBuffer and receivedPkts must grow if there are more packets than expected by INIT_BLOCKSIZE (only if this is bn==1)
				
				// Index [0, blockSize] of this packet in the current block
				int pktIndexInCurrBlock = (recvUTPpkt.sn - 1) % blockSize;
				
				// Index of the first byte of the packet in the write buffer: we're gonna write it there.
				int pktByteOffsInCurrBlock = pktIndexInCurrBlock * pktSize;
				
				// If we're here, the received data belongs to the current block. If it was already received, record it and continue.
				if (receivedPkts[pktIndexInCurrBlock]) {
					duplicateCounter++;
					Utils.logg("Received SN=" + recvUTPpkt.sn + " duplicate");
					break;
				}
				
				// Copy received data in the write buffer at the correct position
				System.arraycopy(recvUTPpkt.payl, 0, writeBuffer, pktByteOffsInCurrBlock, recvUTPpkt.payl.length);
				
				// Update receivedPkts array
				receivedPkts[pktIndexInCurrBlock] = true;
				
				//Utils.logg("Received SN=" + recvUTPpkt.sn);

				break;





			case UTPpacket.FUNCT_EOB:

				if (recvUTPpkt.endOfBlock.bn < bn) {
					// This is an EOB for a BN that was already ACKed (EOB_ACK was probably lost)
					// so we retx the EOB_ACK for that BN.
					
					Utils.logg("EOB from old BN: retransmit BN");
					// Assemble EOB_ACK
					UTPpacket eobAckPkt = new UTPpacket();
					eobAckPkt.sn = UTPpacket.INVALID_SN;
					eobAckPkt.dstAddr = clientAddr;
					eobAckPkt.dstPort = (short) clientPort;
					eobAckPkt.function = UTPpacket.FUNCT_EOB_ACK;
					eobAckPkt.setEndOfBlockAck(bn, new int[0]);

					// Send EOB_ACK
					byte[] sendData = eobAckPkt.getRawData();
					DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
					try {
						socket.send(sendPkt);
					} catch(IOException e) {
						System.err.println("I/O error while sending datagram:\n" + e);
						socket.close(); System.exit(-1);
					}
					break;

				}
				else if (recvUTPpkt.endOfBlock.bn > bn) {
					Utils.logg("! Wrong block number");
					break;
				}

				//TODO: If bn==1, use sentSN to increase further the size of writeBuffer and receivedPkts

				if (bn == 1 && !blockSizeIsFinal) {
					blockSize = recvUTPpkt.endOfBlock.numberOfSentSN;
					blockSizeIsFinal = true;
					Utils.resizeArray(receivedPkts, blockSize);
					Utils.resizeArray(writeBuffer, blockSize);
					Utils.logg("Final blockSize: " + blockSize + " packets");
				}
				
				// Fill in the array with missing SNs
				int[] missingSN = new int[blockSize];
				int missingSNindex = 0;
				for (int i = 0; i < recvUTPpkt.endOfBlock.numberOfSentSN; i++) {
					if (! receivedPkts[i])
						missingSN[missingSNindex++] = i + 1 + (bn - 1) * blockSize;
				}
				missingSN = Arrays.copyOf(missingSN, missingSNindex); // Truncate

				// Assemble EOB_ACK
				UTPpacket eobAckPkt = new UTPpacket();
				eobAckPkt.sn = UTPpacket.INVALID_SN;
				eobAckPkt.dstAddr = clientAddr;
				eobAckPkt.dstPort = (short) clientPort;
				eobAckPkt.function = UTPpacket.FUNCT_EOB_ACK;
				eobAckPkt.setEndOfBlockAck(bn, missingSN);

				// Send EOB_ACK
				byte[] sendData = eobAckPkt.getRawData();
				DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
				try {
					socket.send(sendPkt);
				} catch(IOException e) {
					System.err.println("I/O error while sending datagram:\n" + e);
					socket.close(); System.exit(-1);
				}

				if (missingSN.length == 0) {
					// *This block has been received!*
					Utils.logg("Received correctly BN=" + bn);
					if (!lastBlock)
						bytesInCurrBlock = pktSize * blockSize;
					try {
						fileOutputStream.write(writeBuffer, 0, bytesInCurrBlock);
					} catch (IOException e) {
						e.printStackTrace();
					}
					
					// If this is the last block, and there are no missing packets, then
					// this is the ACTUAL end of transmission
					if (lastBlock) {
						theEnd = true;
						break;
					}

					// Reset flags for received packets of current block, and increment BN
					Arrays.fill(receivedPkts, false);
					bn++;
				}


				break;

			default:
				// Ignore any other type of packet
				Utils.logg("Invalid packet received: neither DATA nor EOB");
			}


			// TODO: (for the Client as well) Maybe read/write asynchronously from/to file so as to optimize computational time for I/O operations?

		}
		
		double percentRetxOverhead = (double)duplicateCounter/totNumPackets * 100;
		Utils.logg(duplicateCounter + " duplicate data packets (" + new DecimalFormat("#0.00").format(percentRetxOverhead) + "% overhead)\n" + futureBlockArrivals + " data packets from future blocks");
		Utils.logg(totNumBytes + " bytes received");
		
		System.out.println("Bye bye, Client! ;-)");
	}
}