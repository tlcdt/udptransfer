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
	static final int PKT_SIZE = 512;
	static final int BLOCK_SIZE = 5;



	public static void main(String args[]) throws IOException
	{
		int channelPort = DEF_CHANNEL_PORT;
		int dstPort = Server.DEF_SERVER_PORT;
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
		}
		catch (SocketException e) {
			System.err.println("Error creating datagram socket:\n" + e);
			return;
		}



		try {
			dstAddr = InetAddress.getByName(args[0]);
			channelAddr = InetAddress.getByName(args[1]);
		}
		catch (UnknownHostException e) {
			System.err.println(e);
			socket.close();	return;
		}

		RandomAccessFile theFile = null;
		try	{
			String fileName = args[2];
			theFile = new RandomAccessFile(fileName, "r");	//creating a file reader
		}
		catch(FileNotFoundException e)	{
			System.out.println("Error: file not found");
			socket.close();
			return;
		}
		FileChannel inChannel = theFile.getChannel();
		
		




		
		
		
		
		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//

		
		int sn = 1;
		long startTransferTime = System.currentTimeMillis();
		int bn = 1; // block number
		
		// This is used to read from file enough data to fill a block
		ByteBuffer chunkContainer = ByteBuffer.allocate(PKT_SIZE * BLOCK_SIZE); // TODO: read even more for better performance?

		while(inChannel.read(chunkContainer) > 0) {
			// We just read from file enough data to fill a block. Now split it into packets.
			
			chunkContainer.flip();	//Important! Otherwise .remaining() method gives 0
			
			int numPacketsInThisBlock = chunkContainer.remaining() / PKT_SIZE;
			if (chunkContainer.remaining() != numPacketsInThisBlock * PKT_SIZE)
				numPacketsInThisBlock++;
			
			// Loop through packets in the current block, and send them away recklessly
			for (int pktInd = 1; pktInd < numPacketsInThisBlock; pktInd++) {
				
				
				// --- Read PKT_SIZE bytes from buffer ---

				int nextPktSize = Math.min(chunkContainer.remaining(), PKT_SIZE);
				byte[] bytes = new byte[nextPktSize];
				chunkContainer.get(bytes, 0, bytes.length);
				//TODO chunkContainer.clear();
				
				
				
				// --- Assemble packet (UDP payload) ---

				UTPpacket sendUTPpkt = new UTPpacket();
				sendUTPpkt.sn = sn;
				sendUTPpkt.dstAddr = dstAddr;
				sendUTPpkt.dstPort = (short)dstPort;
				sendUTPpkt.function = (byte) UTPpacket.FUNCT_DATA;
				sendUTPpkt.payl = bytes;	//Directly obtained from chunkContainer
				byte[] sendData = sendUTPpkt.getRawData();			
				DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);
				
				
				
				
				// --- Send UDP datagram ---

				try {
					System.out.println("\n------\nSending SN=" + sn);
					socket.send(sndPkt);
				}
				catch(IOException e) {
					System.err.println("I/O error while sending datagram");
					socket.close(); theFile.close(); System.exit(-1);
				}
				sn++;
			}
			
			
			// --- Block has been sent, now we must send ENDOFBLOCK to get a feedback

			

			
			
			



			
			
			
			
			
			
			
			
			
			// * * * * * * * * * * * * *//
			// * *  SEND ENDOFBLOCK  * *//
			// * * * * * * * * * * * * *//

			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.sn = UTPpacket.INVALID_SN;
			sendUTPpkt.dstAddr = dstAddr;
			sendUTPpkt.dstPort = (short)dstPort;
			sendUTPpkt.function = UTPpacket.FUNCT_EOB;
			sendUTPpkt.payl = Utils.int2bytes(numPacketsInThisBlock, 4); // TODO Use class UTPpacket to do this
			//TODO put block number in payload
			byte[] sendData = sendUTPpkt.getRawData();
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);

			

			boolean acked = false; // current eob has been acked
			boolean mustTx = true;
			int timeout = ACK_TIMEOUT;
			while (!acked) {			

				// --- Send UDP datagram (ENDOFBLOCK) ---
				if (mustTx) {
					try {
						System.out.println("\n------\nSending EOB");
						socket.send(sndPkt);
					}
					catch(IOException e) {
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
				socket.setSoTimeout(timeout);
				long timerStart = System.currentTimeMillis();
				try{
					socket.receive(recvPkt);
				}
				catch (SocketTimeoutException e) {

					// **CASE 1**
					// The EOB_ACK has timed out.
					System.out.println("\n!EOB_ACK missing\nRetransmitting");
					timeout = ACK_TIMEOUT; continue;   // Reset timeout and go to while(!acked).
				}
				catch(IOException e) {
					System.err.println("I/O error while receiving datagram:\n" + e);
					socket.close(); System.exit(-1);
				}

				// Packet received before timeout! Compute the "used up" timeout (only used if old ACK)
				int usedTimeout = (int) (System.currentTimeMillis() - timerStart);




				// ---- Process received packet ----
				
				byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // Payload of recv UDP datagram
				UTPpacket recvUTPpkt = new UTPpacket(recvData);		// Parse UDP payload

				//DEBUG
				System.out.println("\n------ RECEIVED\nHeader: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
//				if (recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA)
//					System.out.println("ACK " + recvUTPpkt.sn);
//				else
//					System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);

				if (recvUTPpkt.function != UTPpacket.FUNCT_EOB_ACK) {
					// **CASE 2**
					// This is not a FUNCT_ACK packet:
					//   - the timer keeps running because it's a timer for the FUNCT_ACK packet timeout;
					//   - no FUNCT packet retransmission.
					System.out.println("!Not an EOB_ACK");
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
					System.out.println("EOB_ACK with old BN (BN=" + recvUTPpkt.endOfBlockAck.bn + " < currBN=" + bn + "): ignore");
					timeout -= usedTimeout; // timeout>0 since the pkt was received before the timeout
					if (timeout < 1)  // In practice there are some errors: timeout can be slightly negative so we
						timeout = 1;  // force it to be positive (not zero, otherwise receive() would be blocking)
					mustTx = false;
				}

				else {
					// **CASE 4**
					// Current BN was ACKed: default timeout for next tx-rx loop is restored
					// at the beginning of the loop.
					acked = true;
				}


			}
			// EOB ACK has been received. Let's see what packets are missing and retrasmit them. (TODO)
			
			
			
			
			
			
			
			
			
			
			
			
			
			
			
			
			
			
			
			/* * ** *  * * * * ** NOW WHAT? * * * * ** *  */
			
			

/*			//boolean acked = false;
			//int timeout = ACK_TIMEOUT;
			while (!acked) {			

				


				// ---- Receive packet ----
				
				byte[] recvBuf = new byte[RX_BUFSIZE];
				DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
				socket.setSoTimeout(timeout);
				long timerStart = System.currentTimeMillis();
				try{
					socket.receive(recvPkt);
				}
				catch (SocketTimeoutException e) {

					// **CASE 1**
					// The ACK for the current SN has timed out.
					System.out.println("\n!ACK missing for SN=" + sn + "\nRetransmitting");
					timeout = ACK_TIMEOUT; continue;   // Reset timeout and go to while(!acked).
					// We will now tx with the same SN
				}
				catch(IOException e) {
					System.err.println("I/O error while receiving datagram:\n" + e);
					socket.close(); System.exit(-1);
				}

				// Packet received before timeout! Compute the "used up" timeout (only used if old ACK)
				int usedTimeout = (int) (System.currentTimeMillis() - timerStart);



				

				// ---- Process received packet ----
				
				byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // Payload of recv UDP datagram
				UTPpacket recvUTPpkt = new UTPpacket(recvData);		// Parse UDP payload

				//DEBUG
//				System.out.println("\n------ RECEIVED\nHeader: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
//				if (recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA)
//					System.out.println("ACK " + recvUTPpkt.sn);
//				else
//					System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);

				if (recvUTPpkt.sn != sn) {
					// **CASE 3**
					// This is an _old_ ACK:
					//   - the timer keeps running because it's a timer for the current SN;
					//   - no retransmission.
					System.out.println("ACK with old SN (SN=" + recvUTPpkt.sn + " < currSN=" + sn + "): ignore");
					timeout -= usedTimeout; // timeout>0 since the pkt was received before the timeout
					if (timeout < 1)  // In practice there are some errors: timeout can be slightly negative so we
						timeout = 1;  // force it to be positive (not zero, otherwise receive() would be blocking)
					mustTx = false;
				}

				else {
					// **CASE 4**
					// Current SN was ACKed: default timeout for next tx-rx loop is restored
					// at the beginning of the loop.
					acked = true;
				}
			}


			// Increase counter only when current SN was ACKed
			bn++;
		}
		inChannel.close();
		theFile.close();

		 * * END OF DATA TRANSFER LOOP * * 
		double transmissionSec = (double) (System.currentTimeMillis() - startTransferTime)/1000;
		
		
		
		
		
		
		
		

		// * * * * * * * * * * * * * //
		// * *  SEND FIN PACKET  * * //
		// * * * * * * * * * * * * * //

		UTPpacket sendUTPpkt = new UTPpacket();
		sendUTPpkt.sn = sn;
		sendUTPpkt.dstAddr = dstAddr;
		sendUTPpkt.dstPort = (short)dstPort;
		sendUTPpkt.payl = new byte[0];	
		sendUTPpkt.function = UTPpacket.FUNCT_FIN;
		byte[] sendData = sendUTPpkt.getRawData();
		DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);


		boolean acked = false;
		boolean mustTx = true;
		int timeout = ACK_TIMEOUT;
		while (!acked) {			

			// --- Send UDP datagram (FIN) ---
			if (mustTx) {
				try {
					System.out.println("\n------\nSending FIN");
					socket.send(sndPkt);
				}
				catch(IOException e) {
					System.err.println("I/O error while sending FIN datagram");
					socket.close(); System.exit(-1);;
				}
			}


			// Must receive something before sending another pkt.
			// Set timeout for current SN. If other ACKs are received, ignore them and 
			// wait again to receive after updating the timeout.

			// ---- Receive packet ----
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			socket.setSoTimeout(timeout);
			long timerStart = System.currentTimeMillis();
			try{
				socket.receive(recvPkt);
			}
			catch (SocketTimeoutException e) {

				// **CASE 1**
				// The FINACK has timed out.
				System.out.println("\n!FINACK missing\nRetransmitting");
				timeout = ACK_TIMEOUT; continue;   // Reset timeout and go to while(!acked).
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}

			// Packet received before timeout! Compute the "used up" timeout (only used if old ACK)
			int usedTimeout = (int) (System.currentTimeMillis() - timerStart);




			// ---- Process received packet ----
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // Payload of recv UDP datagram
			UTPpacket recvUTPpkt = new UTPpacket(recvData);		// Parse UDP payload

			//DEBUG
			System.out.println("\n------ RECEIVED\nHeader: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
//			if (recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA)
//				System.out.println("ACK " + recvUTPpkt.sn);
//			else if (recvUTPpkt.function == UTPpacket.FUNCT_ACKFIN)
//				System.out.println("ACKFIN " + recvUTPpkt.sn);
//			else
//				System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);


			if (recvUTPpkt.function != UTPpacket.FUNCT_ACKFIN) {
				// **CASE 2**
				// This is not a FINACK:
				//   - the timer keeps running because it's a timer for the FINACK timeout;
				//   - no FIN retransmission.
				System.out.println("!Not a FINACK");
				timeout -= usedTimeout; // timeout>0 since the pkt was received before the timeout
				if (timeout < 1)  // In practice there are some errors: timeout can be slightly negative so we
					timeout = 1;  // force it to be positive (not zero, otherwise receive() would be blocking)
				mustTx = false;
			}

			else {
				// **CASE 3**
				// Received a FINACK: done.
				acked = true;
				System.out.println("Yay. Transmission complete: it took " + transmissionSec + " s. Merry Christmas :(");
			}
			*/
		}




	}
}