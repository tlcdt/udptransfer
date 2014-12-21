package tlcnet.udptest;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

/* TODO: What if the Server starts listening after the Client has started transmitting?
 * The first received pkt has SN > 1.
 * Should we consider this an error and abort?
 */


public class Server {
	
	static final int DEF_CHANNEL_PORT = 65432;
	static final int DEF_SERVER_PORT = 65433;
	private static final short END_TIMEOUT = 20000;		//To stop waiting for pcks
	private static final int RX_PKT_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	
	// When the write buffer exceeds this number of bytes, it is written on the output file
//	private static final int WRITEBUF_THRESH = 20 * 1024;
	
	private static final int INIT_BLOCKSIZE = 50; // TODO: this must be updated when receiving
	
	private static final int INVALID_PKTSIZE = -1;

	

	

	public static void main(String[] args) {

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
			System.err.println("Cannot create file!\n" + e);
		}

		// --- Create the socket ---
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(listenPort);
			socket.setSoTimeout(END_TIMEOUT);
		}
		catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			System.exit(-1);
		}










		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//

		
		byte[] writeBuffer = null;
		boolean[] receivedPkts = new boolean[INIT_BLOCKSIZE]; // all false
		int bn = 1;
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




			// ---- Process packet and prepare new packet ----

			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());  // payload of recv UDP packet
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			InetAddress channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port

			
			// TODO: this is not the right place for these lines.
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.dstAddr = clientAddr;
			sendUTPpkt.dstPort = (short) clientPort;
			sendUTPpkt.sn = recvUTPpkt.sn;
			sendUTPpkt.payl = new byte[0];
			
			
			//DEBUG
			//Utils.logg("\n------ RECEIVED\nHeader:\n" + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
			Utils.logg("SN=" + recvUTPpkt.sn);
			//Utils.logg("\nPayload length = " + recvUTPpkt.payl.length);
			
			
			switch (recvUTPpkt.function) {
			case UTPpacket.FUNCT_DATA:

				// Update pktSize if this is the first packet
				if (pktSize == INVALID_PKTSIZE) {
					pktSize = recvUTPpkt.payl.length; // store packet size derived from first data packet; TODO: is this robust?
					writeBuffer = new byte[pktSize * blockSize];
				}
				else if (pktSize != recvUTPpkt.payl.length) { // this should never happen except in the end of transmission
					lastBlock = true; // TODO: additional check that this is the end?
					int pktsInLastBlock = (recvUTPpkt.sn - 1) % blockSize + 1;
					bytesInCurrBlock = (pktsInLastBlock - 1) * pktSize + recvUTPpkt.payl.length;
				}
				
				
				// Copy received data in the write buffer at the correct position
				// TODO: writeBuffer and receivedPkts must grow if there are more packets than expected by INIT_BLOCKSIZE (only if this is bn==1)
				int pktIndexInCurrBlock = (recvUTPpkt.sn - 1) % blockSize;
				int pktOffsInCurrBlock = pktIndexInCurrBlock * pktSize;
				System.arraycopy(recvUTPpkt.payl, 0, writeBuffer, pktOffsInCurrBlock, recvUTPpkt.payl.length);
				// TODO: pay attention: last packet is smaller, last block has fewer packets. At the end, remove zero padding before writing on file.
				
				// Update receivedPkts array
				receivedPkts[pktIndexInCurrBlock] = true;

				break;
				
				
				
				
				
			case UTPpacket.FUNCT_EOB:
				
				if (recvUTPpkt.endOfBlock.bn != bn)
					Utils.logg("! Wrong block number");
				
				//TODO: If bn==1, use sentSN to increase further the size of writeBuffer and receivedPkts
				//TODO: If this is an old bn, ignore it.
				
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
					if (lastBlock) {
						theEnd = true;	// FIXME: this is useless
						break;
					}
					
					Arrays.fill(receivedPkts, false);
					bn++;
				}
				
				
				break;
				
			case UTPpacket.FUNCT_FIN:
				sendUTPpkt.function = UTPpacket.FUNCT_ACKFIN;
				theEnd = true;
				break;
			default:
				Utils.logg("Wut?");
				System.exit(-1);
			}

			//byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram




			




			
			
			

			// TODO: (for the Client as well) Maybe read/write asynchronously from/to file so as to optimize computational time for I/O operations?





			// --- Send ACK or FINACK ---
//			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
//			try{
//				socket.send(sendPkt);
//			}
//			catch(IOException e) {
//				System.err.println("I/O error while sending datagram:\n" + e);
//				socket.close(); System.exit(-1);
//			}
		}
		System.out.println("Bye bye, Client! ;-)");




	}

}