package tlcnet.udptest;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class Client
{
	private static final int RX_BUFSIZE = 2048; // exceeding data will be discarded
	private static final short ACK_TIMEOUT = 2000;
	private static final int DEF_CHANNEL_PORT = 65432;
	private static final int DEF_CLIENT_PORT = 65431;
	static final int BLOCK_SIZE = 512;



	public static void main(String args[]) throws IOException
	{
		int channelPort = DEF_CHANNEL_PORT;
		int dstPort = Server.DEF_SERVER_PORT;
		InetAddress channelAddr;
		InetAddress dstAddr;
		DatagramSocket socket = null;

		if (args.length != 3) {
			System.out.println("Usage: java Client <dest address> <channel address> <local file>"); //unused
			return;
		}

		// ---- Create socket ----
		try {
			socket = new DatagramSocket(DEF_CLIENT_PORT);
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

		RandomAccessFile theFile = null;
		try	{
			String fileName = args[2];
			theFile = new RandomAccessFile(fileName, "r");	//creating a file reader
		}
		catch(FileNotFoundException e)	{
			System.out.println("Error: file not found");
			socket.close();
			return;
		}
		FileChannel inChannel = theFile.getChannel();
		ByteBuffer chunkContainer = ByteBuffer.allocate(BLOCK_SIZE);

		
		
		
		
		// *** MAIN LOOP ***

		int sn = 1;
		
		while(inChannel.read(chunkContainer) > 0) {

			// --- Read chunk of file from buffer ---
			chunkContainer.flip();	//Important! Otherwise .remaining() method gives 0
			byte[] bytes = new byte[chunkContainer.remaining()];
			chunkContainer.get(bytes, 0, bytes.length);
			chunkContainer.clear();

			// --- Assemble packet (UDP payload) ---
			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.sn = sn;
			sendUTPpkt.dstAddr = dstAddr;
			sendUTPpkt.dstPort = (short)dstPort;
			sendUTPpkt.payl = bytes;	//Directly obtained from chunkContainer
			byte[] sendData = sendUTPpkt.getRawData();
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);

			boolean acked = false;
			int timeout = ACK_TIMEOUT;
			while (!acked) {
				// --- Send UDP datagram ---
				try {
					System.out.println("Sending SN=" + sn);
					socket.send(sndPkt);
				}
				catch(IOException e) {
					System.err.println("I/O error while sending datagram");
					socket.close(); theFile.close();
					return;
				}

				// ---- Receive packet ----
				byte[] recvBuf = new byte[RX_BUFSIZE];
				socket.setSoTimeout(timeout);
				DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
				long timerStart = System.currentTimeMillis();
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
				int usedTimeout = (int) (System.currentTimeMillis() - timerStart);

				// ---- Process received packet ----
				byte[] recvData = recvPkt.getData();				// payload of recv UDP datagram
				recvData = Arrays.copyOf(recvData, recvPkt.getLength());
				UTPpacket recvUTPpkt = new UTPpacket(recvData);		// parse UDP payload
				if (recvUTPpkt.function != UTPpacket.FUNCT_ACKDATA)
					System.out.println("!Not an ACK");
				else if (recvUTPpkt.sn != sn) {
					System.out.println("!ACK for the wrong SN (SN=" + recvUTPpkt.sn + " < currSN=" + sn + "): ignore");
					timeout = timeout - usedTimeout;
				}
				else {
					acked = true;
					socket.setSoTimeout(ACK_TIMEOUT); // restore default timeout
				}

				//DEBUG
				System.out.println("\n------ RECEIVED\nHeader: " + Utils.byteArr2str(Arrays.copyOf(recvData, UTPpacket.HEADER_LENGTH)));
				System.out.println("SN=" + recvUTPpkt.sn + "\nPayload length = " + recvUTPpkt.payl.length);

			}


			// Increase counter
			sn++;
		}
		inChannel.close();
		theFile.close();


		// Send FIN packet

		UTPpacket sendUTPpkt = new UTPpacket();
		sendUTPpkt.sn = sn;
		sendUTPpkt.dstAddr = dstAddr;
		sendUTPpkt.dstPort = (short)dstPort;
		sendUTPpkt.payl = new byte[0];	
		sendUTPpkt.function = UTPpacket.FUNCT_FIN;
		byte[] sendData = sendUTPpkt.getRawData();
		DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);

		boolean acked = false;
		while (!acked) {
			// --- Wait for ack ---
			try {
				System.out.println("Sending FIN");
				socket.send(sndPkt);
			}
			catch(IOException e) {
				System.err.println("I/O error while sending FIN packet");
				socket.close();
				return;
			}

			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvFin = new DatagramPacket(recvBuf, recvBuf.length);
			try{
				socket.receive(recvFin);
			}
			catch (SocketTimeoutException e) {
				System.out.println("!ACK not received for FIN: \nRetransmitting");
				continue;
			}
			catch(IOException e) {
				System.err.println("I/O error while receiving FIN packet:\n" + e);
				socket.close(); System.exit(-1);
			}

			// ---- Process received packet ----
			byte[] recvData = recvFin.getData();				// payload of recv UDP datagram
			recvData = Arrays.copyOf(recvData, recvFin.getLength());
			UTPpacket recvUTPpktFin = new UTPpacket(recvData);		// parse UDP payload
			if (recvUTPpktFin.function != UTPpacket.FUNCT_ACKFIN)
				System.out.println("!Not FIN");
			else
				acked = true;
			System.out.println("Yay. Transmition complete. Have fun while I stay here doing absolutely nothing. Merry Christmas :(");
		}

	}
}