package tlcnet.udptest;

import java.io.*;
import java.net.*;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class Client
{
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	private static final short ACK_TIMEOUT = 2500;
	private static final int DEF_CHANNEL_PORT = 65432; // known by client and server
	static final int DEF_CLIENT_PORT = 65431;
	static final int PKT_SIZE = 128;
	static final int PKTS_IN_BLOCK = 4;
	static final int BLOCKS_IN_BUFFER = 5;

	private static final int channelPort = DEF_CHANNEL_PORT;
	private static final int dstPort = Server.DEF_SERVER_PORT;
	
	private static int sentDataPkt = 0;
	private static int numDataPkt = 0;
	private static int eobSn = 1;
	
	private static boolean[] pendingEobAck;
	private static DatagramPacket[] eob = new DatagramPacket[BLOCKS_IN_BUFFER]; //bleah

	// FIXME If the file size is a multiple of PKT_SIZE, a last extra packet with length 0 must be sent.

	
	
	public static void main(String args[]) throws IOException
	{
		InetAddress channelAddr;
		InetAddress dstAddr;
		DatagramSocket socket = null;

		if (args.length != 3) {
			System.out.println("Usage: java Client <dest address> <channel address> <local file>"); 
			return;
		}

		// ---- Create socket ----
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

		RandomAccessFile theFile = null;
		try	{
			String fileName = args[2];
			theFile = new RandomAccessFile(fileName, "r");	//creating a file reader
		} catch(FileNotFoundException e)	{
			System.err.println("Error: file not found");
			socket.close();
			return;
		}
		FileChannel inChannel = theFile.getChannel();










		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//


		// Start stopwatch
		long startTransferTime = System.currentTimeMillis();
		
		
		boolean finish = false;
		
		
		int bn = 1;				// Current block number
		int totBytesSent = 0;	// Counter for total bytes sent
//		int[] bnWnd = new int[]{1, 1};
		final int BUFFER_SIZE = PKT_SIZE * PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
		final int PKTS_IN_BUFFER = PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
		
		int[] bnInBuffer = new int[BLOCKS_IN_BUFFER];
		for (int i = 0; i < BLOCKS_IN_BUFFER; i++)
			bnInBuffer[i] = i+1;
		int lastBn = BLOCKS_IN_BUFFER;
		
		//
		boolean[] toBeSent = new boolean[PKTS_IN_BUFFER];
		
		pendingEobAck = new boolean[BLOCKS_IN_BUFFER];
		boolean[] isBlockAcked = new boolean[BLOCKS_IN_BUFFER];
		

		// This is used to read from file enough data to fill a block
		ByteBuffer chunkContainer = ByteBuffer.allocate(BUFFER_SIZE);
		
		boolean theEnd = false;

		while(!theEnd) {
			if (inChannel.read(chunkContainer) <= 0)
				break;

			// Now that the buffer is full, flip it in order to perform read operations on it
			chunkContainer.flip();

			int bufferedBytes = chunkContainer.remaining();
			
			// Update total byte counter
			totBytesSent += bufferedBytes; //not the right place...
			
			// Compute number of packets in the current block
			int bufferedPkts = Math.min(PKTS_IN_BUFFER, bufferedBytes / PKT_SIZE + 1);

			// Transmission buffer: its size is the size of the block in bytes (we'll have zero padding at the end of the file transfer)
			byte[] txBuffer = new byte[BUFFER_SIZE];	

			//
			chunkContainer.get(txBuffer, 0, bufferedBytes);

			// Flip again the buffer, to prepare it for the write operation (inChannel.read)
			chunkContainer.flip();

			Arrays.fill(toBeSent, 0, bufferedPkts, true); //FIXME
			

			// 
			try {
				sendBlocksAndEobs(txBuffer, toBeSent, bnInBuffer, bufferedBytes, socket, channelAddr, dstAddr);
				// toBeSent is now all-false
			} catch (IOException e) {
				System.err.println("I/O error while sending data");
				socket.close(); theFile.close(); System.exit(-1);
			}


			long timerStart = System.currentTimeMillis();
			long timeout = 5000; //FIXME
			while(!theEnd) {
				
				if (System.currentTimeMillis() > timerStart + timeout) { //timeout since last sent EOB expired
					theEnd = true;
					for (int j = 0; j < BLOCKS_IN_BUFFER; j++)
						if (pendingEobAck[j]) {
							Utils.logg("timeout: resending EOB " + (j+1));
							sendDatagram(socket, eob[j]);
//							sendDatagram(socket, eob[j]);
//							sendDatagram(socket, eob[j]);
//							sendDatagram(socket, eob[j]);
							timerStart = System.currentTimeMillis();
							theEnd = false;
						}
					if (theEnd)
						break;
				}

				// ---- Receive packet ----
				byte[] recvBuf = new byte[RX_BUFSIZE];
				DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);

				try {
					socket.setSoTimeout(1);
					socket.receive(recvPkt);
				} catch (SocketTimeoutException e) {

					continue;
				} catch(SocketException e) {
					System.err.println("Error while setting socket timeout");
					socket.close(); System.exit(-1);
				} catch(IOException e) {
					System.err.println("I/O error while receiving datagram:\n" + e);
					socket.close(); System.exit(-1);
				}


				// ---- Process received packet ----

				byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // Payload of recv UDP datagram
				UTPpacket recvEobAck = new UTPpacket(recvData);		// Parse UDP payload

				if (recvEobAck.function == UTPpacket.FUNCT_FIN) {
					theEnd = true;
					break;
				}
				
				// It's not EOB_ACK -> listen again
				if (recvEobAck.function != UTPpacket.FUNCT_EOB_ACK)
					continue;


				// It's an EOB_ACK
				Utils.logg(recvEobAck.endOfBlockAck.numberOfMissingSN + " pkts\t missing from BN=" + recvEobAck.endOfBlockAck.bn);
				timerStart = System.currentTimeMillis();
				int numMissingPkts = recvEobAck.endOfBlockAck.numberOfMissingSN;
				int ackedBn = recvEobAck.endOfBlockAck.bn;
				int bnIndexInBuffer = Arrays.binarySearch(bnInBuffer, ackedBn);
				int[] missingPkts = recvEobAck.endOfBlockAck.missingSN;
				int snOffset = (ackedBn - 1) * PKTS_IN_BLOCK + 1;		// sn offset for the BN that was just ACKed		
				int blockOffset = bnIndexInBuffer * PKTS_IN_BLOCK;
				pendingEobAck[bnIndexInBuffer] = false;
				if (numMissingPkts == 0)
					isBlockAcked[bnIndexInBuffer] = true;
				for (int j = 0; j < numMissingPkts; j++) {
					int pktInd = missingPkts[j] - snOffset + blockOffset;
					toBeSent[pktInd] = true;
				}
				
//				for (int i=0; i<eob.length; i++)
//					Utils.logg(pendingEobAck[i] + "\t" + eob[i]);
				sendBlocksAndEobs(txBuffer, toBeSent, bnInBuffer, bufferedBytes, socket, channelAddr, dstAddr);

				for (int i=0; i<eob.length; i++)
					Utils.logg(pendingEobAck[i] + "\t" + eob[i]);
				
				if(isBlockAcked[0]) { // block at index zero is fine: shift
					Utils.logg("shift");
					
				}

				/*if(! pendingEobAck[0]) { // block at index zero is fine: shift
					for (int i = 0; i < pendingEobAck.length - 1; i++)
						pendingEobAck[i] = pendingEobAck[i+1]; // FIXME più bello? metodo già pronto?
					pendingEobAck[pendingEobAck.length] = false;


					if (inChannel.read(chunkContainer) <= 0)
						break;

					chunkContainer.flip();
					int bufferedBytes1 = chunkContainer.remaining();
					totBytesSent += bufferedBytes1; //not the right place...


					System.arraycopy(txBuffer, PKTS_IN_BLOCK * PKT_SIZE, txBuffer, 0, txBuffer.length - PKTS_IN_BLOCK*PKT_SIZE);
					int size = Math.min(PKTS_IN_BLOCK * PKT_SIZE, bufferedBytes1);
					chunkContainer.get(txBuffer, txBuffer.length - PKTS_IN_BLOCK*PKT_SIZE, size);

					// Flip again the buffer, to prepare it for the write operation (inChannel.read)
					chunkContainer.flip();




					for (int i = 0; i < bnInBuffer.length - 1; i++)
						bnInBuffer[i] = bnInBuffer[i+1]; // FIXME più bello? metodo già pronto?
					lastBn++;
					bnInBuffer[bnInBuffer.length] = lastBn;


					System.arraycopy(toBeSent, PKTS_IN_BLOCK * PKT_SIZE, toBeSent, 0, toBeSent.length - PKTS_IN_BLOCK*PKT_SIZE);
					Arrays.fill(toBeSent, toBeSent.length - PKTS_IN_BLOCK*PKT_SIZE, toBeSent.length, true);



					eob = sendBlocksAndEobs(txBuffer, toBeSent, bnInBuffer, bufferedBytes, socket, channelAddr, dstAddr);
				}*/
				


			}



			
		}
		
		double elapsedTime = (double) (System.currentTimeMillis() - startTransferTime)/1000;
		double transferRate = totBytesSent / 1024 / elapsedTime;
		System.out.println("File transfer complete! :(");
		System.out.println(totBytesSent + " bytes sent");
		System.out.println("The file was split in " + numDataPkt + " packets, while " + sentDataPkt + " packets were actually sent");
		System.out.println("Elapsed time: " + elapsedTime + " s");
		System.out.println("Transfer rate: " + new DecimalFormat("#0.00").format(transferRate) + " KB/s");
	}










	/**
	 * @param channelAddr
	 * @param dstAddr
	 * @param bn
	 * @param numPacketsInThisBlock
	 * @return
	 */
	private static DatagramPacket assembleEobDatagram(InetAddress channelAddr,
			InetAddress dstAddr, int bn, int numPacketsInThisBlock) {
		
		// -- Initialize variables for ENDOFBLOCK packet --

		UTPpacket eobUtpPkt = new UTPpacket();
		eobUtpPkt.sn = eobSn++;
		eobUtpPkt.dstAddr = dstAddr;
		eobUtpPkt.dstPort = (short)dstPort;
		eobUtpPkt.function = UTPpacket.FUNCT_EOB;
		eobUtpPkt.setEndOfBlock(bn, numPacketsInThisBlock);
		byte[] eobData = eobUtpPkt.getRawData();
		DatagramPacket eobDatagram = new DatagramPacket(eobData, eobData.length, channelAddr, channelPort);
		return eobDatagram;
	}

	
	
	
	
	
	
	


	/**
	 * @param txBuffer
	 * @param toBeSent
	 * @param bnInBuffer - Block Numbers of the blocks we are trying to send.
	 * @param bufferedBytes - the actual number of bytes in the buffer. It is generally fixed, but it can be
	 * any number between 0 and BUFFER_SIZE if the last block is also the last block of the file transfer operation.
	 * @param socket - the socket on which send and receive operations are performed
	 * @param channelAddr - IP address of Channel
	 * @param dstAddr - IP address of the destination (Server)
	 * @throws IOException if an I/O error occurs in the socket while sending the datagram
	 */
	private static void sendBlocksAndEobs(byte[] txBuffer, boolean[] toBeSent, int[] bnInBuffer,int bufferedBytes,
			DatagramSocket socket, InetAddress channelAddr,	InetAddress dstAddr) throws IOException {
		
		final int BYTES_IN_BLOCK = PKTS_IN_BLOCK * PKT_SIZE;
		int numBlocks = bufferedBytes / BYTES_IN_BLOCK + 1;
		int bytesInLastBlock = bufferedBytes % BYTES_IN_BLOCK;
		if (numBlocks > BLOCKS_IN_BUFFER && bytesInLastBlock == 0) {
			numBlocks = BLOCKS_IN_BUFFER;
			bytesInLastBlock = BYTES_IN_BLOCK;
		}
		int lastBlockIndex = numBlocks - 1;
//		Utils.logg("num blocks = " + numBlocks);
//		Utils.logg(bytesInLastBlock);
		
		for (int i = 0; i < numBlocks; i++) {
			int bytesInThisBlock = BYTES_IN_BLOCK;
			if (i == lastBlockIndex)
				bytesInThisBlock = bytesInLastBlock;
			
			int numPktsInThisBlock = Math.min(PKTS_IN_BLOCK, bytesInThisBlock / PKT_SIZE + 1);
//			Utils.logg("pkts in block " + (i+1) + " = " + numPktsInThisBlock);
			byte[] txBuf_thisBlock = new byte[bytesInThisBlock];
			System.arraycopy(txBuffer, BYTES_IN_BLOCK * i, txBuf_thisBlock, 0, bytesInThisBlock);
			
			boolean[] toBeSent_thisBlock = new boolean[numPktsInThisBlock];
			System.arraycopy(toBeSent, PKTS_IN_BLOCK * i, toBeSent_thisBlock, 0, numPktsInThisBlock);
			
			sendSpecificDataPkts(txBuf_thisBlock, toBeSent_thisBlock, bnInBuffer[i], socket, channelAddr, dstAddr);

			if (Utils.count(toBeSent_thisBlock, true) == 0)
				continue; // don't send EOB
			
			DatagramPacket eobPkt = assembleEobDatagram(channelAddr, dstAddr, bnInBuffer[i], numPktsInThisBlock);
			eob[i] = eobPkt;
			
			try {
				Thread.sleep(800);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			sendDatagram(socket, eobPkt);
//			sendDatagram(socket, eobPkt);
//			sendDatagram(socket, eobPkt);
//			sendDatagram(socket, eobPkt);
			pendingEobAck[i] = true;
			
//			Utils.logg("METHOD -> " + pendingEobAck[i] + "\t" + eob[i]);

		}
		
		// Updates toBeSent array to specify that there are no pending packets to be sent
		Arrays.fill(toBeSent, 0, toBeSent.length, false);

	}


	
	
	
	
	/**
	 * @param txBuffer
	 * @param toBeSent
	 * @param bnInBuffer - Block Numbers of the blocks we are trying to send.
	 * @param bufferedBytes - the actual number of bytes in the buffer. It is generally fixed, but it can be
	 * any number between 0 and BUFFER_SIZE if there is the last block of the file transfer operation.
	 * @param socket - the socket on which send and receive operations are performed
	 * @param channelAddr - IP address of Channel
	 * @param dstAddr - IP address of the destination (Server)
	 * @throws IOException if an I/O error occurs in the socket while sending the datagram
	 */
	private static void sendSpecificDataPkts(byte[] txBuffer, boolean[] toBeSent, int bn, DatagramSocket socket, InetAddress channelAddr, InetAddress dstAddr) {

		if(Utils.count(toBeSent, true) == 0)
			return;
		
		// Offset for SN: the first packet of block bn has SN=snOffset
		int snOffset = 1 + PKTS_IN_BLOCK * (bn - 1);

		Utils.logg("- Sending " + Utils.count(toBeSent, true) + " packets from BN=" + bn);

		// Loop through packets in the current block, and send them away recklessly
		// Note that this loop may exceed the actual number of packets in this block, hence those dummy
		// packets must always be flagged as "not to be sent".
		for (int pktInd = 0; pktInd < PKTS_IN_BLOCK; pktInd++) {

			// If this packet was already received, there's no need to send it again
			if (! toBeSent[pktInd])
				continue;


			// --- Assemble packet (UDP payload) ---

			// index of the first and last+1 byte of the current packet in the tx buffer
			int currPktStart = pktInd * PKT_SIZE;
			int currPktEnd = Math.min (currPktStart + PKT_SIZE, txBuffer.length);
			
			//Utils.logg("SN = " + (snOffset + pktInd));
			//Utils.logg("currPktStart = " + currPktStart);
			//Utils.logg("currPktEnd = " + currPktEnd);

			// Payload of the current UDP packet. It's taken from the tx buffer.
			byte[] currPkt = Arrays.copyOfRange(txBuffer, currPktStart, currPktEnd);

			//Utils.logg(" == Pkt " + (snOffset + pktInd) + ": " + new String(currPkt).substring(0, 12));

			UTPpacket sendUTPpkt = new UTPpacket();
			sendUTPpkt.sn = snOffset + pktInd;
			sendUTPpkt.dstAddr = dstAddr;
			sendUTPpkt.dstPort = (short)dstPort;
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_DATA;
			sendUTPpkt.payl = currPkt;
			byte[] sendData = sendUTPpkt.getRawData();			
			DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);




			// --- Send UDP datagram ---

			//Utils.logg("Sending SN=" + sendUTPpkt.sn);
			//socket.send(sndPkt);
			sendDatagram(socket, sndPkt);
			sentDataPkt++;
		}


	}







	/**
	 * @param socket
	 * @param sndPkt
	 */
	private static void sendDatagram(DatagramSocket socket,
			DatagramPacket sndPkt) {
		try {
			socket.send(sndPkt);
		} catch(IOException e) {
			System.err.println("I/O error while sending EOB datagram");
			socket.close(); System.exit(-1);;
		}
	}
}