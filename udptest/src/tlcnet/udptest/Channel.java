package tlcnet.udptest;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Channel {
	static final int DEF_CHANNEL_RCV_PORT = 65432;
	private static final int CORE_POOL_SIZE = 1000;
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP

	
	
	public static void main(String[] args) {
		
		int listenPort = DEF_CHANNEL_RCV_PORT;
		
		// --- Create listen and send sockets ---
		DatagramSocket listenSocket = null;
		DatagramSocket outSocket = null;
		try {
			listenSocket = new DatagramSocket(listenPort);
		} catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			System.exit(-1);
		}
		try {
			outSocket = new DatagramSocket(); // any available local port
		} catch (SocketException e) {
			System.err.println("Error creating a datagram socket:\n" + e);
			listenSocket.close(); System.exit(-1);
		}

		// Create object for async packet forwarding
		ScheduledThreadPoolExecutor schedExec = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE);
		
		// Create object for UDP parsing in order to get the destination port and address
		ChannelGenericPacketParser pktParser = new ChannelGenericPacketParser();

		
		
		// * * *  MAIN LOOP  * * *
		
		while(true) {
			
			// Receive packet
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try {
				listenSocket.receive(recvPkt);
			} catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				listenSocket.close(); outSocket.close(); System.exit(-1);
			}
			
			// Payload of received UDP datagram: this is also the payload of the outbound packet.
			byte[] udpPayload = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
			
			// Parse the payload and get destination address and port
			pktParser.setPayload(udpPayload);
			InetAddress dstAddr = pktParser.getDstAddr();
			int dstPort = pktParser.getDstPort();
			
			// The following is compatible only with our format.
			/*UTPpacket utpPkt = new UTPpacket(recvData);		// parse UDP payload
			InetAddress dstAddr = utpPkt.dstAddr;			// get intended dest address and port
			int dstPort = (int)utpPkt.dstPort & 0xffff;*/
			
			
			// Decide whether to drop the packet; if so, start listening again.
			if (mustDrop(udpPayload.length)) {
				//Utils.logg("Dropping packet SN=" + utpPkt.sn + " towards " + dstAddr.getHostAddress());
				continue;
			}
			
			// Create the UDP datagram to be sent
			DatagramPacket sendPkt = new DatagramPacket(udpPayload, udpPayload.length, dstAddr, dstPort);

			// Execute thread that sends packet after a random time
			long rndDelay = getRndDelay(udpPayload.length);
			schedExec.schedule(new AsyncRepeatedPacketSender(outSocket, sendPkt), rndDelay, TimeUnit.MILLISECONDS);
			
			// AsyncRepeatedPacketSender is a runnable class that is called by the ScheduledThreadPoolExecutor, after the delay that
			// was assigned to the current packet. In order to perform the task of sending the packet, this class needs to be passed
			// the packet itself, together with the output socket.
			
			
			//DEBUG
//			if (dstPort == Client.DEF_CLIENT_PORT)
//				Utils.logg("  <--  Header: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH + 12)));
//			else if (dstPort == Server.DEF_SERVER_PORT)
//				Utils.logg("  -->  Header: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH + 12)));
		}
	}


	

	
	/**
	 * Computes whether the channel should drop the current packet or forward it, based
	 * on the packet drop probability, which depends on the length of the UDP payload.
	 * 
	 * @param length - the length of the UTP packet (i.e. of the UDP payload)
	 * @return true if the current packet must be dropped, false if it must be forwarded to its destination
	 */
	private static boolean mustDrop(int length) {
		double discard_prob = 1 - Math.exp(-length/(double)1024);
		boolean discard = new Random().nextDouble() <= discard_prob;
		return discard;
	}

	

	
	/**
	 * Returns the random delay that the current packet must experience. The delay follows an exponential
	 * distribution and depends on the length of the UDP payload.
	 * 
	 * @param length - the length of the UTP packet (i.e. of the UDP payload)
	 * @return a random delay for the current packet 
	 */
	private static long getRndDelay(int length) {
		double mean = 1024/Math.log((double) length);
		double delay = -Math.log(new Random().nextDouble()) * mean;
		return Math.round(delay);
	}
}