package tlcnet.udptest;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

// TODO: What if the Server starts listening after the Client has started transmitting?
// The first received pkt has SN > 1.
// Should we consider this an error and abort?

public class Server {
	static final int DEF_CLIENT_PORT = 65431;
	static final int DEF_CHANNEL_PORT = 65432;
	static final int DEF_SERVER_PORT = 65433;
	private static final short END_TIMEOUT = 20000;		//To stop waiting for pcks...ugly!
	private static final int RX_BUFSIZE = 2048; // exceeding data will be discarded

	public static void main(String[] args) {
		int listenPort = DEF_SERVER_PORT;
		int channelPort = DEF_CHANNEL_PORT;
		InetAddress clientAddr = null;
		
		if (args.length != 2) {
		    System.out.println("Usage: java Client <client address> <path to new file>\nExample: Client /home/user/workspace/filename"); //unused
		    return;
		}
		try {
			clientAddr = InetAddress.getByName(args[0]);
		} catch (UnknownHostException e) {
			System.err.println(e); return;
		}
		
		// Create the socket
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(listenPort);
			socket.setSoTimeout(END_TIMEOUT);
		}
		catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			System.exit(-1);
		}
		
		//This is needed to copy the received file into a given directory
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		
		boolean mustStop = false;		//Needed to stop the cycle
		while(!mustStop)
		{
			// ---- Receive packet ----

			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try{
				socket.receive(recvPkt);
			}
			catch (SocketTimeoutException e) {
				mustStop = true;		
				System.out.println("Closing connection: FIN not received...");
				continue;
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}
			



			// ---- Process packet and prepare new packet ----

			byte[] recvData = recvPkt.getData();				// payload of recv UDP packet
			recvData = Arrays.copyOf(recvData, recvPkt.getLength());  // (truncate unused buffer)
			UTPpacket recvUTPpkt = new UTPpacket(recvData);		// parse payload
			InetAddress channelAddr = recvPkt.getAddress();			// get sender (=channel) address and port

			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.dstAddr = clientAddr;
			sendUTPpkt.dstPort = (short) DEF_CLIENT_PORT;
			sendUTPpkt.sn = recvUTPpkt.sn;
			sendUTPpkt.function = UTPpacket.FUNCT_ACKDATA;
			sendUTPpkt.payl = new byte[0];
			
			if (recvUTPpkt.function == Byte.valueOf(Integer.toString(UTPpacket.FUNCT_FIN)))	{
				sendUTPpkt.function = UTPpacket.FUNCT_ACKFIN;
				mustStop = true;
			}
			
			byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram




			//DEBUG
			System.out.println("\n------ RECEIVED\nHeader:\n" + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
			System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);
			if(mustStop)	{
				System.out.println("Oh, this is a FIN! I'll ack it right away!");
			}
			

			
			
			
			//Append current part of the file to output
			try	{
				outputStream.write(recvUTPpkt.payl);
			}
			catch(IOException e)	{
				System.out.println("Error while putting data back together");
			}
			
			

			// --- Send ACK ---
			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);  
			try{
				socket.send(sendPkt);
			}
			catch(IOException e) {
				System.err.println("I/O error while sending datagram:\n" + e);
				socket.close(); System.exit(-1);
			}
		}
		
		// --- Copy file to given directory ---
		System.out.println("Bye bye, Client! ;-)");
		byte[] finalData = outputStream.toByteArray();
		
		String newFile = args[1];
		try (FileOutputStream fos = new FileOutputStream(newFile)) {
		    fos.write(finalData);
		} catch (IOException ioe) {
		    ioe.printStackTrace();
		}

	}

}