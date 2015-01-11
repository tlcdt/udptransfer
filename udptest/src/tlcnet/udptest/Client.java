package tlcnet.udptest;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class Client
{
	private static final int RX_BUFSIZE = 2048; // Exceeding data will be discarded: note that such a datagram would be fragmented by IP
	private static final int ACK_TIMEOUT = 1000;
	private static final int DEF_CHANNEL_PORT = 65432; // known by client and server
	static final int DEF_CLIENT_PORT = 65431;
	static final int BLOCK_SIZE = 512;
	static final int WINDOW_SIZE = 63;



	public static void main(String args[]) throws IOException
	{
		int channelPort = DEF_CHANNEL_PORT;
		int dstPort = Server.DEF_SERVER_PORT;
		InetAddress channelAddr;
		InetAddress dstAddr;
		long startTransferTime = 0;		//This is the timer variable
		DatagramSocket socket = null;

		if (args.length != 3) {
			System.out.println("Usage: java Client <dest address> <channel address> <local file>"); 
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
		ByteBuffer chunkContainer = ByteBuffer.allocate(WINDOW_SIZE*BLOCK_SIZE);		//chunkContainer has the size of a window of pcks


		
		// * * * * * * * * * * * * * *//
		// * *  DATA TRANSFER LOOP * *//
		// * * * * * * * * * * * * * *//

		
		// ---- Initialization and useful variables ----
		long sn = 1; 
		int begin = 0;	//begin always refears to array "first"; end is the index of the last byte of the window
		int end = (WINDOW_SIZE - 1)*BLOCK_SIZE; //begin and end keep track of the frames we are sending
		
		String ack = Utils.AckToBinaryString(0, WINDOW_SIZE);	//this string will contain the ack in binary. 0 = not acked; 1 = "acked"
		boolean first_pck = true;						//start of transmission
		byte first[] = new byte[WINDOW_SIZE*BLOCK_SIZE];//Two arrays of bytes that help sending data. They are to be considered consecutive
		byte sec[] = new byte[WINDOW_SIZE*BLOCK_SIZE]; 	
		boolean mustSend = true;						//Used to stop the loop
		int segmentCounter = 1;							//To know how many "windows" we sent
		int pcksInFirst = WINDOW_SIZE;					//How many pcks of the window are in the first byte array
		boolean slide = false;							//Used when we have to shift the window
		long lastSn = 0;									//It becomes != 0 when we reach the final packet
		long lastSnInWindow = 0;
		boolean resending = false;
		boolean rep = false;
		int ackSlide = 0;
		boolean pcksInFirstFixer = true;
		
		while(mustSend)	{
			int offset = 0; 							//it's the entity of the window-slide measured in packets
			if(first_pck)	{
				first_pck = false;						//The beginning comes just once :P
				first = fill(first, chunkContainer, inChannel);	//Initialization: check it out below
				sec = fill(sec, chunkContainer, inChannel);
				if(sec.length == 0 && first.length != 0 && first.length < WINDOW_SIZE*BLOCK_SIZE)	{	//This is for small files
					if(first.length%BLOCK_SIZE != 0)
						end = (first.length/BLOCK_SIZE)*BLOCK_SIZE;
					else
						end = first.length - BLOCK_SIZE;			//This is to deal with very small pcks
					lastSn = end + 1;
				}
				else if(sec.length == 0 && first.length == 0)	{	//TODO: Should we fix empty pck transmission?
					System.out.println("Empty packet. Shutting down.");	
					socket.close();
					theFile.close();
					return;
				}
				// ---- Sending the first window ----
				startTransferTime = System.currentTimeMillis();		//Here we start the timer
				lastSnInWindow = WINDOW_SIZE;						//UTPpacket new field
				for(int i = begin; i <= end; i += BLOCK_SIZE)	{
					int pck_size = BLOCK_SIZE;
					if(i == end && first.length%BLOCK_SIZE != 0)	//last pck could be smaller than BLOCK_SIZE
						pck_size = first.length%BLOCK_SIZE;
					byte[] temp = new byte[pck_size]; 
					System.arraycopy(first, i, temp, 0, pck_size);	//Check out method "send" below
					send(lastSnInWindow, lastSn, sn, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
					sn++;											
				}
			}
			
			// the next thing will be done only if !first_pck
			else	{
				// ---- Check the ack-String for nacks. Then resend nacked pcks. If possible, slide the window and send new pcks. ---
				if(ack.equals(Utils.AckToBinaryString((int) Math.pow(2, WINDOW_SIZE) - 1, WINDOW_SIZE)))	{
						offset = WINDOW_SIZE;
						lastSnInWindow = sn - 1 + offset;
					}
				if(lastSn != 0 && pcksInFirstFixer)	{
					if(pcksInFirst - (lastSnInWindow - lastSn) <= 0)	{
						first = sec; 	//Points to sec array
						begin = (int) (lastSnInWindow - lastSn) - pcksInFirst;
						pcksInFirst = 10;			//Maximize
					}
					else	{
						pcksInFirst = pcksInFirst - (int) (lastSnInWindow - lastSn);
					begin = begin + (int) (lastSnInWindow - lastSn);
					}
					pcksInFirstFixer = false;
				}
				boolean firstDroppedPck = false;
				for(int i = 0; i < ack.length(); i++)	{
					
					if(ack.substring(i,i+1).equals("0"))	{		//Note that if character is "1", we ignore it
						if(!rep && !firstDroppedPck && lastSn == 0)	{
							firstDroppedPck = true;
							offset = i;					
						}
						if(lastSn == 0)
							lastSnInWindow = sn - 1 + offset;
						else	{
							sn = lastSnInWindow + 1;
							System.out.println("lastSn = " + lastSn + " pcksInFirst = " + pcksInFirst + "i = " + i);
						}
						
						// ---- Re-send the dropped pcks ----
						if(i < pcksInFirst)	{		   				//Are they in first byte array?...
							
							int pck_size = BLOCK_SIZE;
							if((i*BLOCK_SIZE == end) && sec.length - end < BLOCK_SIZE && first.length%BLOCK_SIZE != 0)	{
								pck_size = first.length%BLOCK_SIZE;
							}
							byte[] temp = new byte[pck_size]; 
							System.arraycopy(first, begin + i*BLOCK_SIZE, temp, 0, pck_size);
							send(lastSnInWindow, lastSn, sn - WINDOW_SIZE + i, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						}
						else	{									//...or in second byte array?
							int index = i - pcksInFirst;
							int pck_size = BLOCK_SIZE;
							//System.out.println("end is " + end + "sec.length is " + sec.length + " index is " + index);
							if((index*BLOCK_SIZE == end) && sec.length - end < BLOCK_SIZE && sec.length%BLOCK_SIZE != 0)	{
								pck_size = sec.length%BLOCK_SIZE;
								System.out.println("shorter packet!! length is " + pck_size);
							}
							byte[] temp = new byte[pck_size]; 
							System.arraycopy(sec, index*BLOCK_SIZE, temp, 0, pck_size);
							send(lastSnInWindow, lastSn, sn - WINDOW_SIZE + i, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						}
					}
				}
				
				ackSlide = offset;
				
				// ---- Slide the window if possible ----
				
				if(offset!=0 && lastSn == 0 && !rep)	{
					//Case 1: we can slide without problems (here we don't accept sliding to the end of sec array)
					if((WINDOW_SIZE - pcksInFirst + offset)*BLOCK_SIZE < sec.length)	{
						begin += offset*BLOCK_SIZE;
						end = (WINDOW_SIZE - pcksInFirst + offset - 1)*BLOCK_SIZE;
						pcksInFirst = (first.length - begin)/BLOCK_SIZE;
						slide = true;
					}
					//Case 2: Not possible to slide so much. Sec array is shorter than the normal size: we are transmitting last window. 
					//So we slide as much as we can!
					else if(sec.length!=0 && sec.length < BLOCK_SIZE*WINDOW_SIZE)	{
						if(sec.length%BLOCK_SIZE != 0)		//Last packet could be smaller than BLOCK_SIZE
							if(begin == 0)
								offset = sec.length/BLOCK_SIZE +1;
							else
								offset = (sec.length - end)/BLOCK_SIZE;
						else
							if(begin == 0)
								offset = sec.length/BLOCK_SIZE;
							else
								offset = (sec.length - end)/BLOCK_SIZE - 1;
						begin += offset*BLOCK_SIZE;
						end = (WINDOW_SIZE - pcksInFirst + offset - 1)*BLOCK_SIZE ;
						pcksInFirst = (first.length - begin)/BLOCK_SIZE;			
						// --- This is the last window ----
						lastSn = sn - 1 + offset;
						if(offset != 0)		//Real offset may have changed
							slide = true;
					}
					//Oh, we must read new bytes from chunkContainer!
					else if(sec.length == BLOCK_SIZE*WINDOW_SIZE)	{
						System.arraycopy(sec, 0, first, 0, WINDOW_SIZE*BLOCK_SIZE);
						//String p = new String(first, "UTF-8");
						//System.out.println(p);
						sec = fill(sec, chunkContainer, inChannel);
						//Case 3: Ok, now we can slide without any problems
						System.out.println("LENGTH OF SECOND ARRAY after sliding = " + sec.length + " pcksInFirst = " + pcksInFirst + " end = " + end + " offset = " + offset);
						if(( (offset*BLOCK_SIZE) - (first.length - end - BLOCK_SIZE) <= sec.length) || (begin == 0 && offset == WINDOW_SIZE))	{
							begin = (offset - pcksInFirst)*BLOCK_SIZE;
							if(begin == 0)	{
								end = (WINDOW_SIZE -1)*BLOCK_SIZE;
								System.out.println("END " + end);
							}
							else	{
								end = begin - BLOCK_SIZE;
							}
							pcksInFirst = (first.length - begin)/BLOCK_SIZE;
							slide = true;
						}
						//Case 4: Oh, man...sec array is shorter than usual and we cannot slide so much. This is the last window!
						//We slide as much as we can
						else if(sec.length != 0 && sec.length < BLOCK_SIZE*WINDOW_SIZE)	{
							
							if(sec.length%BLOCK_SIZE != 0)	{
								end = (sec.length/BLOCK_SIZE)*BLOCK_SIZE;
								begin = end + BLOCK_SIZE;
								offset = pcksInFirst + begin/BLOCK_SIZE;
							}
							else	{
								end = (sec.length/BLOCK_SIZE - 1)*BLOCK_SIZE;
								begin = end + BLOCK_SIZE;
								offset = pcksInFirst + begin/BLOCK_SIZE;
							}
							lastSn = sn - 1 + offset;
							pcksInFirst = (first.length - begin)/BLOCK_SIZE;
							slide = true;
							System.out.println("END IS " + end);
						}
						//Case 5: sec.length is equal to 0!
						else	{
							end = (WINDOW_SIZE - 1)*BLOCK_SIZE;
							begin = 0;
							offset = pcksInFirst + begin/BLOCK_SIZE;
							pcksInFirst = WINDOW_SIZE;
							lastSn = sn - 1 + offset;
							if(offset != 0)
								slide = true;
						}
					}
				}
			}
			// ---- Fix the old "ack" string to be consistent with the slide
			if(slide)	{
				System.out.println("Problems? offset = " + offset + " end = " + end + ", and ackSlide = " + ackSlide);
				String zeros = Utils.AckToBinaryString(0, ackSlide);
				ack = ack.substring(ackSlide) + zeros;
				//System.out.println("packets in first = " + pcksInFirst);
			}
			
			
			// ---- Send new pcks, if possible ----
			
			if(slide)	{			//of course here we just send packets that have never been sent before!
				for(int i = WINDOW_SIZE - offset; i < WINDOW_SIZE; i++)	{
					if(pcksInFirst - i > 0)	{
						int pck_size = BLOCK_SIZE;
						if((i == WINDOW_SIZE - 1) && first.length%BLOCK_SIZE != 0)	{	//TODO: is this if really necessary, here?
							pck_size = first.length%BLOCK_SIZE;
						}
						//System.out.println("Debug: (First array) before arraycopy. i = " + i + " and pck_size = " + pck_size + " and pcksinfirst = " + pcksInFirst);
						byte[] temp = new byte[pck_size]; 
						System.arraycopy(first, begin + i*BLOCK_SIZE, temp, 0, pck_size);
						send(lastSnInWindow, lastSn, sn, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						sn++;
					}
					else	{
						int index = i - pcksInFirst;
						int pck_size = BLOCK_SIZE;
						if((index*BLOCK_SIZE == end) && sec.length - end < BLOCK_SIZE && sec.length%BLOCK_SIZE != 0)	{		//TODO cheeeck
							pck_size = sec.length%BLOCK_SIZE;
							System.out.println("shorter packet! Size = " + pck_size);
						}
						//System.out.println("Debug... before arraycopy: index = " + index + "; pck_size = " + pck_size + "; end = " + end);
						byte[] temp = new byte[pck_size]; 
						
						System.arraycopy(sec, index*BLOCK_SIZE, temp, 0, pck_size);
						send(lastSnInWindow, lastSn, sn, temp, dstAddr, dstPort, channelAddr, channelPort, socket, theFile);
						sn++;
					}
				}
				segmentCounter++;
				slide = false;		//Ok, we slided, next time we'll see if it's still possible
			}
			
			// ---- Receive ack ----
			long ack_start = System.currentTimeMillis();
			long countOnes = 0;
			boolean keepTrying = true;
			boolean receivedSomething = false;
			int recSn = 0;
			//System.out.println("Il timer è a " + ack_start);
			while(keepTrying && System.currentTimeMillis() - ack_start < 2000)	{
				
				byte[] recvBuf = new byte[RX_BUFSIZE];
				DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
				socket.setSoTimeout(ACK_TIMEOUT);
				try{
					socket.receive(recvPkt);
				}
				catch (SocketTimeoutException e) {		
					System.out.println("Timeout for ack expired...");
					continue;
				}
				catch(IOException e) {
					System.err.println("I/O error while receiving ack packet:\n" + e);
					socket.close(); System.exit(-1);
				}
				byte[] recvData = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()); 	// Payload of recv UDP datagram
				UTPpacket recvUTPpkt = new UTPpacket(recvData);								// Parse UDP payload
				if(recvUTPpkt.function == UTPpacket.FUNCT_ACKFIN)	{
					System.out.println("ACK_FIN!!!");
					mustSend = false;
					keepTrying = false;
					System.out.println("********************/nEnd of transmission at SN " + lastSn + ". Transmission time was: " + (System.currentTimeMillis() - startTransferTime)/1000 + " seconds./n********************");
					System.out.println("Bye Server...I always loved you T_T...take care until next transmission!/nForever yours, Client.");
				}
				
				if ((recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA || recvUTPpkt.function == UTPpacket.FUNCT_ACKFIN) && recvUTPpkt.lastSnInWindow == lastSnInWindow)	{
					//System.out.println("Ok, the ack should be received now........");
					if(!rep)	{			//this window hasn't already been sent
						keepTrying = false;
					}
					else if((recvUTPpkt.function == UTPpacket.FUNCT_ACKDATA || recvUTPpkt.function == UTPpacket.FUNCT_ACKFIN) && recvUTPpkt.sn > countOnes)	{		//The packet with the most acks wins
						countOnes = recvUTPpkt.sn;
						ack = Utils.AckToBinaryString(recvUTPpkt.sn, WINDOW_SIZE);				//Check it out in Utils class
					}
					
				}
			}
			if(keepTrying && countOnes == 0)	{
				System.out.println("Couldn't receive any ack for segment " + segmentCounter + "...resending.");
				//if(!rep && lastSn == 0)
					//ack = Utils.AckToBinaryString(0, WINDOW_SIZE);
				rep = true;
				resending = true;
			}
			else	{
				receivedSomething = true;
				System.out.println("keepTrying = " + keepTrying + " and countOnes = " + countOnes + ".So...this is the ack");
				System.out.println("ACK n. " + segmentCounter + ": " + ack);
			}

			//Note next if-clause: it's needed because lastSnInWindow field may be wrong if last window is forced to be smaller 
			//(no more data). I realized that, since we send old nacked packets BEFORE sliding the window, when we send them 
			//we don't know if we'll have more packets to send or not. So when we reach last packet, we take care of the received
			//ack, fixing it.
				
			if(ack.substring(0, 1).equals("0") || lastSn != 0)
				rep = true;
			else
				rep = false;
			if(lastSn != 0 && lastSnInWindow != lastSn)	{
				int digits = (int) (lastSnInWindow - lastSn);
				String ones = Utils.AckToBinaryString((int) Math.pow(2, digits) - 1, digits);
				ack = ack.substring(0, WINDOW_SIZE - digits) + ones;
			}
		}
	}
	
	
	//SOME AUXILIARY METHODS
	
	static byte[] fill(byte[] array, ByteBuffer container, FileChannel channel)	throws IOException {	//fills an array with data
		long howManyBytes = channel.read(container);
		if(howManyBytes == BLOCK_SIZE*WINDOW_SIZE)	{
			container.flip();	//Important! Otherwise .remaining() method gives 0
			container.get(array, 0, array.length);
			container.clear();
			return array;
		}
		else if(howManyBytes > 0)	{
			container.flip();	
			array = new byte[container.remaining()];		//resize the array
			System.out.println("*********************/nREMAINING BYTES = " + container.remaining() + "\n*****************\n");
			container.get(array, 0, array.length);
			container.clear();
			return array;
		}
		else array = new byte[0];							//empty array
		return array;
	}
	
	static void send(long lastSnInWindow, long lastSn, long sn, byte[] temp, InetAddress dstAddr, int dstPort, InetAddress channelAddr, int channelPort, DatagramSocket socket, RandomAccessFile theFile) 
	throws IOException {		//Just the usual boring stuff
		UTPpacket sendUTPpkt = new UTPpacket();
		sendUTPpkt.sn = sn;
		sendUTPpkt.dstAddr = dstAddr;
		sendUTPpkt.dstPort = (short)dstPort;
		sendUTPpkt.lastSnInWindow = (int) lastSnInWindow;	//This is just a reference for receiver
		if(sn == lastSn)		//NOTE: last packet carries data, but it has FIN as function field
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_FIN;
		else
			sendUTPpkt.function = (byte) UTPpacket.FUNCT_DATA;
		sendUTPpkt.payl = temp;	
		byte[] sendData = sendUTPpkt.getRawData();
		DatagramPacket sndPkt = new DatagramPacket(sendData, sendData.length, channelAddr, channelPort);
		//----DEBUG----
		System.out.println("/n--DEBUG--/nLast sn in window is " + lastSnInWindow + "; SN is " + sn + "; size of payload is: " + sendUTPpkt.payl.length);
		if(lastSnInWindow - sn < 0)
			System.out.println("ERRORE!! CONTROLLARE L'AGGIORNAMENTO DI LASTSNINW.");
		try {
			System.out.println("------\nSending SN = " + sn + "\n------");
			System.out.println("Payload: /n");
			//String p = new String(temp, "UTF-8");
			//System.out.println(p);
			socket.send(sndPkt);
		}
		catch(IOException e) {
			System.err.println("I/O error while sending datagram");
			socket.close(); theFile.close();
			System.exit(-1);
		}
	}
}
