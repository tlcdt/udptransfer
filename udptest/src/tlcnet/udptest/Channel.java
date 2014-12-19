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
	private static final int CORE_POOL_SIZE = 50; // TODO: check this
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP

	
	
	
	
	public static void main(String[] args) {
		
		int listenPort = DEF_CHANNEL_RCV_PORT;
		
		// --- Create listen and send sockets ---
		
		DatagramSocket listenSocket = null;
		DatagramSocket outSocket = null;
		try {
			listenSocket = new DatagramSocket(listenPort);
		}
		catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			System.exit(-1);
		}
		try {
			outSocket = new DatagramSocket(); // any available local port
		}
		catch (SocketException e) {
			System.err.println("Error creating a datagram socket:\n" + e);
			listenSocket.close(); System.exit(-1);
		}

		ScheduledThreadPoolExecutor schedExec = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE); //TODO Check if threads close. Should this be inside the loop?

		
		// * * * *  MAIN LOOP  * * * *
		
		while(true) {
			
			
			// ---- Receive packet ----
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
			try{
				listenSocket.receive(recvPkt);
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving datagram:\n" + e);
				listenSocket.close(); outSocket.close(); System.exit(-1);
			}
			
			
			// ---- Process received packet and prepare new packet ----
			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // payload of recv UDP datagram
			UTPpacket utpPkt = new UTPpacket(recvData);		// parse UDP payload
			InetAddress dstAddr = utpPkt.dstAddr;			// get intended dest address and port
			int dstPort = (int)utpPkt.dstPort & 0xffff;
			byte[] sendData = recvData; // useless but clear
			
			//DEBUG
			System.out.println("\n------ RECEIVED\nHeader:\n" + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
//			if (utpPkt.function == UTPpacket.FUNCT_ACKDATA)
//				System.out.println("ACK " + utpPkt.sn);
//			else
//				System.out.println("SN=" + utpPkt.sn + "\nPayload length = " + utpPkt.payl.length);

			
			
			// ---- Send packet ----
			
			if (mustDrop(utpPkt.payl.length)) {
				System.out.println("Dropping packet SN=" + utpPkt.sn + " towards " + dstAddr.getHostAddress());
				continue;
			}
			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, dstAddr, dstPort);
			// Execute thread that sends packet after a random time
			long rndDelay = getRndDelay(sendData.length);
			System.out.println("Delay=" + rndDelay + " ms");
			schedExec.schedule(new SendDelayedPacket(outSocket, listenSocket, sendPkt), rndDelay, TimeUnit.MILLISECONDS);

		}

		
	}

	
	private static class SendDelayedPacket implements Runnable
	{
		private DatagramSocket dstSock;
		private DatagramSocket srcSock;
		private DatagramPacket sendPkt;

		public SendDelayedPacket(DatagramSocket dstSock, DatagramSocket srcSock, DatagramPacket sendPkt) {
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

	

	private static boolean mustDrop(int length) {
		double discard_prob = 1 - Math.exp(-length/(double)1024);
		boolean discard = new Random().nextDouble() <= discard_prob;
		return discard;
	}

	

	private static long getRndDelay(int length) {
		double mean = 1024/Math.log((double) length);
		double delay = -Math.log(new Random().nextDouble()) * mean;
		return Math.round(delay);
	}
	
	

}
