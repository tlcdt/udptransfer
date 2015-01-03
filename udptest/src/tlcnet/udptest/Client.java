package tlcnet.udptest;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;




public class Client
{
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	private static final short ACK_TIMEOUT = 4000;
	private static final int DEF_CHANNEL_PORT = 65432; // known by client and server
	static final int DEF_CLIENT_PORT = 65431;
	
	private static final int CORE_POOL_SIZE = 1000;
	private static final int EOB_PRE_SLEEP = 1;
	private static final int EOB_PRE_DELAY = 100; // TODO In localhost, with parameters {640, 50, 20}, 100 is the best. Below this, throughput doesn't change, but more packets are transmitted. The problem is that with different parameters this may not be the best choice!
	private static final int EOB_INTER_DELAY = 20;
	
	static final int PKT_SIZE = 640;
	static final int PKTS_IN_BLOCK = 1200;
	static final int BLOCKS_IN_BUFFER = 8;
	static final int BUFFER_SIZE = PKT_SIZE * PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
	static final int PKTS_IN_BUFFER = PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
	static final int BYTES_IN_BLOCK = PKTS_IN_BLOCK * PKT_SIZE;

	private static final int channelPort = DEF_CHANNEL_PORT;
	private static final int dstPort = Server.DEF_SERVER_PORT;
	
	private static int sentDataPkts = 0;
	private static int sentEobPkts = 0;
	private static int eobSn = 1;
	private static DuplicateIdHandler dupEobHandler = new DuplicateIdHandler();
	private static int[] numTransmissionsCache = new int[PKTS_IN_BLOCK];
	private static boolean[] pendingEobAck;
	private static DatagramPacket[] eobCache = new DatagramPacket[BLOCKS_IN_BUFFER]; //bleah
	private static int eob_preSleep = EOB_PRE_SLEEP;
	
	/*private static int spaceOutFactor = 1;
	private static final int SPACE_OUT_DELAY = 50;*/
	

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



		RandomAccessFile theFile = null;
		try {
			dstAddr = InetAddress.getByName(args[0]);
			channelAddr = InetAddress.getByName(args[1]);
			String fileName = args[2];
			theFile = new RandomAccessFile(fileName, "r");	//creating a file reader
		} catch (UnknownHostException e) {
			System.err.println(e);
			socket.close();	return;
		} catch(FileNotFoundException e)	{
			System.err.println("Error: file not found");
			socket.close();	return;
		}

		FileInputStream inStream = new FileInputStream(theFile.getFD());










		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//


		// Start stopwatch
		long startTransferTime = System.currentTimeMillis();

		int totBytesRead = 0;	// Counter
		
		int windowLeft = 1;
		//int windowRight = BLOCKS_IN_BUFFER;
		
		//
		boolean[] toBeSent = new boolean[PKTS_IN_BUFFER];
		
		pendingEobAck = new boolean[BLOCKS_IN_BUFFER];
		boolean[] isBlockAcked = new boolean[BLOCKS_IN_BUFFER];
		
		boolean theEnd = false;
		
		// Bytes of useful data in the tx buffer
		int bufferedBytes = 0;

