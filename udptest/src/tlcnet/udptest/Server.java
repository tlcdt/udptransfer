package tlcnet.udptest;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class Server {
	static final int DEF_CHANNEL_PORT = 65432;
	private static final short END_TIMEOUT = 20000;		//To stop waiting for pcks...ugly!
	static final int DEF_SERVER_PORT = 65433;
	private static final int RX_BUFSIZE = 2048; // exceeding data will be discarded

	public static void main(String[] args) {
		
		if (args.length != 1) {
		    System.out.println("Usage: java Client <complete path to new file>. E.g.: /home/user/workspace/file.extention"); //unused
		    return;
		}
		// Create the socket
		int listenPort = DEF_SERVER_PORT;
		int channelPort = DEF_CHANNEL_PORT;
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
				mustStop = true;		//When there are probably no more packets (said: ugly)
				System.out.println("Closing connection: timeout expired. Check for your copy of the file.");
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
			sendUTPpkt.dstAddr = recvUTPpkt.dstAddr;
			sendUTPpkt.dstPort = recvUTPpkt.dstPort;
			sendUTPpkt.sn = recvUTPpkt.sn;
			sendUTPpkt.function = UTPpacket.FUNCT_ACKDATA;
			sendUTPpkt.payl = new String("").getBytes(); // TODO: ugly
			byte[] sendData = sendUTPpkt.getRawData(); 	// payload of outgoing UDP datagram




			//DEBUG
			System.out.println("\n------RECV DATA:\n" + Utils.byteArr2str(recvData));
			System.out.println("Rcvd SN=" + recvUTPpkt.sn + "\nPayload (len=" + recvUTPpkt.payl.length
					+ "): " + new String(recvUTPpkt.payl) + "\n");
			
			
			
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
		//Copy file to given directory
		byte[] finalData = outputStream.toByteArray();
		
		String newFile = args[0];
		try (FileOutputStream fos = new FileOutputStream(newFile)) {
		    fos.write(finalData);
		} catch (IOException ioe) {
		    ioe.printStackTrace();
		}

	}

}