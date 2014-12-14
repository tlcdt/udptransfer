package tlcnet.udptest;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;

public class ChannelThread {
	private static final int RX_BUFSIZE = 2048; // exceeding data will be discarded
	private static final int INVALID_PORT = -1;
	//private InetAddress currClientAddr = null;
	//private int currClientPort = INVALID_PORT;
	private int listenPort = INVALID_PORT;
	
	public ChannelThread(int listenPort) {
		super();
		this.listenPort = listenPort; // local port to listen on
		run();
	}
	
	private void run() {

		// --- Create client-side and server-side sockets ---
		
		DatagramSocket cliSocket = null;
		DatagramSocket srvSocket = null;
		try {
			cliSocket = new DatagramSocket(listenPort);
		}
		catch(SocketException e) {
			System.err.println("Error creating a socket bound to port " + listenPort);
			System.exit(-1);
		}
		try {
			srvSocket = new DatagramSocket(); // any available local port
		}
		catch (SocketException e) {
			System.err.println("Error creating a datagram socket:\n" + e);
			cliSocket.close(); System.exit(-1);
		}
		
		
		// ---- MAIN LOOP ----
		while(true) {
			forward(cliSocket, srvSocket);
			forward(srvSocket, cliSocket);
		}
	}

	
	private void forward(DatagramSocket srcSock, DatagramSocket dstSock) {
		
		// ---- Receive packet ----
		byte[] recvBuf = new byte[RX_BUFSIZE];
		DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
		try{
			srcSock.receive(recvPkt);
		}
		catch(IOException e) {
			System.err.println("I/O error while receiving datagram:\n" + e);
			srcSock.close(); dstSock.close(); System.exit(-1);
		}
		
		
		// ---- Process received packet and prepare new packet ----
		byte[] recvData = recvPkt.getData();				// payload of recv UDP datagram
		recvData = Arrays.copyOf(recvData, recvPkt.getLength());
		UTPpacket recvUTPpkt = new UTPpacket(recvData);		// parse UDP payload
		InetAddress dstAddr = recvUTPpkt.dstAddr;			// get intendend dest address and port
		int dstPort = (int)recvUTPpkt.dstPort & 0xffff;
		InetAddress srcAddr = recvPkt.getAddress();			// get sender address and port (UDP header)
		int srcPort = recvPkt.getPort();
		
		UTPpacket sendUTPpkt = new UTPpacket();	// Create new UTP packet (it will be the payload of the datagram)
		sendUTPpkt.dstAddr = srcAddr; 			// Destination address (and port) from the destination POV: now,
		sendUTPpkt.dstPort = (short)srcPort;	//  for the channel, it's the addr (and port) of the sender.
		sendUTPpkt.function = recvUTPpkt.function;
		sendUTPpkt.sn = recvUTPpkt.sn;
		sendUTPpkt.payl = recvUTPpkt.payl;
		byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram
		
		//DEBUG
		System.out.println("\n------\nRECV DATA:\n" + Utils.byteArr2str(recvData));
		
		System.out.println("Rcvd SN=" + recvUTPpkt.sn + "\nPayload (len=" + recvUTPpkt.payl.length
						+ "): " + new String(recvUTPpkt.payl) + "\n");

		
		
		// ---- Send packet ----
		DatagramPacket sendPkt = new DatagramPacket(sendData, sendData.length, dstAddr, dstPort);
		try {
			dstSock.send(sendPkt);
		}
		catch(IOException e) {
			System.err.println("I/O error while sending datagram:\n" + e);
			dstSock.close(); srcSock.close(); System.exit(-1);
		}
	}
	
	

}
