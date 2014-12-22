package tlcnet.udptest;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class Client
{
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	private static final short ACK_TIMEOUT = 2000;
	private static final int DEF_CHANNEL_PORT = 65432; // known by client and server
	static final int DEF_CLIENT_PORT = 65431;
	static final int PKT_SIZE = 32;
	static final int BLOCK_SIZE = 50;

	static int channelPort = DEF_CHANNEL_PORT;
	static int dstPort = Server.DEF_SERVER_PORT;
	
	// TODO If the file size is a multiple of PKT_SIZE, a last extra packet with length 0 must be sent.

	public static void main(String args[]) throws IOException
	{
		InetAddress channelAddr;
		InetAddress dstAddr;
		DatagramSocket socket = null;

		if (args.length != 3) {
			System.out.println("Usage: java Client <dest address> <channel address> <local file>"); 
			return;
		}

		// ---- Create socket ----
		try {
			socket = new DatagramSocket(DEF_CLIENT_PORT);
		} catch (SocketException e) {
			System.err.println("Error creating datagram socket:\n" + e);
			return;
		}



		try {
			dstAddr = InetAddress.getByName(args[0]);
			channelAddr = InetAddress.getByName(args[1]);
		} catch (UnknownHostException e) {
			System.err.println(e);
			socket.close();	return;
		}

		RandomAccessFile theFile = null;
		try	{
			String fileName = args[2];
			theFile = new RandomAccessFile(fileName, "r");	//creating a file reader
		} catch(FileNotFoundException e)	{
			System.err.println("Error: file not found");
			socket.close();
			return;
		}
		FileChannel inChannel = theFile.getChannel();










		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//


		// Start stopwatch
		long startTransferTime = System.currentTimeMillis();
		
		// Current block number
		int bn = 1;

		// This is used to read from file enough data to fill a block
		ByteBuffer chunkContainer = ByteBuffer.allocate(PKT_SIZE * BLOCK_SIZE); // TODO: read even more than one block for better performance?

		while(inChannel.read(chunkContainer) > 0) {
			// We just read from file enough data to fill a block. Now we're gonna split it into packets,
			// send them, send EOB message, wait for EOB ACK and retransmit until all packets of the
			// current block are correctly received.

			// Now that the buffer is full, flip it in order to perform read operations on it
			chunkContainer.flip();

			// Bytes of actual data in the current block
			int bytesInCurrBlock = chunkContainer.remaining();

			// Transmission buffer: its size is the size of the block in bytes (we'll have zero padding at the end of the file transfer)
			byte[] txBuffer = new byte[PKT_SIZE * BLOCK_SIZE];	

			// Store current block data in the transmission buffer
			chunkContainer.get(txBuffer, 0, bytesInCurrBlock);

			// Flip again the buffer, to prepare it for the write operation (inChannel.read)
			chunkContainer.flip();

			// Compute number of packets in the current block
			int numPacketsInThisBlock = bytesInCurrBlock / PKT_SIZE;
			if (bytesInCurrBlock != numPacketsInThisBlock * PKT_SIZE)
				numPacketsInThisBlock++;

			// Flag all packets as "to be sent"
			// Note that except for the last block, numPacketsInThisBlock == BLOCK_SIZE
			boolean[] toBeSent = new boolean[BLOCK_SIZE];
			Arrays.fill(toBeSent, 0, numPacketsInThisBlock, true);

			// Send necessary packets (those that have not yet been acknowledged)
			try {
				sendSpecificDataPkts(txBuffer, toBeSent, bn, bytesInCurrBlock, socket, channelAddr, dstAddr);
			} catch (IOException e) {
				System.err.println("I/O error while sending data");
				socket.close(); theFile.close(); System.exit(-1);
			}


			// --- The block has been sent, now we must send ENDOFBLOCK to get a feedback

			while(true) {
				
				// -- Initialize variables for ENDOFBLOCK packet --

				UTPpacket sendUTPpkt = new UTPpacket();
				sendUTPpkt.sn = UTPpacket.INVALID_SN;
				sendUTPpkt.dstAddr = dstAddr;
				sendUTPpkt.dstPort = (short)dstPort;
				sendUTPpkt.function = UTPpacket.FUNCT_EOB;
				sendUTPpkt.setEndOfBlock(bn, numPacketsInThisBlock);
				byte[] sendData = sendUTPpkt.getRawData();
				DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);


				// -- Send EOB and wait for the proper ACK --

				UTPpacket recvUTPpkt = endOfBlockExchange(socket, bn, sendPkt);
				// EOB ACK has been received. Let's see what packets are missing and retransmit them.
				Utils.logg("  Received EOB_ACK: " + recvUTPpkt.endOfBlockAck.numberOfMissingSN + " missing packets in block BN=" + bn);
				// TODO: send EOB after a certain time that depends on the observed delay. Otherwise some pkts are received correctly after the EOB and therefore are uselessly retransmitted
				
				// If everything has been received, go ahead to the next block
				if (recvUTPpkt.endOfBlockAck.numberOfMissingSN == 0) {
					bn++;
					Utils.logg("");
					break;
				}
				

				// -- Retransmission --

				// Flag packets to be retransmitted
				Arrays.fill(toBeSent, false);
				for (int i = 0; i < recvUTPpkt.endOfBlockAck.numberOfMissingSN; i++) {
					int indexOfMissingSN = (recvUTPpkt.endOfBlockAck.missingSN[i] - 1) % BLOCK_SIZE;
					//Utils.logg("missing" + recvUTPpkt.endOfBlockAck.missingSN[i]);
					toBeSent[indexOfMissingSN] = true;
					//Utils.logg("    ---      TOBESENT = " + (indexOfMissingSN + 1 + BLOCK_SIZE * (bn - 1)));
				}

				// Send those packets (they haven't been acknowledged yet)
				try {
					sendSpecificDataPkts(txBuffer, toBeSent, bn, bytesInCurrBlock, socket, channelAddr, dstAddr);
				} catch (IOException e) {
					System.err.println("I/O error while sending data");
					socket.close(); theFile.close(); System.exit(-1);
				}

			}			
		} // while not eof
		
		double elapsedTime = (double) (System.currentTimeMillis() - startTransferTime)/1000;
		System.out.println("File transfer complete! :(");
		System.out.println("Elapsed time: " + elapsedTime + " s");
	}

	
	
	
	
	
	
	


	/**
	 * @param txBuffer
	 * @param toBeSent
	 * @param bn - Block Number of the block we are trying to send.
	 * @param bytesInCurrBlock - the actual number of bytes in the current block. It is generally fixed, but it can be
	 * any number between 0 and BLOCK_SIZE * PKT_SIZE if this is the last block of the file transfer operation.
	 * @param socket - the socket on which send and receive operations are performed
	 * @param channelAddr - IP address of Channel
	 * @param dstAddr - IP address of the destination (Server)
	 * @throws IOException if an I/O error occurs in the socket while sending the datagram
	 */
	private static void sendSpecificDataPkts(byte[] txBuffer, boolean[] toBeSent, int bn,int bytesInCurrBlock,
			DatagramSocket socket, InetAddress channelAddr,	InetAddress dstAddr) throws IOException {
		
		// Offset for SN: the first packet of block bn has SN=snOffset
		int snOffset = 1 + BLOCK_SIZE * (bn - 1);

		Utils.logg("- Sending " + Utils.count(toBeSent, true) + " packets from BN=" + bn);
		
		// Loop through packets in the current block, and send them away recklessly
		// Note that this loop may exceed the actual number of packets in this block, hence those dummy
		// packets must always be flagged as "not to be sent".
		for (int pktInd = 0; pktInd < BLOCK_SIZE; pktInd++) {

			// If this packet was already received, there's no need to send it again
			if (! toBeSent[pktInd])
				continue;


			// --- Assemble packet (UDP payload) ---

			// index of the first and last+1 byte of the current packet in the tx buffer
			int currPktStart = pktInd * PKT_SIZE;
			int currPktEnd = Math.min (currPktStart + PKT_SIZE, bytesInCurrBlock);

			//Utils.logg("SN = " + (snOffset + pktInd));
			//Utils.logg("currPktStart = " + currPktStart);
			//Utils.logg("currPktEnd = " + currPktEnd);

			// Payload of the current UDP packet. It's taken from the tx buffer.
			byte[] currPkt = Arrays.copyOfRange(txBuffer, currPktStart, currPktEnd);
			
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.sn = snOffset + pktInd;
			sendUTPpkt.dstAddr = dstAddr;
			sendUTPpkt.dstPort = (short)dstPort;
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_DATA;
			sendUTPpkt.payl = currPkt;
			byte[] sendData = sendUTPpkt.getRawData();			
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);




			// --- Send UDP datagram ---

			//Utils.logg("Sending SN=" + sendUTPpkt.sn);
			socket.send(sndPkt);
		}
	}


	
	

	/**
	 * This method sends an EndOfBlock packet to the destination, in order to notify the delivery of a block.
	 * Then it waits to receive an EndOfBlockAck for the current Block Number, in a  stop-and-wait fashion.
	 * Other received packets are discarded.
	 * 
	 * @param socket the socket this class is communicating on. It will be used to transmit the EOB and receive the EOB_ACK.
	 * @param bn the current Block Number
	 * @param sndPkt the UDP packet that contains EOB information
	 * @return the received EndOfBlockAck packet 
	 */
	private static UTPpacket endOfBlockExchange(DatagramSocket socket, int bn,
			DatagramPacket sndPkt) {

		boolean ackedEob = false; // current eob has been acked
		boolean mustTx = true;
		UTPpacket recvUTPpkt = null;
		int timeout = ACK_TIMEOUT;
		while (!ackedEob) {			

			// --- Send UDP datagram (ENDOFBLOCK) ---
			if (mustTx) {
				try {
					Utils.logg("  Sending EOB " + bn);
					socket.send(sndPkt);
				} catch(IOException e) {
					System.err.println("I/O error while sending EOB datagram");
					socket.close(); System.exit(-1);;
				}
			}


			// Must receive something before sending another pkt.
			// Set timeout for current EOB. If other things are received, ignore them and 
			// wait again to receive after updating the timeout.

			// ---- Receive packet ----
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			long timerStart = System.currentTimeMillis();

			try {
				socket.setSoTimeout(timeout);
				socket.receive(recvPkt);
			} catch (SocketTimeoutException e) {

				// **CASE 1**
				// The EOB_ACK has timed out.
				Utils.logg("! EOB_ACK missing: retransmitting");
				timeout = ACK_TIMEOUT; continue;   // Reset timeout and go to while(!acked).
			} catch(SocketException e) {
				System.err.println("Error while setting socket timeout");
				socket.close(); System.exit(-1);
			} catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}

			// Packet received before timeout! Compute the "used up" timeout (only used if old ACK)
			int usedTimeout = (int) (System.currentTimeMillis() - timerStart);




			// ---- Process received packet ----

			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // Payload of recv UDP datagram
			recvUTPpkt = new UTPpacket(recvData);		// Parse UDP payload

			//DEBUG
			//Utils.logg("\n - Received\nHeader: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));

			if (recvUTPpkt.function != UTPpacket.FUNCT_EOB_ACK) {
				// **CASE 2**
				// This is not a EOB_ACK packet:
				//   - the timer keeps running because it's a timer for the FUNCT_ACK packet timeout;
				//   - no FUNCT packet retransmission.
				Utils.logg("! Not an EOB_ACK");
				timeout -= usedTimeout; // timeout>0 since the pkt was received before the timeout
				if (timeout < 1)  // In practice there are some errors: timeout can be slightly negative so we
					timeout = 1;  // force it to be positive (not zero, otherwise receive() would be blocking)
				mustTx = false;
			}

			else if (recvUTPpkt.endOfBlockAck.bn != bn) {
				// **CASE 3**
				// This is an _old_ EOB_ACK:
				//   - the timer keeps running because it's a timer for the current SN;
				//   - no retransmission.
				Utils.logg("EOB_ACK with wrong BN (BN=" + recvUTPpkt.endOfBlockAck.bn + ", currBN=" + bn + "): ignore");
				timeout -= usedTimeout; // timeout>0 since the pkt was received before the timeout
				if (timeout < 1)  // In practice there are some errors: timeout can be slightly negative so we
					timeout = 1;  // force it to be positive (not zero, otherwise receive() would be blocking)
				mustTx = false;
			}

			else {
				// **CASE 4**
				// Current EOB was ACKed
				ackedEob = true;
			}
		}
		return recvUTPpkt;
	}
}