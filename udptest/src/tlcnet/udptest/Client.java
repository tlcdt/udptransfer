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
	private static final short ACK_TIMEOUT = 1300;
	private static final int DEF_CHANNEL_PORT = 65432; // known by client and server
	static final int DEF_CLIENT_PORT = 65431;
	static final int DEF_CLIENT_CTRL_PORT = 65430;
	
	private static final int CORE_POOL_SIZE = 20;
	private static final int EOB_PRE_SLEEP = 10;//15; attraverso router
	private static final int EOB_PRE_DELAY = 150; // TODO In localhost, with parameters {640, 50, 20}, 100 is the best. Below this, throughput doesn't change, but more packets are transmitted. The problem is that with different parameters this may not be the best choice!
	private static final int EOB_INTER_DELAY = 20;
	
	private static final int NUM_OF_EOBS = 5; // each time we send an EOB, we send it NUM_OF_EOBS times.
	
	static final int PKT_SIZE = 896;
	static final int PKTS_IN_BLOCK = 650;
	static final int BLOCKS_IN_BUFFER = 15;
	static final int BUFFER_SIZE = PKT_SIZE * PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
	static final int PKTS_IN_BUFFER = PKTS_IN_BLOCK * BLOCKS_IN_BUFFER;
	static final int BYTES_IN_BLOCK = PKTS_IN_BLOCK * PKT_SIZE;

	private static final int channelPort = DEF_CHANNEL_PORT;
	private static final int dstPort = Server.DEF_SERVER_PORT;
	
	private static int sentDataPkts = 0;
	private static int sentEobPkts = 0;
	private static int[] numTransmissionsCache = new int[PKTS_IN_BLOCK];
	private static int eob_preSleep = EOB_PRE_SLEEP;
	
	/*private static int spaceOutFactor = 1;
	private static final int SPACE_OUT_DELAY = 50;*/
	

	
	
	
	@SuppressWarnings("resource")
	public static void main(String args[]) throws IOException
	{
		InetAddress channelAddr;
		InetAddress dstAddr;
		DatagramSocket socket = null;
		DatagramSocket ctrlSocket = null;

		// Input arguments check
		if (args.length != 3) {
			System.out.println("Usage: java Client <dest address> <channel address> <local file>"); 
			return;
		}

		// Create sockets
		try {
			socket = new DatagramSocket(DEF_CLIENT_PORT);
			socket.setSoTimeout(1);	// timeout for reception
			ctrlSocket = new DatagramSocket(DEF_CLIENT_CTRL_PORT);
		} catch (SocketException e) {
			System.err.println("Error creating datagram socket:\n" + e);
			return;
		}

		// Parse input and create a handle to the file
		RandomAccessFile theFile = null;
		try {
			dstAddr = InetAddress.getByName(args[0]);
			channelAddr = InetAddress.getByName(args[1]);
			String fileName = args[2];
			theFile = new RandomAccessFile(fileName, "r");
		} catch (UnknownHostException | FileNotFoundException e) {
			System.err.println(e);
			socket.close();	return;
		}
		// Input stream to read from the file
		FileInputStream inStream = new FileInputStream(theFile.getFD());

		// Initialize left border of the sliding window
		int windowLeft = 1;
		
		// Flags
		boolean[] toBeSent = new boolean[PKTS_IN_BUFFER];	// tells which packets in the window must be sent
		boolean[] isBlockAcked = new boolean[BLOCKS_IN_BUFFER];
		
		// Flag to stop execution: it signals the end of transmission
		boolean theEnd = false;
		
		// Counters
		int bufferedBytes = 0;	// Bytes of useful data in the tx buffer
		int totBytesRead = 0;	// Bytes read from file during the whole execution
		
		// This is for generating EOB packets and handling EOB sequence numbers
		EobAssembler eobAssembler = new EobAssembler(BLOCKS_IN_BUFFER, channelAddr, channelPort, dstAddr, dstPort);

		// This object allows to send packets asynchronously with a specified delay. It's used for congestion control purposes only.
		ScheduledThreadPoolExecutor schedExec = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE);

		// Start stopwatch
		long startTransferTime = System.currentTimeMillis();
		
		// Transmission buffer: its size is the size of the block in bytes (we'll have zero padding at the end of the file transfer)
		byte[] txBuffer = new byte[BUFFER_SIZE];
		
		// A priori statistics
		double expectedLossProb = (1 - Math.exp(-(PKT_SIZE + UTPpacket.HEADER_LENGTH)/(double)1024));
		double lossThresh = 0.4 * 1 + 0.6 * expectedLossProb;
		Utils.logg("Expected loss probability is " + Math.round(expectedLossProb * 100) + "%");
		Utils.logg("Loss threshold is " + Math.round(lossThresh * 100) + "%\n");
		
		
		
		
		
		
		// * * * * * * * * * * * *//
		// * *  DATA TRANSFER  * *//
		// * * * * * * * * * * * *//


		EobTimers eobTimers = new EobTimers(BLOCKS_IN_BUFFER, ACK_TIMEOUT);
		
		// This object's job is to identify EOB ACK packets that have already been received before.
		// These packets will be dropped, so as to avoid useless retransmission of data packets.
		DuplicateIdHandler dupEobHandler = new DuplicateIdHandler();
		
		// Read first chunk from file
		int bytesRead = inStream.read(txBuffer, 0, txBuffer.length);

		// Update total byte counter
		totBytesRead += bytesRead;
		bufferedBytes += bytesRead;

		// Compute number of packets in the buffer
		int bufferedPkts = Math.min(PKTS_IN_BUFFER, bufferedBytes / PKT_SIZE + 1);

		// All packets are waiting to be sent. If there are not enough packets, the problem will be dealt with in the method sendBlocksAndEobs()
		Arrays.fill(toBeSent, 0, bufferedPkts, true);

		// Send first BLOCKS all at once. The buffer will be empty after this, and toBeSent will be all-false
		sendBlocksAndEobs(txBuffer, toBeSent, windowLeft, bufferedBytes, socket, ctrlSocket, channelAddr, dstAddr, eobAssembler, eobTimers, schedExec);



		/* * Main loop * */
		
		while(!theEnd) {
			

			// Handle timeout and retransmit proper EOB if needed
			for (int j = 0; j < BLOCKS_IN_BUFFER; j++)

				// Re-send EOB packets that have been awaiting ACK for too long
				if (eobTimers.hasTimedOut(j)) {
					Utils.logg("timeout: resending EOB " + bnInWindow(j, windowLeft));
					// We get from the cache a copy of the current EOB without SN, to avoid the possibility that the server discards it
					for (int k = 0; k < NUM_OF_EOBS; k++)
						sendDatagram(ctrlSocket, eobAssembler.getFromCache(j));
					sentEobPkts += NUM_OF_EOBS;
					// Just sent an EOB: reset the timer
					eobTimers.restartTimer(j);
				}

			// If we're done even without a FIN, close transmission
			if (eobTimers.getActiveNumber() == 0) {
				System.out.println("Timeout: no FIN received, but all data has been ACKed. Exit!");
				theEnd = true;
				break;
			}


			// ---- Receive packet ----
			
			byte[] recvBuf = new byte[RX_BUFSIZE];
			DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);

			// Block the thread for a bit and check if something's at the door. Not a problem since the
			// packets arriving at the client are not a lot.
			try {
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

			byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); // Payload of received UDP datagram
			UTPpacket recvEobAck = new UTPpacket(recvData);		// Parse UDP payload

			// If it's a FIN, end of transmission
			if (recvEobAck.function == UTPpacket.FUNCT_FIN) {
				theEnd = true;
				break;
			}

			// It's not EOB_ACK -> listen again
			if (recvEobAck.function != UTPpacket.FUNCT_EOB_ACK)
				continue;


			// > It's an EOB ACK! <
			
			// If another copy of this EOB ACK was already received (and processed) before, ignore it!
			if (!dupEobHandler.isNew(recvEobAck.sn)) continue;
			
			Utils.logg(recvEobAck.endOfBlockAck.numberOfMissingSN + " pkts\t missing from BN=" + recvEobAck.endOfBlockAck.bn);
			int numMissingPkts = recvEobAck.endOfBlockAck.numberOfMissingSN;	// Number of packets of this block that the server hasn't received yet
			int ackedBn = recvEobAck.endOfBlockAck.bn;	// BN of the block this ACK is referred to
			
			// Lost too many packets? Something's wrong: let's increase the pause between the tx of blocks
			/*if (numMissingPkts > PKTS_IN_BLOCK * lossThresh)
				eob_preSleep = (int) Math.ceil(eob_preSleep * 1.1);*/
			
			// Index of this packet's block in the current window. If it's outside the window, ignore it.
			int bnIndexInWindow = ackedBn - windowLeft;
			if (bnIndexInWindow < 0 || bnIndexInWindow > BLOCKS_IN_BUFFER - 1) continue;

			// Extract SNs of packets that are missing at the server (we're gonna flag them as toBeSent)
			int[] missingPkts = recvEobAck.endOfBlockAck.missingSN;
			int snOffset = (ackedBn - 1) * PKTS_IN_BLOCK + 1;		// SN offset for the BN that was just ACKed		
			int blockOffset = bnIndexInWindow * PKTS_IN_BLOCK;
			eobTimers.disableTimer(bnIndexInWindow);	// No longer waiting for the EOB ACK for this block
			
			// If all packets are correctly received, flag the current block as ACKed
			if (numMissingPkts == 0)
				isBlockAcked[bnIndexInWindow] = true;
			
			// Flag the missing packets as toBeSent
			for (int j = 0; j < numMissingPkts; j++) {
				int pktInd = missingPkts[j] - snOffset + blockOffset;
				toBeSent[pktInd] = true;
			}

			// The block at index zero is fine, and we still have data in the tx buffer: shift
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
				eobAssembler.shiftWindow();
				eobTimers.shiftWindow();
				Utils.shiftArrayLeft(isBlockAcked, 1);
				windowLeft++;
				Utils.shiftArrayLeft(toBeSent, PKTS_IN_BLOCK);
				// this time we also put a zero padding at the end of the flag array, just because we can.
				Arrays.fill(toBeSent, toBeSent.length - PKTS_IN_BLOCK, toBeSent.length - PKTS_IN_BLOCK + newPkts, true);				
			}

			// Send selected packets from all the blocks in the current window, and at the end of each block send an EOB packet
			sendBlocksAndEobs(txBuffer, toBeSent, windowLeft, bufferedBytes, socket, ctrlSocket, channelAddr, dstAddr, eobAssembler, eobTimers, schedExec);
		}

		int numDataPkts = totBytesRead / PKT_SIZE + 1;
		double elapsedTime = (double) (System.currentTimeMillis() - startTransferTime)/1000;
		double transferRate = totBytesRead / 1024 / elapsedTime;
		System.out.println("\nFile transfer complete! :(");
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
	 * @param - index
	 * @param - windowLeft
	 * @return - the Block Number of the block that is at the specified index in the current window.<br>
	 * - the value 0 if the index is invalid.
	 */
	private static int bnInWindow(int index, int windowLeft) {
		if (index < 0 || index > BLOCKS_IN_BUFFER - 1)
			return 0;
		return index + windowLeft;
	}


	
	
	
	
	
	


	/**
	 * 
	 * 
	 * @param txBuffer - the transmission buffer as array of bytes
	 * @param toBeSent - array of flags that indicate whether a packet in the buffer has to be sent
	 * @param windowLeft - Block Number of the leftmost block in the current window.
	 * @param bufferedBytes - the actual number of bytes in the buffer. It is generally fixed, but it can be
	 * any number between 0 and BUFFER_SIZE if the last block is also the last block of the file transfer operation.
	 * @param socket - the socket on which send and receive operations are performed
	 * @param ctrlSocket 
	 * @param channelAddr - IP address of Channel
	 * @param dstAddr - IP address of the destination (Server)
	 * @param eobAssembler - this object assembles EOB packets and stores them in a cache that has the size of the window, in order
	 * to retransmit them when needed. It must be the same object throughout the whole execution of the program
	 * @param eobTimers 
	 * @param schedExec - a ScheduledThreadPoolExecutor with a sufficient pool size: its purpose is to send EOB packets asynchronously
	 */
	private static void sendBlocksAndEobs(byte[] txBuffer, boolean[] toBeSent, int windowLeft,int bufferedBytes,
			DatagramSocket socket, DatagramSocket ctrlSocket, InetAddress channelAddr,	InetAddress dstAddr, EobAssembler eobAssembler, EobTimers eobTimers, ScheduledThreadPoolExecutor schedExec) {

		if (bufferedBytes < 0)	// should never happen, but better safe than sorry
			return;
		
		int numBlocks = bufferedBytes / BYTES_IN_BLOCK + 1;		// n. of blocks actually stored in the buffer
		int bytesInLastBlock = bufferedBytes % BYTES_IN_BLOCK;	// bytes of data in the last block stored in the buffer
		if (numBlocks > BLOCKS_IN_BUFFER && bytesInLastBlock == 0) {
			numBlocks = BLOCKS_IN_BUFFER;
			bytesInLastBlock = BYTES_IN_BLOCK;
		}
		
		for (int i = 0; i < numBlocks; i++) {
			int bytesInThisBlock = BYTES_IN_BLOCK;
			if (i == numBlocks - 1)	// if this is the last block actually stored in the buffer...
				bytesInThisBlock = bytesInLastBlock; // ...set the correct value to bytesInThisBlock
			
			// Number of packets stored in the current block: lower than usual only if this is the last block
			int numPktsInThisBlock = Math.min(PKTS_IN_BLOCK, bytesInThisBlock / PKT_SIZE + 1);
			// if the block is full, (bytesInThisBlock / PKT_SIZE + 1) would give an extra empty packet, which is true only if the number of packets is PKTS_IN_BLOCK-1 and the last packet is empty
			
			// Get the chunk of buffer that corresponds to this block
			byte[] txBuf_thisBlock = new byte[bytesInThisBlock];
			System.arraycopy(txBuffer, BYTES_IN_BLOCK * i, txBuf_thisBlock, 0, bytesInThisBlock);
			
			// Get the toBeSent flags of this block only
			boolean[] toBeSent_thisBlock = new boolean[PKTS_IN_BLOCK];
//			Utils.logg(numPktsInThisBlock + " pkts in block " + bnInBuffer[i]);
			System.arraycopy(toBeSent, PKTS_IN_BLOCK * i, toBeSent_thisBlock, 0, numPktsInThisBlock);
			// numPktsInThisBlock is the same as PKTS_IN_BLOCK, since anyway toBeSent has false-padding in the end.
			// But in the first transmission (before the loop) it is all true so we must check the actual number of pkts to send
			
			// Go on only if we need to actually send something (we know this from the flags in toBeSent)
			int numPktsToSend = Utils.count(toBeSent_thisBlock, true);
			if (numPktsToSend == 0)
				continue;
			
			// Need to send really few packets from this block? Why not send them even more times?
			int numTxForCurrBlock = getNumTransmissions(numPktsToSend);
			if (i < numBlocks/4)
				numTxForCurrBlock *= 4;
			//Utils.logg("- Sending " + Utils.count(toBeSent_thisBlock, true) + " packets from BN=" + bnInBuffer[i] + " (" + numTxForCurrBlock + " times)");
			for (int j = 0; j < numTxForCurrBlock; j++)
				sendSpecificDataPkts(txBuf_thisBlock, toBeSent_thisBlock, bnInWindow(i, windowLeft), socket, channelAddr, dstAddr);

			// Prepare EOB packet (eobAssembler will store it in an SN-less form in its cache)
			DatagramPacket eobPkt = eobAssembler.assembleEobDatagram(bnInWindow(i, windowLeft), numPktsInThisBlock);
			
			// Pause a little bit: the server may receive this too early (this may play a role in congestion control as well)
			try {
				if(eob_preSleep > 0)
					Thread.sleep(eob_preSleep);
			} catch (InterruptedException e) {}
			
			// Send EOB packet asynchronously: see javadoc for the class AsyncRepeatedPacketSender
			schedExec.schedule(new AsyncRepeatedPacketSender(ctrlSocket, eobPkt, NUM_OF_EOBS, EOB_INTER_DELAY), EOB_PRE_DELAY, TimeUnit.MILLISECONDS);
			eobTimers.restartTimer(i); // we are now waiting for an ACK of the current EOB: set the timer
			
			sentEobPkts += NUM_OF_EOBS;	// stupid counter
		}
		
		// Updates toBeSent array to specify that there are no pending packets to be sent
		Arrays.fill(toBeSent, 0, toBeSent.length, false);
	}


	
	
	
	private static int getNumTransmissions(int numPktsToSend) {
		if (numTransmissionsCache[numPktsToSend-1] == 0)
			numTransmissionsCache[numPktsToSend-1] = Math.round((int) Math.ceil(Math.exp(- numPktsToSend * 0.08 + 2.9)));//0.125 + 2.9)));
		return numTransmissionsCache[numPktsToSend-1];
	}



	

	/**
	 * Sends specific data packets (those that have not been received yet by the server) from the current block.
	 * 
	 * @param txBuffer - a byte array with all data of this block (just the current block, not the whole buffer)
	 * @param toBeSent - array of flags that indicate whether a packet from this block has to be sent
	 * @param bn - Block Number of the current block
	 * @param socket - the socket on which send and receive operations are performed
	 * @param channelAddr - IP address of Channel
	 * @param dstAddr - IP address of the destination (Server)
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

			// Payload of the current UDP packet. It's taken from the tx buffer.
			byte[] currPkt = Arrays.copyOfRange(txBuffer, currPktStart, currPktEnd);

			// Create UTPpacket object and get a DatagramPacket
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
	 * Sends a DatagramPacket (UDP datagram) over the specified socket, and terminates the process if an error occurs.
	 * 
	 * @param socket - the socket on which the packet must be sent
	 * @param outPkt - the outgoing packet
	 */
	private static void sendDatagram(DatagramSocket socket, DatagramPacket outPkt) {
		try {
			socket.send(outPkt);
		} catch(IOException e) {
			System.err.println("I/O error while sending datagram");
			socket.close(); System.exit(-1);;
		}
	}	
}