		ScheduledThreadPoolExecutor schedExec = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE);

		// Transmission buffer: its size is the size of the block in bytes (we'll have zero padding at the end of the file transfer)
		byte[] txBuffer = new byte[BUFFER_SIZE];

		int bytesRead = inStream.read(txBuffer, 0, txBuffer.length);

		// Update total byte counter
		totBytesRead += bytesRead;
		bufferedBytes += bytesRead;

		// Compute number of packets in the buffer
		int bufferedPkts = Math.min(PKTS_IN_BUFFER, bufferedBytes / PKT_SIZE + 1);
		
		double expectedLossProb = (1 - Math.exp(-(PKT_SIZE + UTPpacket.HEADER_LENGTH)/(double)1024));
		double lossThresh = 0.4 * 1 + 0.6 * expectedLossProb;
		Utils.logg("Expected loss probability is " + Math.round(expectedLossProb * 100) + "%");
		Utils.logg("Loss threshold is " + Math.round(lossThresh * 100) + "%");

		Arrays.fill(toBeSent, 0, bufferedPkts, true);


		// 
		try {
			sendBlocksAndEobs(txBuffer, toBeSent, windowLeft, bufferedBytes, socket, channelAddr, dstAddr, schedExec);
			// toBeSent is now all-false
		} catch (IOException e) {
			System.err.println("I/O error while sending data");
			socket.close(); theFile.close(); inStream.close(); System.exit(-1);
		}


		long timerStart = System.currentTimeMillis();
		long timeout = ACK_TIMEOUT;
		while(!theEnd) {

			if (System.currentTimeMillis() > timerStart + timeout) { //timeout since last sent EOB expired
				int pendingEobAcks = 0;
				for (int j = 0; j < BLOCKS_IN_BUFFER; j++)
					if (pendingEobAck[j]) {
						Utils.logg("timeout: resending EOB " + bnInWindow(j, windowLeft));
						int times=3;
						for (int k = 0; k < times; k++)
							sendDatagram(socket, eobCache[j]); // TODO CHANGE THE SN
						sentEobPkts += times;
						timerStart = System.currentTimeMillis(); // TODO one timer for each block
						pendingEobAcks++;
					}
				if (pendingEobAcks == 0) { // is this block useful at the end of transmission? Isn't the FIN packet enough?
					System.out.println("Timeout: no FIN received, but all data has been ACKed. Exit!");
					theEnd = true;
					break;
				}
				if (pendingEobAcks > BLOCKS_IN_BUFFER / 2) {
					timeout *= 2;
				}
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
			
			timerStart = System.currentTimeMillis();
			//Utils.logg(recvEobAck.endOfBlockAck.numberOfMissingSN + " pkts\t missing from BN=" + recvEobAck.endOfBlockAck.bn);
			int numMissingPkts = recvEobAck.endOfBlockAck.numberOfMissingSN;
			int ackedBn = recvEobAck.endOfBlockAck.bn;
			if (numMissingPkts > PKTS_IN_BLOCK * lossThresh)
				eob_preSleep *= 2;
			
			// Index of this packet's block in the current window
			int bnIndexInWindow = ackedBn - windowLeft;
			if (bnIndexInWindow < 0 || bnIndexInWindow > BLOCKS_IN_BUFFER - 1) continue;

			if (!dupEobHandler.isNew(recvEobAck.sn)) continue;
			
			int[] missingPkts = recvEobAck.endOfBlockAck.missingSN;
			int snOffset = (ackedBn - 1) * PKTS_IN_BLOCK + 1;		// sn offset for the BN that was just ACKed		
			int blockOffset = bnIndexInWindow * PKTS_IN_BLOCK;
			pendingEobAck[bnIndexInWindow] = false;
			if (numMissingPkts == 0)
				isBlockAcked[bnIndexInWindow] = true;
			for (int j = 0; j < numMissingPkts; j++) {
				int pktInd = missingPkts[j] - snOffset + blockOffset;
				toBeSent[pktInd] = true;
			}

			// block at index zero is fine, and we still have data in the tx buffer: shift
			while(isBlockAcked[0] && bufferedBytes >= BYTES_IN_BLOCK) { 
				
				Utils.logg("BN " + windowLeft + " was received -> shifting...");

				// Shift tx buffer
				Utils.shiftArrayLeft(txBuffer, BYTES_IN_BLOCK);
				
				// Read data and write them into the tx buffer
				bytesRead = inStream.read(txBuffer, txBuffer.length - BYTES_IN_BLOCK, BYTES_IN_BLOCK);
				if (bytesRead == -1) // eof
					bytesRead = 0;
				
				// Counters and stuff
				totBytesRead += bytesRead;
				bufferedBytes -= BYTES_IN_BLOCK;
				bufferedBytes += bytesRead;
				int newPkts = Math.min(PKTS_IN_BLOCK, bytesRead / PKT_SIZE + 1);
				if(bytesRead > 0) Utils.logg("Read " + bytesRead + " more bytes from file");

				// Shift and update other entities
				Utils.shiftArrayLeft(pendingEobAck, 1);
				Utils.shiftArrayLeft(eobCache, 1);
				Utils.shiftArrayLeft(isBlockAcked, 1);
				windowLeft++;
				//windowRight++;
				Utils.shiftArrayLeft(toBeSent, PKTS_IN_BLOCK);
				Arrays.fill(toBeSent, toBeSent.length - PKTS_IN_BLOCK, toBeSent.length - PKTS_IN_BLOCK + newPkts, true);				
			}

			sendBlocksAndEobs(txBuffer, toBeSent, windowLeft, bufferedBytes, socket, channelAddr, dstAddr, schedExec);
		}

		int numDataPkts = totBytesRead / PKT_SIZE + 1;
		double elapsedTime = (double) (System.currentTimeMillis() - startTransferTime)/1000;
		double transferRate = totBytesRead / 1024 / elapsedTime;
		System.out.println("File transfer complete! :(");
		System.out.println(totBytesRead + " bytes read");
		System.out.println(numDataPkts + " data packets to be sent, while " + sentDataPkts + " data packets were actually sent");
		System.out.println(sentEobPkts + " EOB packets were sent");
		System.out.println((sentDataPkts + sentEobPkts) + " total packets sent to channel");
		System.out.println("Elapsed time: " + elapsedTime + " s");
		System.out.println("Transfer rate: " + new DecimalFormat("#0.00").format(transferRate) + " KB/s");
		
		Utils.logg("Now eob_preSleep is " + eob_preSleep + " ms");
		Utils.logg("duplicate EOB handler misses = " + dupEobHandler.misses());
		schedExec.shutdownNow();
	}





	/**
	 * Returns the Block Number of the block that is at the specified index in the current window.
	 * Returns 0 if the index is invalid.
	 * 
	 * @param index
	 * @param windowLeft
	 * @return - the Block Number of the block that is at the specified index in the current window.<br>
	 * - the value 0 if the index is invalid.
	 */
	private static int bnInWindow(int index, int windowLeft) {
		if (index < 0 || index > BLOCKS_IN_BUFFER - 1)
			return 0;
		return index + windowLeft;
	}




	/**
	 * @param channelAddr
	 * @param dstAddr
	 * @param bn
	 * @param numPacketsInThisBlock
	 * @return
	 */
	private static DatagramPacket assembleEobDatagram(InetAddress channelAddr, InetAddress dstAddr, int bn, int numPacketsInThisBlock, boolean useSequenceNumber) {

		UTPpacket eobUtpPkt = new UTPpacket();
		if (useSequenceNumber)
			eobUtpPkt.sn = eobSn++;
		else
			eobUtpPkt.sn = DuplicateIdHandler.JOLLY;
		eobUtpPkt.dstAddr = dstAddr;
		eobUtpPkt.dstPort = (short)dstPort;
		eobUtpPkt.function = UTPpacket.FUNCT_EOB;
		eobUtpPkt.setEndOfBlock(bn, numPacketsInThisBlock);
		byte[] eobData = eobUtpPkt.getRawData();
		DatagramPacket eobDatagram = new DatagramPacket(eobData, eobData.length, channelAddr, channelPort);
		return eobDatagram;
	}
	
	
	
	/**
	 * @param channelAddr
	 * @param dstAddr
	 * @param bn
	 * @param numPacketsInThisBlock
	 * @return
	 */
	private static DatagramPacket assembleEobDatagram(InetAddress channelAddr, InetAddress dstAddr, int bn, int numPacketsInThisBlock) {

		return assembleEobDatagram(channelAddr, dstAddr, bn, numPacketsInThisBlock, true);
	}

	
	
	
	
	
	
	


	/**
	 * @param txBuffer
	 * @param toBeSent
	 * @param windowLeft - Block Number of the leftmost block in the current window.
	 * @param bufferedBytes - the actual number of bytes in the buffer. It is generally fixed, but it can be
	 * any number between 0 and BUFFER_SIZE if the last block is also the last block of the file transfer operation.
	 * @param socket - the socket on which send and receive operations are performed
	 * @param channelAddr - IP address of Channel
	 * @param dstAddr - IP address of the destination (Server)
	 * @throws IOException if an I/O error occurs in the socket while sending the datagram
	 */
	private static void sendBlocksAndEobs(byte[] txBuffer, boolean[] toBeSent, int windowLeft,int bufferedBytes,
			DatagramSocket socket, InetAddress channelAddr,	InetAddress dstAddr, ScheduledThreadPoolExecutor schedExec) throws IOException {
		
		final int BYTES_IN_BLOCK = PKTS_IN_BLOCK * PKT_SIZE;
		int numBlocks = bufferedBytes / BYTES_IN_BLOCK + 1;	
		int bytesInLastBlock = bufferedBytes % BYTES_IN_BLOCK; // FIXME check bufferedbytes > 0 (or at least non-negative!)
		
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
			byte[] txBuf_thisBlock = new byte[bytesInThisBlock];
			System.arraycopy(txBuffer, BYTES_IN_BLOCK * i, txBuf_thisBlock, 0, bytesInThisBlock);
			
			boolean[] toBeSent_thisBlock = new boolean[PKTS_IN_BLOCK];
//			Utils.logg(numPktsInThisBlock + " pkts in block " + bnInBuffer[i]);
			System.arraycopy(toBeSent, PKTS_IN_BLOCK * i, toBeSent_thisBlock, 0, numPktsInThisBlock); // numPktsInThisBlock can be substituted by PKTS_IN_BLOCK, since anyway toBeSent has false-padding in the end
			
			int numPktsToSend = Utils.count(toBeSent_thisBlock, true);
			if (numPktsToSend == 0)
				continue;
			
			int numTxForCurrBlock = getNumTransmissions(numPktsToSend);
			//Utils.logg("- Sending " + Utils.count(toBeSent_thisBlock, true) + " packets from BN=" + bnInBuffer[i] + " (" + numTxForCurrBlock + " times)");
			for (int j = 0; j < numTxForCurrBlock; j++)
				sendSpecificDataPkts(txBuf_thisBlock, toBeSent_thisBlock, bnInWindow(i, windowLeft), socket, channelAddr, dstAddr);

			DatagramPacket eobPkt = assembleEobDatagram(channelAddr, dstAddr, bnInWindow(i, windowLeft), numPktsInThisBlock);
			eobCache[i] = assembleEobDatagram(channelAddr, dstAddr, bnInWindow(i, windowLeft), numPktsInThisBlock, false);
			
			try {
				Thread.sleep(eob_preSleep);
			} catch (InterruptedException e) {}
			int TEMPTIMES = 6; // FIXME
			schedExec.schedule(new AsyncRepeatedPacketSender(socket, eobPkt, TEMPTIMES, EOB_INTER_DELAY), EOB_PRE_DELAY, TimeUnit.MILLISECONDS);
			pendingEobAck[i] = true;
//			sendDatagram(socket, eobPkt);
//			sendDatagram(socket, eobPkt);
//			sendDatagram(socket, eobPkt);
			sentEobPkts += TEMPTIMES;
		}
		
		// Updates toBeSent array to specify that there are no pending packets to be sent
		Arrays.fill(toBeSent, 0, toBeSent.length, false);
	}


	
	
	
	
	private static int getNumTransmissions(int numPktsToSend) {
		if (numTransmissionsCache[numPktsToSend-1] == 0)
			numTransmissionsCache[numPktsToSend-1] = Math.round((int) Math.ceil(Math.exp(- numPktsToSend * 0.125 + 2.2)));
		return numTransmissionsCache[numPktsToSend-1];
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

		// Loop through packets in the current block, and send them away recklessly
		// Note that this loop may exceed the actual number of packets in this block, hence those dummy
		// packets must always be flagged as "not to be sent".
		for (int pktInd = 0; pktInd < PKTS_IN_BLOCK; pktInd++) {
			
			/*if (spaceOutFactor > 1 && pktInd == Math.round((double)PKTS_IN_BLOCK / spaceOutFactor))
				try {
					Thread.sleep(SPACE_OUT_DELAY);
				} catch (InterruptedException e) {}*/

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
			sendDatagram(socket, sndPkt);
			sentDataPkts++;
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
			System.err.println("I/O error while sending datagram");
			socket.close(); System.exit(-1);;
		}
	}
}