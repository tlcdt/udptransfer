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
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	
	// When the write buffer exceeds this number of bytes, it is written on the output file
	private static final int WRITEBUF_THRESH = 20 * 1024;

	

	

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


		// Create output stream to write received data. This is periodically emptied on the out file.
		ByteArrayOutputStream writeBuffer = new ByteArrayOutputStream();

		boolean gotFIN = false; //Needed to stop the cycle
		while(!gotFIN)
		{
			// ---- Receive packet ----

			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try{
				socket.receive(recvPkt);
			}
			catch (SocketTimeoutException e) {		
				System.out.println("Closing connection: FIN not received...");
				break;
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}




			// ---- Process packet and prepare new packet ----

			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());  // payload of recv UDP packet
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			InetAddress channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port

			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.dstAddr = clientAddr;
			sendUTPpkt.dstPort = (short) clientPort;
			sendUTPpkt.sn = recvUTPpkt.sn;
			sendUTPpkt.payl = new byte[0];
			switch (recvUTPpkt.function) {
			case UTPpacket.FUNCT_DATA:
				sendUTPpkt.function = UTPpacket.FUNCT_INVALID; //TODO fix
				break;
			case UTPpacket.FUNCT_FIN:
				sendUTPpkt.function = UTPpacket.FUNCT_ACKFIN;
				gotFIN = true;
				break;
			default:
				System.out.println("Wut?");
				System.exit(-1);
			}

			byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram




			//DEBUG
			System.out.println("\n------ RECEIVED\nHeader:\n" + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
			System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);
			if(gotFIN)
				System.out.println("Oh, this is a FIN! I'll ack it right away!");





			// Append received packet to the ByteArrayOutputStream
			// TODO: (for the Client as well) Maybe read/write asynchronously from/to file so as to optimize computational time for I/O operations?
			try	{
				writeBuffer.write(recvUTPpkt.payl);
			}
			catch(IOException e)	{
				System.out.println("Error while putting data back together");
			}


			// If the buffer is too large, write it on file (append) and empty it.
			if (writeBuffer.size() > WRITEBUF_THRESH) {
				writeBufferToFile(writeBuffer, fileOutputStream);
			}




			// --- Send ACK or FINACK ---
			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
			try{
				socket.send(sendPkt);
			}
			catch(IOException e) {
				System.err.println("I/O error while sending datagram:\n" + e);
				socket.close(); System.exit(-1);
			}
		}
		System.out.println("Bye bye, Client! ;-)");




		// Write the remaining data in the buffer to the file
		writeBufferToFile(writeBuffer, fileOutputStream);

	}


	
	private static void writeBufferToFile(ByteArrayOutputStream buffer,
			FileOutputStream fileOutputStream) {
		
		System.out.println("\n   - - Writing " + buffer.size() + " bytes to disk");
		try {
			fileOutputStream.write(buffer.toByteArray());
			buffer.reset();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

}