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
	static final int BLOCK_SIZE = 512;



	public static void main(String args[]) throws IOException
	{
		int channelPort = DEF_CHANNEL_PORT;
		int dstPort = Server.DEF_SERVER_PORT;
		InetAddress channelAddr;
		InetAddress dstAddr;
		DatagramSocket socket = null;

		if (args.length != 3) {
			System.out.println("Usage: java Client <dest address> <channel address> <local file>"); //unused
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
		ByteBuffer chunkContainer = ByteBuffer.allocate(BLOCK_SIZE);




		
		
		
		
		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//

		
		int sn = 1;

		while(inChannel.read(chunkContainer) > 0) {

			
			
			// --- Read chunk of file from buffer ---
			
			chunkContainer.flip();	//Important! Otherwise .remaining() method gives 0
			byte[] bytes = new byte[chunkContainer.remaining()];
			chunkContainer.get(bytes, 0, bytes.length);
			chunkContainer.clear();

			
			
			// --- Assemble packet (UDP payload) ---
			
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.sn = sn;
			sendUTPpkt.dstAddr = dstAddr;
			sendUTPpkt.dstPort = (short)dstPort;
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_DATA;
			sendUTPpkt.payl = bytes;	//Directly obtained from chunkContainer
			byte[] sendData = sendUTPpkt.getRawData();
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);




			// --- This is the loop of stop-and-wait ARQ for the current packet ---

			boolean acked = false;
			boolean mustTx = true;
			int timeout = ACK_TIMEOUT;
			while (!acked) {			

				// --- Send UDP datagram ---
				if (mustTx) {
					try {
						System.out.println("\n------\nSending SN=" + sn);
						socket.send(sndPkt);
					}
					catch(IOException e) {
						System.err.println("I/O error while sending datagram");
						socket.close(); theFile.close();
						System.exit(-1);
					}
				}


				// Must receive something before sending another pkt.
				// Set timeout for current SN. If other ACKs are received, ignore them,
				// update the timeout and finally wait again to receive the right ACK.

				
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
				System.out.println("\n------ RECEIVED\nHeader: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
				if (recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA)
					System.out.println("ACK " + recvUTPpkt.sn);
				else
					System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);

				if (recvUTPpkt.function != UTPpacket.FUNCT_ACKDATA)
					// **CASE 2**
					// This is not an ACK
					System.out.println("!Not an ACK"); // TODO: handle this properly

				else if (recvUTPpkt.sn != sn) {
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
			sn++;
		}
		inChannel.close();
		theFile.close();

		/* * * END OF DATA TRANSFER LOOP * * */
		
		
		
		
		
		
		
		
		

		// * * * * * * * * * * * * *//
		// * *  SEND FIN PACKET  * *//
		// * * * * * * * * * * * * *//

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
			if (recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA)
				System.out.println("ACK " + recvUTPpkt.sn);
			else if (recvUTPpkt.function == UTPpacket.FUNCT_ACKFIN)
				System.out.println("ACKFIN " + recvUTPpkt.sn);
			else
				System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);


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
				System.out.println("Yay. Transmission complete. Have fun while I stay here doing absolutely nothing. Merry Christmas :(");
			}
		}




	}
}