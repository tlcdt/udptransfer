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

		ScheduledThreadPoolExecutor schedExec = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE); //TODO Check if threads close. Should this be inside the loop?

		
		// * * * *  MAIN LOOP  * * * *
		
		while(true) {
			
			
			// ---- Receive packet ----
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try {
				listenSocket.receive(recvPkt);
			} catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				listenSocket.close(); outSocket.close(); System.exit(-1);
			}
			
			
			// ---- Process received packet and prepare new packet ----
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // payload of recv UDP datagram
			UTPpacket utpPkt = new UTPpacket(recvData);		// parse UDP payload
			// FIXME: This is compatible only with our format. We should instead parse only the first 6 bytes of the payload.
			InetAddress dstAddr = utpPkt.dstAddr;			// get intended dest address and port
			int dstPort = (int)utpPkt.dstPort & 0xffff;
			byte[] sendData = recvData; // useless but clear
			
			
			// ---- Send packet ----
			
			if (mustDrop(utpPkt.payl.length)) {
//				Utils.logg("Dropping packet SN=" + utpPkt.sn + " towards " + dstAddr.getHostAddress());
				continue;
			}
			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, dstAddr, dstPort);
			
			
			//DEBUG
//			if (dstPort == Client.DEF_CLIENT_PORT)
//				Utils.logg("  <--  Header: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH + 12)));
//			else if (dstPort == Server.DEF_SERVER_PORT)
//				Utils.logg("  -->  Header: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH + 12)));
//			if (utpPkt.function == UTPpacket.FUNCT_DATA)
//				Utils.logg("SN=" + utpPkt.sn);
			
			
			
			// Execute thread that sends packet after a random time
			long rndDelay = getRndDelay(sendData.length);
//			Utils.logg("Delay=" + rndDelay + " ms");
			schedExec.schedule(new DelayedPacketSender(outSocket, listenSocket, sendPkt), rndDelay, TimeUnit.MILLISECONDS);

		}

		
	}


	
	/**
	 * This is a runnable class that is called by the ScheduledThreadPoolExecutor, after the delay
	 * that was decided for the current packet. In order to perform the task of sending the packet,
	 * this class needs to be passed the packet itself, together with the input and output socket
	 * (the input socket is only needed because it must be closed if an error occurs).
	 *
	 */
	private static class DelayedPacketSender implements Runnable
	{
		private DatagramSocket dstSock;
		private DatagramSocket srcSock;
		private DatagramPacket sendPkt;

		public DelayedPacketSender(DatagramSocket dstSock, DatagramSocket srcSock, DatagramPacket sendPkt) {
			super();
			this.dstSock = dstSock;
			this.srcSock = srcSock;
			this.sendPkt = sendPkt;
		}


		@Override
		public void run() {

			try {
				dstSock.send(sendPkt);
			}
			catch(IOException e) {
				System.err.println("I/O error while sending datagram:\n" + e);
				dstSock.close(); srcSock.close(); System.exit(-1);
			}
		}
	}

	

	
	/**
	 * Computes whether the channel should drop the current packet or forward it,
	 * based on the packet drop probability, which depends on the length of the UDP
	 * payload.
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
