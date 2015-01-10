package tlcnet.udptest;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;


public class ChannelTesterServer {

	static final int DEF_CHANNEL_PORT = 65432;
	static final int DEF_SERVER_PORT = 65433;
	private static final int RX_PKT_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP

	private static int listenPort = DEF_SERVER_PORT;




	public static void main(String[] args) throws IOException {

		// --- Create the socket ---
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(listenPort);
		} catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			System.exit(-1);
		}
		
		// Input arguments check
		if (args.length != 1) {
			System.out.println("Usage: java ChannelTesterServer <num pkts>"); 
			return;
		}

		int numPkts = Integer.parseInt(args[0]);

		
		long[] rxTime = new long[numPkts];
		boolean loop = true;
		

		while(loop) {

			// Receive UDP datagram
			DatagramPacket recvPkt = receiveDatagram(socket);

			// Process packet
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // payload of received UDP packet
			UTPpacket recvUTPpkt = new UTPpacket(recvData);			// parse payload
			
			switch (recvUTPpkt.function) {
			case UTPpacket.FUNCT_DATA:
			{
				//Utils.logg("Received SN=" + recvUTPpkt.sn);
				rxTime[recvUTPpkt.sn] = System.currentTimeMillis();
				break;
			}
			
			case UTPpacket.FUNCT_FIN:
			{
				loop = false;
				break;
			}
			
			default:
				// Ignore any other type of packet
				System.out.println("Invalid packet received: not DATA");
			}
		}
		
		for(int i = 0; i < numPkts; i++)
			System.out.println(rxTime[i]);
	}





	/**
	 * Receives a datagram from the given socket and returns it. It stops execution of the process if
	 * socket.receive() reaches the timeout set for this socket, or if an I/O error occurs.
	 * 
	 * @param socket - the socket on which the datagram will be received
	 * @return the received datagram
	 */
	private static DatagramPacket receiveDatagram(DatagramSocket socket) {

		byte[] recvBuf = new byte[RX_PKT_BUFSIZE];
		DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
		try {
			socket.receive(recvPkt);
		} catch (SocketTimeoutException e) {		
			System.err.println("Connection timeout: exiting");
			System.exit(-1);
		} catch(IOException e) {
			System.err.println("I/O error while receiving datagram:\n" + e);
			socket.close(); System.exit(-1);
		}
		return recvPkt;
	}
}