package tlcnet.udptest;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;




public class ChannelTesterClient
{
	private static final int DEF_CHANNEL_PORT = 65432; // known by client and server
	static final int DEF_CLIENT_PORT = 65431;
	static final int DEF_CLIENT_CTRL_PORT = 65430;

	private static final int DELAY = 2;
	private static final int NUM_OF_FINS = 30;

	private static final int channelPort = DEF_CHANNEL_PORT;
	private static final int dstPort = Server.DEF_SERVER_PORT;

	
	
	public static void main(String args[]) throws IOException
	{
		InetAddress channelAddr, dstAddr;
		DatagramSocket socket = null;
		

		// Input arguments check
		if (args.length != 4) {
			System.out.println("Usage: java ChannelTesterClient <dest address> <channel address> <pkt size> <num pkts>"); 
			return;
		}

		// Create sockets
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
		
		int pktSize = Integer.parseInt(args[2]);
		int numPkts = Integer.parseInt(args[3]);

		long[] txTime = new long[numPkts]; // actually useless
		
		byte[] pktData = new byte[pktSize]; 
		

		/* * Main loop * */

		for (int sn = 0; sn < numPkts; sn++) {

			// --- Assemble packet (UDP payload) ---
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.sn = sn;
			sendUTPpkt.dstAddr = dstAddr;
			sendUTPpkt.dstPort = (short)dstPort;
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_DATA;
			sendUTPpkt.payl = pktData;
			byte[] sendData = sendUTPpkt.getRawData();			
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);

			// --- Send UDP datagram ---
			//Utils.logg("Sending SN=" + sendUTPpkt.sn);
			sendDatagram(socket, sndPkt);
			
			txTime[sn] = System.currentTimeMillis();
			System.out.println(txTime[sn]);
			try {
				Thread.sleep(DELAY);
			} catch (InterruptedException e) {
			}			
		}
		
		for (int i = 0; i < NUM_OF_FINS; i++) {
			sendFin(channelAddr, dstAddr, socket);
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {}
		}
	}





	private static void sendFin(InetAddress channelAddr, InetAddress dstAddr, DatagramSocket socket) {
		// - - Send FIN - -
		UTPpacket sendUTPpkt = new UTPpacket();
		sendUTPpkt.dstAddr = dstAddr;
		sendUTPpkt.dstPort = (short)dstPort;
		sendUTPpkt.function = (byte) UTPpacket.FUNCT_FIN;
		sendUTPpkt.payl = new byte[0];
		byte[] sendData = sendUTPpkt.getRawData();			
		DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);

		// --- Send UDP datagram ---
		sendDatagram(socket, sndPkt);
	}





	/**
	 * Sends a DatagramPacket (UDP datagram) over the specified socket, and terminates the process if an error occurs.
	 * 
	 * @param socket - the socket on which the packet must be sent
	 * @param outPkt - the outgoing packet
	 */
	private static void sendDatagram(DatagramSocket socket, DatagramPacket outPkt) {
		try {
			socket.send(outPkt);
		} catch(IOException e) {
			System.err.println("I/O error while sending datagram");
			socket.close(); System.exit(-1);;
		}
	}	
}