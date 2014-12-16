package tlcnet.udptest;

import java.io.*;
import java.net.*;
import java.util.Arrays;

public class Client
{
	private static final int RX_BUFSIZE = 2048; // exceeding data will be discarded
	private static final short ACK_TIMEOUT = 2000;
	private static final int DEF_CHANNEL_PORT = 65432;
	private static final int DEF_CLIENT_PORT = 65431;
	 // The client port needs to be standardized even in a client/server architecture,
	 //  for compatibility with evil stuff such as NAT.
	
	
	
	public static void main(String args[])  
	{
		int channelPort = DEF_CHANNEL_PORT;
		int dstPort = Server.DEF_SERVER_PORT;
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
			socket = new DatagramSocket(DEF_CLIENT_PORT);
			socket.setSoTimeout(ACK_TIMEOUT);
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
			sendUTPpkt.dstPort = (short)dstPort;
			sendUTPpkt.payl = sndStr.getBytes();
			byte[] sendData = sendUTPpkt.getRawData();
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);

			boolean acked = false;
			while (!acked) {
				// --- Send UDP datagram ---
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
				catch (SocketTimeoutException e) {
					System.out.println("!ACK not received for SN=" + sn + "\nRetransmitting");
					continue;
				}
				catch(IOException e) {
					System.err.println("I/O error while receiving datagram:\n" + e);
					socket.close(); System.exit(-1);
				}
				
				// ---- Process received packet ----
				byte[] recvData = recvPkt.getData();				// payload of recv UDP datagram
				recvData = Arrays.copyOf(recvData, recvPkt.getLength());
				UTPpacket recvUTPpkt = new UTPpacket(recvData);		// parse UDP payload
				if (recvUTPpkt.function != UTPpacket.FUNCT_ACKDATA)
					System.out.println("!Not an ACK");
				else if (recvUTPpkt.sn != sn)
					System.out.println("!ACK for the wrong SN (SN=" + recvUTPpkt.sn + ")");
				else
					acked = true;

				//DEBUG
				System.out.println("\n------\nRECV DATA:\n" + Utils.byteArr2str(recvData));

				System.out.println("Rcvd SN=" + recvUTPpkt.sn + "\nPayload (len=" + recvUTPpkt.payl.length
						+ "): " + new String(recvUTPpkt.payl) + "\n");
			}

			
			
			
			// Increase counter
			sn++;
		}
	}
}