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

public class ChannelThread {	
	private static final int CORE_POOL_SIZE = 50; // TODO: check this
	
	private static final int RX_BUFSIZE = 2048; // exceeding data will be discarded
	private static final int INVALID_PORT = -1;
	private int listenPort = INVALID_PORT;
	
	public ChannelThread(int listenPort) {
		super();
		this.listenPort = listenPort; // local port to listen on
		run();
	}
	
	private void run() {

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

		
		
		// * * * *  MAIN LOOP  * * * *
		
		while(true) {
			
			ScheduledThreadPoolExecutor schedExec = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE);
			
			
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
			byte[] recvData = recvPkt.getData();				// payload of recv UDP datagram
			recvData = Arrays.copyOf(recvData, recvPkt.getLength());
			UTPpacket recvUTPpkt = new UTPpacket(recvData);		// parse UDP payload
			InetAddress dstAddr = recvUTPpkt.dstAddr;			// get intended dest address and port
			int dstPort = (int)recvUTPpkt.dstPort & 0xffff;
			InetAddress srcAddr = recvPkt.getAddress();			// get sender address and port (UDP header)
			int srcPort = recvPkt.getPort();
			
			UTPpacket sendUTPpkt = new UTPpacket();	// Create new UTP packet (it will be the payload of the datagram)
			sendUTPpkt.function = recvUTPpkt.function;
			sendUTPpkt.sn = recvUTPpkt.sn;
			sendUTPpkt.payl = recvUTPpkt.payl;
			 // - The following two lines are needed only to notify the TRUE sender (DS or DR) to the
			 //   receiver (DR or DS); for other implementations of DR they are harmless.
			 //   Anyway, our DR will not be compatible with a Channel implemented without these lines.
			 //	  TODO What should we do? 
			sendUTPpkt.dstAddr = srcAddr; 			// Destination address (and port) from the destination POV: now,
			sendUTPpkt.dstPort = (short)srcPort;	//  for the channel, it's the addr (and port) of the sender.
			 // - - - - - -
			byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram
			
			//DEBUG
			//System.out.println("\n------\nRECV DATA:\n" + Utils.byteArr2str(recvData));
			System.out.println("Rcvd SN=" + recvUTPpkt.sn + "\nPayload (len=" + recvUTPpkt.payl.length
							+ "): " + new String(recvUTPpkt.payl));

			
			
			// ---- Send packet ----
			
			if (mustDrop(sendUTPpkt.payl.length)) {
				System.out.println("Dropping packet SN=" + recvUTPpkt.sn + " towards " + dstAddr.getHostAddress());
				continue;
			}
			DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, dstAddr, dstPort);
			// Execute thread that sends packet after a random time
			long rndDelay = getRndDelay(sendData.length);
			System.out.println("Delay=" + rndDelay + " ms");
			schedExec.schedule(new SendDelayedPacket(outSocket, listenSocket, sendPkt), rndDelay, TimeUnit.MILLISECONDS);

		}
	}
	
	
	private class SendDelayedPacket implements Runnable
	{
		private DatagramSocket dstSock;
		private DatagramSocket srcSock;
		private DatagramPacket sendPkt;

		SendDelayedPacket(DatagramSocket dstSock, DatagramSocket srcSock, DatagramPacket sendPkt) {
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

	

	private boolean mustDrop(int length) {
		double discard_prob = 1 - Math.exp(-length/(double)1024); // TODO: too low for testing
		boolean discard = new Random().nextDouble() <= discard_prob;
		return discard;
	}

	

	private long getRndDelay(int length) {
		double mean = 1024/Math.log((double) length);
		double delay = -Math.log(new Random().nextDouble()) * mean;
		return Math.round(delay);
	}
	
	

}
