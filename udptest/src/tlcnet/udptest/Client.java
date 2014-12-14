package tlcnet.udptest;

import java.io.*;
import java.net.*;
import java.util.Arrays;

public class Client
{
	private static final int RX_BUFSIZE = 2048; // exceeding data will be discarded
	//private static final short TIMEOUT = 500; TODO
	private static final short DEF_DSTPORT = 9878;
	private static final short DEF_CHANPORT = 9879;
	
	
	public static void main(String args[])  
	{
		short channelPort = DEF_CHANPORT;
		short dstPort = DEF_DSTPORT;
		InetAddress channelAddr;
		InetAddress dstAddr;
		DatagramSocket socket = null;
		String sndStr = null;
		
		if (args.length != 3) {
		    System.out.println("Usage: java Client <dest address> <channel address> <local file>"); //unused
		    return;
		}
		
		// ---- Create stdin reader and socket ----
		BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));
		try {
			socket = new DatagramSocket(); // any available local port
		}
		catch (SocketException e) {
			System.err.println("Error creating a datagram socket:\n" + e);
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
		
		
		// *** MAIN LOOP ***
			
		int sn = 1;
		while(true) {
			
			// --- Read string from stdin ---
			try {
				sndStr = inFromUser.readLine();
			}
			catch(IOException e) {
				System.err.println("I/O error while reading input from user");
				socket.close();
				return;
			}
			
			// --- Assemble packet (UDP payload) ---
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.sn = sn;
			sendUTPpkt.dstAddr = dstAddr;
			sendUTPpkt.dstPort = dstPort;
			sendUTPpkt.payl = sndStr.getBytes();
			
			// --- Send UDP datagram ---
			byte[] sendData = sendUTPpkt.getRawData();
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);
			try {
				socket.send(sndPkt);
			}
			catch(IOException e) {
				System.err.println("I/O error while sending datagram");
				socket.close();
				return;
			}

			// ---- Receive packet ----
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try{
				socket.receive(recvPkt);
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				socket.close(); System.exit(-1);
			}

			
			// ---- Process received packet ----
			byte[] recvData = recvPkt.getData();				// payload of recv UDP datagram
			recvData = Arrays.copyOf(recvData, recvPkt.getLength());
			UTPpacket recvUTPpkt = new UTPpacket(recvData);		// parse UDP payload
			
			//DEBUG
			System.out.println("\n------\nRECV DATA:\n" + Utils.byteArr2str(recvData));
			
			System.out.println("Rcvd SN=" + recvUTPpkt.sn + "\nPayload (len=" + recvUTPpkt.payl.length
							+ "): " + new String(recvUTPpkt.payl) + "\n");
			
			// Increase counter
			sn++;
		}
	}
}